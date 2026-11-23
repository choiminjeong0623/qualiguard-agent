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
 * 진행 중에는 메모리에 두고, 단계가 바뀔 때마다 {@link #snapshot()} 을 DB(agent_incident.checkpoint_json)에 남긴다.
 * 앱이 재시작되면 그 기록으로 사건을 되살려 멈춘 단계부터 이어 간다 (로드맵 A2, {@link IncidentStore#restore}).
 */
public final class Incident {

    public enum State { TRIAGED, ANALYZING, VERIFYING, WRITING, AWAITING_APPROVAL, CLOSED, FAILED }

    final String id;
    final IncidentAnalyzer.Job job;
    final TriageAgent.Triage triage;
    final long startNanos;
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
    /** 검증을 마친 분석 시도 번호 (같은 검증 요청을 두 번 받아도 다시 검증하지 않게) */
    volatile int verifiedAttempt;
    /** 재시작 뒤 DB 기록에서 되살린 사건이면, 마지막 기록 뒤 지난 분 (아니면 -1) */
    volatile long restoredAgeMinutes = -1;

    Incident(String id, IncidentAnalyzer.Job job, TriageAgent.Triage triage) {
        this(id, job, triage, 0);
    }

    /** @param elapsedMs 이미 지난 시간 (되살린 사건은 단계 기록의 시각이 이어지도록) */
    private Incident(String id, IncidentAnalyzer.Job job, TriageAgent.Triage triage, long elapsedMs) {
        this.id = id;
        this.job = job;
        this.triage = triage;
        this.startNanos = System.nanoTime() - elapsedMs * 1_000_000;
    }

    /** 재시작 뒤 이어 가는 데 필요한 것만 담은 기록 (JSON 으로 저장). */
    Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("job", job);
        m.put("triage", triage);
        m.put("state", state.name());
        m.put("elapsedMs", elapsedMs());
        m.put("attempts", attempts);
        m.put("basePrompt", basePrompt);
        m.put("prompt", prompt);
        m.put("result", result);
        m.put("error", error);
        m.put("verification", verification);
        m.put("verifiedAttempt", verifiedAttempt);
        m.put("written", written);
        m.put("report", report);
        m.put("lastAttemptTrace", lastAttemptTrace);
        m.put("observed", observed.values().stream().map(java.math.BigDecimal::toPlainString).toList());
        synchronized (trace) {
            m.put("trace", new ArrayList<>(trace));
        }
        synchronized (timeline) {
            m.put("timeline", new ArrayList<>(timeline));
        }
        return m;
    }

    /** {@link #snapshot()} 으로 남긴 기록에서 사건을 되살린다. */
    @SuppressWarnings("unchecked")
    static Incident fromSnapshot(String id, Map<String, Object> m, tools.jackson.databind.ObjectMapper om) {
        var job = om.convertValue(m.get("job"), IncidentAnalyzer.Job.class);
        var triage = om.convertValue(m.get("triage"), TriageAgent.Triage.class);
        long elapsed = m.get("elapsedMs") instanceof Number n ? n.longValue() : 0;
        Incident i = new Incident(id, job, triage, elapsed);
        i.state = State.valueOf(String.valueOf(m.get("state")));
        i.attempts = m.get("attempts") instanceof Number n ? n.intValue() : 0;
        i.basePrompt = (String) m.get("basePrompt");
        i.prompt = (String) m.get("prompt");
        i.result = m.get("result") == null ? null : om.convertValue(m.get("result"), AnalysisResult.class);
        i.error = (String) m.get("error");
        i.verification = m.get("verification") == null ? null
                : om.convertValue(m.get("verification"), VerifierAgent.Verification.class);
        i.verifiedAttempt = m.get("verifiedAttempt") instanceof Number n ? n.intValue() : 0;
        i.written = m.get("written") == null ? null : om.convertValue(m.get("written"), AnalysisResult.class);
        i.report = (Map<String, Object>) m.get("report");
        if (m.get("lastAttemptTrace") instanceof List<?> l) {
            i.lastAttemptTrace = l.stream().map(String::valueOf).toList();
        }
        if (m.get("observed") instanceof List<?> l) {
            l.forEach(v -> i.observed.addValue(String.valueOf(v)));
        }
        if (m.get("trace") instanceof List<?> l) {
            l.forEach(v -> i.trace.add(String.valueOf(v)));
        }
        if (m.get("timeline") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) o);
                if (step.get("atMs") instanceof Number n) {
                    step.put("atMs", n.longValue());   // lastStage() 가 Long 으로 읽는다
                }
                i.timeline.add(step);
            }
        }
        return i;
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
