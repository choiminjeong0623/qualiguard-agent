package com.qualiguard.qms.agent.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.VerifierAgent;

/**
 * 검증 에이전트의 파이프라인 연결: agent.verification.requested 를 받아 VerifierAgent 로 검증하고
 * agent.verification.completed (PASS / WEAK + 지적) 를 보낸다. 되돌릴지는 오케스트레이터가 정한다.
 */
public class VerifierStage {

    private final VerifierAgent verifier;
    private final IncidentAnalyzer analyzer;
    private final IncidentStore store;
    private final EventBus bus;

    public VerifierStage(VerifierAgent verifier, IncidentAnalyzer analyzer, IncidentStore store, EventBus bus,
            AgentEventRouter router) {
        this.verifier = verifier;
        this.analyzer = analyzer;
        this.store = store;
        this.bus = bus;
        router.register(AgentTopics.VERIFICATION_REQUESTED, this::onRequested);
    }

    void onRequested(AgentEvent e) {
        Incident i = store.get(e.incidentId()).orElse(null);
        if (i == null || i.result == null) {
            return;
        }
        VerifierAgent.Verification v = verifier.verify(
                analyzer.verifyInput(i.job, i.result, i.lastAttemptTrace, i.observed));
        i.verification = v;
        i.trace.add("verify#" + e.attempt() + "(" + v.status() + ") @" + i.elapsedMs() + "ms");
        v.fails().forEach(f -> i.trace.add("  ↳ 지적: " + f));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", v.status());
        data.put("fails", v.fails());
        data.put("warns", v.warns());
        bus.publish(AgentTopics.VERIFICATION_COMPLETED, new AgentEvent(i.id, e.attempt(), data));
    }
}
