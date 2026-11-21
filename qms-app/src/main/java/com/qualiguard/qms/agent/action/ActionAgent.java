package com.qualiguard.qms.agent.action;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.agent.pipeline.AgentEvent;
import com.qualiguard.qms.agent.pipeline.AgentEventRouter;
import com.qualiguard.qms.agent.pipeline.AgentTopics;

/**
 * 조치 에이전트: agent.action.requested 를 받아
 *  1. 조치 목록에서 원인에 맞는 조치를 고르고 (없으면 끝)
 *  2. 안전장치(ActionPolicy)로 자동 실행할지 사람 승인을 받을지 정하고
 *  3. 기록한다. 자동이면 바로 실행 표시를 해서 효과 확인 대상이 된다.
 * 규칙 기반이라 LLM 을 부르지 않는다. 실제 설비 제어는 하지 않고 "실행했다"는 기록만 남긴다 (가상 실행).
 */
@Component
@ConditionalOnProperty(name = "qg.agent.action.enabled", havingValue = "true")
public class ActionAgent {

    private static final Logger log = LoggerFactory.getLogger(ActionAgent.class);

    private final ActionStore store;
    private final ActionPolicy policy;

    public ActionAgent(ActionStore store, AgentEventRouter router,
            @Value("${qg.agent.action.max-auto-per-day:3}") int maxAutoPerDay) {
        this.store = store;
        this.policy = new ActionPolicy(maxAutoPerDay);
        router.register(AgentTopics.ACTION_REQUESTED, this::onRequested);
    }

    void onRequested(AgentEvent e) {
        Map<String, Object> d = e.data();
        ActionCatalog.Action action = ActionCatalog.forCause(str(d.get("decision")), str(d.get("causeCode")));
        if (action == null) {
            return;   // 관찰·NOISE: 조치할 것이 없다
        }
        String equipmentId = str(d.get("equipmentId"));
        int analysisId = num(d.get("analysisId"));
        int confidence = d.get("confidence") == null ? 0 : num(d.get("confidence"));
        boolean verified = Boolean.TRUE.equals(d.get("verified"));

        // 효과가 없어 다시 분석한 결과면 몇 번째 조치인지 이어 센다
        var prev = store.retriedFrom(d.get("parentAnalysisId") == null ? null : num(d.get("parentAnalysisId")));
        int attempt = prev.map(p -> num(p.get("attempt")) + 1).orElse(1);
        Integer parentActionId = prev.map(p -> num(p.get("action_id"))).orElse(null);

        ActionPolicy.Decision decision = policy.decide(new ActionPolicy.Context(action, str(d.get("severity")),
                confidence, verified, store.autoCountLast24h(equipmentId)));
        int actionId = store.insert(analysisId, equipmentId, str(d.get("causeCode")), action, decision, attempt,
                parentActionId);
        if (decision.mode() == ActionPolicy.Mode.AUTO) {
            store.markExecuted(actionId, null);
        }
        log.info("[조치] {} {} → {} ({}) {} — {}", equipmentId, d.get("causeCode"), action.code(), action.risk(),
                decision.mode() == ActionPolicy.Mode.AUTO ? "자동 실행" : "승인 대기", decision.reasons());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int num(Object o) {
        return ((Number) o).intValue();
    }
}
