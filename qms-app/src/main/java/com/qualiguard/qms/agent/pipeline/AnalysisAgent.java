package com.qualiguard.qms.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.ml.MlTriage;

/**
 * 원인 분석 에이전트 (LLM): agent.analysis.requested 를 받아 도구로 근거를 모으고 원인을 추정한 뒤
 * agent.analysis.completed 를 보낸다. 검증 에이전트가 되돌린 경우 입력에 앞선 결과와 지적이 붙어 온다.
 */
public class AnalysisAgent {

    private final IncidentAnalyzer analyzer;
    private final IncidentStore store;
    private final EventBus bus;
    /** ML 먼저, 애매하면 LLM (실험 기능, 기본 꺼짐) */
    private final MlTriage ml;

    public AnalysisAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, AgentEventRouter router) {
        this(analyzer, store, bus, router, MlTriage.off());
    }

    public AnalysisAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, AgentEventRouter router,
            MlTriage ml) {
        this.analyzer = analyzer;
        this.store = store;
        this.bus = bus;
        this.ml = ml == null ? MlTriage.off() : ml;
        router.register(AgentTopics.ANALYSIS_REQUESTED, this::onRequested);
    }

    void onRequested(AgentEvent e) {
        Incident i = store.get(e.incidentId()).orElse(null);
        if (i == null) {
            return;   // 되살리지 못한 사건 등: 오케스트레이터가 모르는 사건은 무시
        }
        if (i.state != Incident.State.ANALYZING || e.attempt() != i.attempts) {
            return;   // 지난 시도의 요청이 다시 온 경우 (Kafka 재전달·재시작 뒤 재발행)
        }
        if (i.result != null || i.error != null) {
            // 이 시도는 이미 분석했다 (결과를 기록한 뒤 완료 이벤트를 보내기 전에 앱이 멈춘 경우): LLM 을 다시 부르지 않고 알리기만
            bus.publish(AgentTopics.ANALYSIS_COMPLETED, new AgentEvent(i.id, e.attempt(), Map.of("replayed", true)));
            return;
        }
        List<String> attemptTrace = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            // 첫 시도에만 ML 을 먼저 본다 (검증 에이전트가 되돌린 두 번째 시도는 LLM 이 지적을 보고 다시 분석)
            MlTriage.Prediction p = e.attempt() == 1 ? mlPredict(i, attemptTrace) : null;
            boolean skipped = ml.skipLlm(p);
            if (p != null) {
                data.put("mlCause", p.cause());
                data.put("mlProbability", Math.round(p.probability() * 1000) / 1000.0);
                data.put("mlSkippedLlm", skipped);
                attemptTrace.add("[ML 1차 분류] " + p.summary(ml.threshold()) + " → "
                        + (skipped ? "LLM 건너뜀, 조회 도구로 근거 수집" : ml.mode() == MlTriage.Mode.SHADOW
                                ? "기록만 함 (shadow), LLM 분석" : "LLM 으로 넘김"));
            }
            AnalysisResult r = skipped ? ml.result(i.job, p, i.startNanos, attemptTrace, i.observed)
                    : analyzer.analyzeOnce(i.job, i.prompt, i.startNanos, attemptTrace, i.observed);
            i.result = r;
            data.put("decision", r.decision());
            data.put("causeCode", r.causeCode());
            data.put("confidence", r.confidence());
        } catch (RuntimeException ex) {
            i.error = causeChain(ex);
            data.put("error", i.error);
        } finally {
            i.lastAttemptTrace = List.copyOf(attemptTrace);
            i.trace.addAll(attemptTrace);   // 실패해도 어디까지 조사했는지 남긴다
        }
        data.put("toolCalls", attemptTrace.size());
        store.checkpoint(i);   // 분석 결과를 먼저 남기고 알린다 (재시작해도 LLM 을 다시 부르지 않게)
        bus.publish(AgentTopics.ANALYSIS_COMPLETED, new AgentEvent(i.id, e.attempt(), data));
    }

    /** ML 판단 실패(모델·DB 문제)는 분석을 막지 않는다: 기록하고 LLM 으로 넘긴다. */
    private MlTriage.Prediction mlPredict(Incident i, List<String> trace) {
        if (!ml.enabled()) {
            return null;
        }
        try {
            return ml.predict(i.job).orElse(null);
        } catch (RuntimeException ex) {
            trace.add("[ML 1차 분류] 실패, LLM 으로 넘김: " + ex.getMessage());
            return null;
        }
    }

    static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        Throwable t = e.getCause();
        int depth = 0;
        while (t != null && t != t.getCause() && depth++ < 5) {
            sb.append(" ← ").append(t);
            t = t.getCause();
        }
        return sb.toString();
    }
}
