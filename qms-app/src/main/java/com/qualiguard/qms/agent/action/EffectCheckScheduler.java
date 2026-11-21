package com.qualiguard.qms.agent.action;

import com.qualiguard.qms.agent.pipeline.AgentPipeline;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실행한 조치의 효과를 주기적으로 확인한다.
 * 해소 → RESOLVED, 미해소 → 그 원인을 빼고 재분석, 시도 한도 초과 → ESCALATED(담당자 확인).
 */
@Component
@ConditionalOnProperty(name = "qg.agent.action.enabled", havingValue = "true")
public class EffectCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(EffectCheckScheduler.class);
    private static final int BEFORE_SHOTS = 30;

    private final ActionStore store;
    private final AgentPipeline pipeline;
    private final EffectEvaluator evaluator;
    private final int checkShots;
    private final int maxAttempts;

    public EffectCheckScheduler(ActionStore store, AgentPipeline pipeline,
                                @Value("${qg.agent.action.check-shots:40}") int checkShots,
                                @Value("${qg.agent.action.max-attempts:2}") int maxAttempts) {
        this.store = store;
        this.pipeline = pipeline;
        this.checkShots = checkShots;
        this.maxAttempts = maxAttempts;
        this.evaluator = new EffectEvaluator(checkShots * 3 / 4, 0.5);
    }

    @Scheduled(fixedDelayString = "${qg.agent.action.poll-ms:5000}",
               initialDelayString = "${qg.agent.action.poll-ms:5000}")
    public void poll() {
        for (Map<String, Object> row : store.dueForCheck(checkShots)) {
            try {
                check(row);
            } catch (Exception e) {
                log.warn("조치 효과 확인 실패 action_id={}: {}", row.get("action_id"), e.toString());
            }
        }
    }

    private void check(Map<String, Object> row) {
        int actionId = ((Number) row.get("action_id")).intValue();
        String equipmentId = (String) row.get("equipment_id");
        long baseShot = ((Number) row.get("base_shot")).longValue();
        ActionCatalog.Action action = ActionCatalog.byCode((String) row.get("action_code"));
        String sensor = action == null ? null : action.targetSensor();

        EffectEvaluator.Window w = store.window(equipmentId, sensor, baseShot, BEFORE_SHOTS, checkShots);
        EffectEvaluator.Effect effect = evaluator.evaluate(w);
        switch (effect.verdict()) {
            case NOT_ENOUGH_DATA -> log.info("조치 {} 판정 보류: {}", actionId, effect.detail());
            case RESOLVED -> store.markChecked(actionId, "RESOLVED", effect.detail());
            case NOT_RESOLVED -> notResolved(row, actionId, action, effect.detail());
        }
    }

    private void notResolved(Map<String, Object> row, int actionId, ActionCatalog.Action action, String detail) {
        int attempt = ((Number) row.get("attempt")).intValue();
        String rollback = action == null ? "-" : action.rollback();
        if (attempt >= maxAttempts) {
            store.markChecked(actionId, "ESCALATED",
                    "조치 " + attempt + "회 모두 효과 없음 → 담당자 확인 필요. " + detail + " / 되돌리기: " + rollback);
            return;
        }
        if (pipeline.inFlight() > 0) {
            return; // 진행 중인 분석이 끝난 뒤 다음 폴링에서 다시 본다
        }
        store.markChecked(actionId, "NOT_RESOLVED", detail + " / 되돌리기: " + rollback);
        try {
            pipeline.startRetry(((Number) row.get("analysis_id")).intValue(), (String) row.get("cause_code"));
        } catch (Exception e) {
            store.markChecked(actionId, "ESCALATED",
                    "재분석을 시작하지 못함(" + e.getMessage() + ") → 담당자 확인 필요.");
        }
    }
}
