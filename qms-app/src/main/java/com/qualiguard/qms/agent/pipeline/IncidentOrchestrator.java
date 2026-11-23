package com.qualiguard.qms.agent.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.VerifierAgent;

import tools.jackson.databind.ObjectMapper;

/**
 * 오케스트레이터 (ADR-016): 사건 상태를 관리하고 다음 에이전트에게 일을 넘긴다. 스스로 분석하지 않는다.
 *
 * <pre>
 * TRIAGED → ANALYZING → VERIFYING ─PASS──────────────▶ WRITING(초안일 때) → AWAITING_APPROVAL (사람 승인 대기)
 *                ▲            └─WEAK, 재분석 남음─┐                └ 초안이 아니면 → CLOSED
 *                └──────────── (지적을 붙여) ─────┘    분석 실패 → FAILED (경보는 다시 분석 대상으로)
 * </pre>
 * 사람 승인은 이 파이프라인 밖(QMS 화면)에서 항상 마지막에 한다.
 * actionStage 가 켜져 있으면 결과를 저장한 뒤 조치 에이전트에게 agent.action.requested 를 보낸다 (평가 재생은 제외).
 */
public class IncidentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IncidentOrchestrator.class);

    private final IncidentAnalyzer analyzer;
    private final IncidentStore store;
    private final EventBus bus;
    private final ObjectMapper objectMapper;
    private final int verifyMaxRetries;
    private final boolean actionStage;

    public IncidentOrchestrator(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, ObjectMapper objectMapper,
            int verifyMaxRetries, AgentEventRouter router) {
        this(analyzer, store, bus, objectMapper, verifyMaxRetries, router, false);
    }

    public IncidentOrchestrator(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, ObjectMapper objectMapper,
            int verifyMaxRetries, AgentEventRouter router, boolean actionStage) {
        this.actionStage = actionStage;
        this.analyzer = analyzer;
        this.store = store;
        this.bus = bus;
        this.objectMapper = objectMapper;
        this.verifyMaxRetries = verifyMaxRetries;
        router.register(AgentTopics.TRIAGED, this::onTriaged);
        router.register(AgentTopics.ANALYSIS_COMPLETED, this::onAnalysisCompleted);
        router.register(AgentTopics.VERIFICATION_COMPLETED, this::onVerificationCompleted);
        router.register(AgentTopics.REPORT_COMPLETED, this::onReportCompleted);
    }

    void onTriaged(AgentEvent e) {
        withIncident(e, AgentTopics.TRIAGED, i -> {
            if (i.state != Incident.State.TRIAGED) {
                return;   // 같은 이벤트가 다시 옴 (이미 분석 단계로 넘김)
            }
            i.basePrompt = analyzer.userPrompt(i.job);
            i.prompt = i.basePrompt;
            i.attempts = 1;
            store.transition(i, AgentTopics.TRIAGED, Incident.State.ANALYZING,
                    i.triage.severity() + " · " + i.triage.type());
            bus.publish(AgentTopics.ANALYSIS_REQUESTED, new AgentEvent(i.id, i.attempts, Map.of()));
        });
    }

    void onAnalysisCompleted(AgentEvent e) {
        withIncident(e, AgentTopics.ANALYSIS_COMPLETED, i -> {
            if (i.state != Incident.State.ANALYZING || e.attempt() != i.attempts) {
                return;   // 지난 시도의 완료가 다시 옴
            }
            if (i.error != null || i.result == null) {
                finishFailed(i, AgentTopics.ANALYSIS_COMPLETED, i.error == null ? "분석 결과 없음" : i.error);
                return;
            }
            store.transition(i, AgentTopics.ANALYSIS_COMPLETED, Incident.State.VERIFYING,
                    "시도 " + e.attempt() + ": " + i.result.decision() + " / " + i.result.causeCode());
            bus.publish(AgentTopics.VERIFICATION_REQUESTED, new AgentEvent(i.id, e.attempt(), Map.of()));
        });
    }

    void onVerificationCompleted(AgentEvent e) {
        withIncident(e, AgentTopics.VERIFICATION_COMPLETED, i -> {
            if (i.state != Incident.State.VERIFYING || e.attempt() != i.attempts) {
                return;
            }
            VerifierAgent.Verification v = i.verification;
            if (v != null && !v.passed() && i.attempts <= verifyMaxRetries) {
                // 근거가 약함: 지적을 붙여 분석 에이전트로 되돌린다 (횟수 제한)
                i.trace.add("  ↳ 분석 에이전트로 되돌림 (" + i.attempts + "/" + verifyMaxRetries + ")");
                i.prompt = analyzer.reworkPrompt(i.basePrompt, i.result, v);
                i.attempts++;
                i.result = null;
                store.transition(i, AgentTopics.VERIFICATION_COMPLETED, Incident.State.ANALYZING,
                        "되돌림: " + String.join(" / ", v.fails()));
                bus.publish(AgentTopics.ANALYSIS_REQUESTED, new AgentEvent(i.id, i.attempts, Map.of()));
                return;
            }
            String status = v == null ? "-" : v.status();
            if ("NCR_DRAFT".equals(upper(i.result.decision()))) {
                store.transition(i, AgentTopics.VERIFICATION_COMPLETED, Incident.State.WRITING, "검증 " + status);
                bus.publish(AgentTopics.REPORT_REQUESTED, new AgentEvent(i.id, i.attempts, Map.of()));
            } else {
                finish(i, AgentTopics.VERIFICATION_COMPLETED, "검증 " + status);
            }
        });
    }

    void onReportCompleted(AgentEvent e) {
        withIncident(e, AgentTopics.REPORT_COMPLETED, i -> {
            if (i.state != Incident.State.WRITING) {
                return;
            }
            finish(i, AgentTopics.REPORT_COMPLETED, "작성 " + (i.report == null ? "-" : i.report.get("mode")));
        });
    }

    /**
     * 재시작 복구 (로드맵 A2): 되살린 사건을 멈춘 단계부터 이어 간다. 그 단계의 요청 이벤트를 다시 보내고,
     * 이미 끝난 일이면 각 에이전트가 다시 하지 않고 완료만 알린다 (중복 요청은 상태·시도 번호로 걸러진다).
     * 평가 재생 사건(run_tag)과 너무 오래된 사건은 이어 가지 않고 실패로 닫는다 (경보는 다시 분석 대상으로).
     *
     * @param maxAgeMinutes 마지막 기록 뒤 이만큼 지난 사건은 이어 가지 않는다
     * @return 이어 간 사건 수
     */
    public int resume(List<Incident> restored, long maxAgeMinutes) {
        int resumed = 0;
        for (Incident i : restored) {
            try {
                if (i.job.tag() != null) {
                    finishFailed(i, AgentTopics.RESUMED, "앱 재시작으로 중단 (평가 재생은 이어 하지 않음)");
                    continue;
                }
                if (i.restoredAgeMinutes > maxAgeMinutes) {
                    finishFailed(i, AgentTopics.RESUMED, "앱 재시작으로 중단 (마지막 기록 뒤 " + i.restoredAgeMinutes
                            + "분 지나 이어 하지 않음)");
                    continue;
                }
                String next = switch (i.state) {
                    case TRIAGED -> AgentTopics.TRIAGED;
                    case ANALYZING -> AgentTopics.ANALYSIS_REQUESTED;
                    case VERIFYING -> AgentTopics.VERIFICATION_REQUESTED;
                    case WRITING -> AgentTopics.REPORT_REQUESTED;
                    default -> null;
                };
                if (next == null) {
                    continue;
                }
                store.note(i, AgentTopics.RESUMED, "재시작 뒤 " + i.state + " 단계부터 이어 감");
                log.info("[재시작 복구] 사건 {} ({} {}) — {} 단계부터 이어 감", i.id, i.job.equipmentId(), i.job.trigger(),
                        i.state);
                resumed++;
                bus.publish(next, new AgentEvent(i.id, next.equals(AgentTopics.TRIAGED) ? 0 : i.attempts, Map.of()));
            } catch (RuntimeException ex) {
                log.error("[재시작 복구] 사건 {} 이어 가기 실패: {}", i.id, ex.toString(), ex);
                if (!i.done.isDone()) {
                    try {
                        finishFailed(i, AgentTopics.RESUMED, AnalysisAgent.causeChain(ex));
                    } catch (RuntimeException ex2) {
                        store.close(i, null, null);
                    }
                }
            }
        }
        return resumed;
    }

    /** 결과 저장: 초안이면 사람 승인 대기, 아니면 종결. */
    private void finish(Incident i, String topic, String detail) {
        boolean draft = "NCR_DRAFT".equals(upper(i.result.decision()));
        Incident.State end = draft ? Incident.State.AWAITING_APPROVAL : Incident.State.CLOSED;
        i.mark(topic, end, detail);   // 저장할 단계 기록에 마지막 상태까지 넣는다
        IncidentAnalyzer.Outcome o = analyzer.complete(i.id, i.job, i.result, List.copyOf(i.trace), i.elapsedMs(),
                i.attempts, i.verification, i.written, pipelineJson(i));
        store.transitionLogged(i, topic, end, detail + (o.ncrNo() == null ? "" : " → " + o.ncrNo()));
        store.recordResult(i, upper(i.result.decision()));
        store.close(i, o.analysisId(), o.ncrNo());
        if (actionStage && i.job.tag() == null && o.analysisId() != null) {
            requestAction(i, o);
        }
        i.done.complete(o.withPipeline(i.id, i.triage.toMap(), i.report, List.copyOf(i.timeline)));
    }

    /** 조치 에이전트가 판단에 쓸 요약만 싣는다 (사건은 이미 닫혀 메모리에 없다). */
    private void requestAction(Incident i, IncidentAnalyzer.Outcome o) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analysisId", o.analysisId());
        data.put("parentAnalysisId", o.parentAnalysisId());
        data.put("equipmentId", o.equipmentId());
        data.put("decision", o.decision());
        data.put("causeCode", o.causeCode());
        data.put("confidence", o.confidence());
        data.put("severity", i.triage.severity());
        data.put("verified", i.verification != null && i.verification.passed());
        try {
            bus.publish(AgentTopics.ACTION_REQUESTED, new AgentEvent(i.id, i.attempts, data));
        } catch (RuntimeException ex) {
            // 분석 결과는 이미 저장됐다. 조치 요청 실패는 사건을 실패로 만들지 않는다
            log.warn("[오케스트레이터] 조치 요청 실패 (사건 {}): {}", i.id, ex.toString());
        }
    }

    private void finishFailed(Incident i, String topic, String error) {
        i.mark(topic, Incident.State.FAILED, shorten(error));
        IncidentAnalyzer.Outcome o = analyzer.fail(i.id, i.job, List.copyOf(i.trace), i.elapsedMs(), i.attempts, error,
                pipelineJson(i));
        store.transitionLogged(i, topic, Incident.State.FAILED, shorten(error));
        store.recordResult(i, "FAILED");
        store.close(i, o.analysisId(), null);
        i.done.complete(o.withPipeline(i.id, i.triage.toMap(), i.report, List.copyOf(i.timeline)));
    }

    private void withIncident(AgentEvent e, String topic, java.util.function.Consumer<Incident> action) {
        Incident i = store.get(e.incidentId()).orElse(null);
        if (i == null) {
            log.debug("[오케스트레이터] 모르는 사건 무시 ({}): {}", topic, e.incidentId());
            return;
        }
        try {
            action.accept(i);
        } catch (RuntimeException ex) {
            log.error("[오케스트레이터] {} 처리 중 오류 — 사건 {} 실패 처리: {}", topic, i.id, ex.toString(), ex);
            if (!i.done.isDone()) {
                try {
                    finishFailed(i, topic, AnalysisAgent.causeChain(ex));
                } catch (RuntimeException ex2) {
                    i.done.completeExceptionally(ex2);
                    store.close(i, null, null);
                }
            }
        }
    }

    private String pipelineJson(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incidentId", i.id);
        m.put("triage", i.triage.toMap());
        m.put("report", i.report);
        m.put("timeline", new ArrayList<>(i.timeline));
        return objectMapper.writeValueAsString(m);
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static String shorten(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
