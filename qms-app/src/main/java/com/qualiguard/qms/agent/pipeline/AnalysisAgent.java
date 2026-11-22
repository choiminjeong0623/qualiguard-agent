package com.qualiguard.qms.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer;

/**
 * 원인 분석 에이전트 (LLM): agent.analysis.requested 를 받아 도구로 근거를 모으고 원인을 추정한 뒤
 * agent.analysis.completed 를 보낸다. 검증 에이전트가 되돌린 경우 입력에 앞선 결과와 지적이 붙어 온다.
 */
public class AnalysisAgent {

    private final IncidentAnalyzer analyzer;
    private final IncidentStore store;
    private final EventBus bus;

    public AnalysisAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, AgentEventRouter router) {
        this.analyzer = analyzer;
        this.store = store;
        this.bus = bus;
        router.register(AgentTopics.ANALYSIS_REQUESTED, this::onRequested);
    }

    void onRequested(AgentEvent e) {
        Incident i = store.get(e.incidentId()).orElse(null);
        if (i == null) {
            return;   // 재시작 전 사건 등: 오케스트레이터가 모르는 사건은 무시
        }
        List<String> attemptTrace = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            AnalysisResult r = analyzer.analyzeOnce(i.prompt, i.job.asOf(), i.startNanos, attemptTrace, i.observed);
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
        bus.publish(AgentTopics.ANALYSIS_COMPLETED, new AgentEvent(i.id, e.attempt(), data));
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
