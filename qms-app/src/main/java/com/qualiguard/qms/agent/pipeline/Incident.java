package com.qualiguard.qms.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.ToolObservations;
import com.qualiguard.qms.agent.VerifierAgent;

/**
 * 진행 중인 사건 1건의 작업 공간. 오케스트레이터가 상태를 바꾸고, 각 에이전트는 자기 단계의 결과를 여기에 쓴다.
 * (한 JVM 안의 메모리에 둔다. 앱이 재시작되면 진행 중이던 사건은 사라지고, 경보는 다시 분석 대상으로 남는다)
 */
public final class Incident {

    public enum State { TRIAGED, ANALYZING, VERIFYING, WRITING, AWAITING_APPROVAL, CLOSED, FAILED }

    final String id;
    final IncidentAnalyzer.Job job;
    final TriageAgent.Triage triage;
    final long startNanos = System.nanoTime();
    final CompletableFuture<IncidentAnalyzer.Outcome> done = new CompletableFuture<>();
    /** 모든 시도의 도구 호출·검증 기록 (사람이 나중에 볼 판단 과정) */
    final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> timeline = Collections.synchronizedList(new ArrayList<>());

    volatile State state = State.TRIAGED;
    volatile String basePrompt;
    volatile String prompt;
    volatile int attempts;
    volatile AnalysisResult result;
    volatile List<String> lastAttemptTrace = List.of();
    /** 이 사건의 모든 시도에서 도구가 돌려준 수치 (검증 에이전트가 근거 수치를 대조할 때 쓴다) */
    final ToolObservations observed = new ToolObservations();
    volatile String error;
    volatile VerifierAgent.Verification verification;
    volatile AnalysisResult written;
    volatile Map<String, Object> report;

    Incident(String id, IncidentAnalyzer.Job job, TriageAgent.Triage triage) {
        this.id = id;
        this.job = job;
        this.triage = triage;
    }

    public String id() {
        return id;
    }

    public State state() {
        return state;
    }

    public CompletableFuture<IncidentAnalyzer.Outcome> done() {
        return done;
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 방금 끝난 단계(마지막에서 두 번째 기록)의 상태와 머문 시간. 기록이 2개 미만이면 null. */
    Object[] lastStage() {
        synchronized (timeline) {
            int n = timeline.size();
            if (n < 2) {
                return null;
            }
            Map<String, Object> prev = timeline.get(n - 2);
            Map<String, Object> last = timeline.get(n - 1);
            return new Object[] {prev.get("state"), (Long) last.get("atMs") - (Long) prev.get("atMs")};
        }
    }

    void mark(String topic, State to, String detail) {
        state = to;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atMs", elapsedMs());
        m.put("topic", topic);
        m.put("state", to.name());
        if (detail != null) {
            m.put("detail", detail);
        }
        timeline.add(m);
    }
}
