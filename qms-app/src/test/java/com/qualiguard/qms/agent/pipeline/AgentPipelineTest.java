package com.qualiguard.qms.agent.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.qualiguard.qms.agent.AgentProperties;
import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.VerifierAgent;

import tools.jackson.databind.json.JsonMapper;

/**
 * 역할 분리형 파이프라인의 상태 기계 (DB·LLM·Kafka 없이, DirectEventBus):
 * 분류 → 분석 → 검증(되돌림 포함) → 작성 → 승인 대기 / 종결 / 실패.
 */
class AgentPipelineTest {

    static final List<String> FULL_TRACE = List.of("getSpcSummary(IMM-01) @1ms", "getSensorTrend(IMM-01) @2ms",
            "getDefectSummary(IMM-01) @3ms");

    /** LLM 대신 미리 정한 결과를 차례로 돌려주고, 저장 대신 받은 값을 기억하는 분석기. */
    static class FakeAnalyzer extends IncidentAnalyzer {
        final Deque<Object> answers = new ArrayDeque<>();   // AnalysisResult 또는 RuntimeException
        final List<String> prompts = new ArrayList<>();
        AnalysisResult completedWith;
        AnalysisResult writtenWith;
        int completedAttempts;
        String completedPipeline;
        String failedError;

        @SuppressWarnings("unchecked")
        FakeAnalyzer() {
            super(null, JsonMapper.builder().build(), null,
                    new AgentProperties(false, 5000, 100, 30, 60, 40, 3, 1),
                    org.mockito.Mockito.mock(ObjectProvider.class), "test-model");
        }

        @Override
        public synchronized Job prepareAlarm(String seed, boolean replay, String runTag) {
            if ("missing".equals(seed)) {
                throw new IllegalArgumentException("경보 없음");
            }
            List<Map<String, Object>> alarms = List.of(Map.of("rule_code", "XBAR", "sensor_key", "barrelTemp"));
            return new Job("ALARM", "IMM-01", seed, "t", alarms, List.of(seed), true, Map.of(), "t", 300L, "t", null,
                    runTag, null);
        }

        @Override
        public String userPrompt(Job job) {
            return "사건 " + job.seedAnomalyId();
        }

        @Override
        public VerifierAgent.Input verifyInput(Job job, AnalysisResult result, List<String> attemptTrace) {
            return new VerifierAgent.Input(job.equipmentId(), job.asOf(), List.of("XBAR"), result, attemptTrace);
        }

        @Override
        public AnalysisResult analyzeOnce(String prompt, String asOf, long t0, List<String> trace) {
            prompts.add(prompt);
            Object a = answers.pop();
            if (a instanceof RuntimeException ex) {
                trace.add("getSpcSummary(IMM-01) @1ms");
                throw ex;
            }
            AnalysisResult r = (AnalysisResult) a;
            trace.addAll("NCR_DRAFT".equals(r.decision()) && r.confidence() == 11
                    ? List.of("getSensorTrend(IMM-01) @2ms")   // 불량을 보지 않은 시도
                    : FULL_TRACE);
            return r;
        }

        @Override
        public Outcome complete(String incidentId, Job job, AnalysisResult result, List<String> trace, long ms,
                int attempts, VerifierAgent.Verification verified, AnalysisResult written, String pipelineJson) {
            completedWith = result;
            writtenWith = written;
            completedAttempts = attempts;
            completedPipeline = pipelineJson;
            return new Outcome(1, job.equipmentId(), job.trigger(), job.asOfShot(), null, result.decision(),
                    result.causeCode(), result.confidence(), "NCR_DRAFT".equals(result.decision()) ? "NCR-T-1" : null,
                    false, job.anomalyIds(), ms, trace, result, null, attempts, verified, null, null, null, null);
        }

        @Override
        public Outcome fail(String incidentId, Job job, List<String> trace, long ms, int attempts, String error,
                String pipelineJson) {
            failedError = error;
            return new Outcome(2, job.equipmentId(), job.trigger(), job.asOfShot(), null, "FAILED", null, null, null,
                    false, job.anomalyIds(), ms, trace, null, error, attempts, null, null, null, null, null);
        }
    }

    static class NoFacts implements VerifierAgent.Facts {
        @Override
        public Set<String> approvedCases(List<String> ncrNos, String asOf) {
            return Set.of();
        }

        @Override
        public double[] defectRates(String equipmentId, String asOf, int recent, int baseline) {
            return null;
        }
    }

    static AnalysisResult draft(int conf, List<String> evidence) {
        return new AnalysisResult("NCR_DRAFT", "HEATER_DEGRADATION", conf, "분석 제목", "현상", "추정", evidence, List.of(),
                "히터 점검", "요약", List.of());
    }

    static AnalysisResult observe() {
        return new AnalysisResult("OBSERVE", "UNKNOWN", 40, "제목", "현상", "추정", List.of(), List.of(), "", "요약",
                List.of("다음 20 shot 평균"));
    }

    record Wired(FakeAnalyzer analyzer, AgentPipeline pipeline, IncidentStore store) {
    }

    static Wired wire(String writerMode, ReportWriterAgent.LocalModel local) {
        var analyzer = new FakeAnalyzer();
        var json = JsonMapper.builder().build();
        var router = new AgentEventRouter();
        var bus = new DirectEventBus(router);
        var store = new IncidentStore(null);
        new AnalysisAgent(analyzer, store, bus, router);
        new VerifierStage(new VerifierAgent(new NoFacts()), analyzer, store, bus, router);
        new ReportWriterAgent(new ReportWriterAgent.Settings(writerMode, "http://x", "m", 5), local, null, json, store,
                bus, router);
        new IncidentOrchestrator(analyzer, store, bus, json, 1, router);
        return new Wired(analyzer, new AgentPipeline(new TriageAgent(analyzer, store, bus), store, Duration.ofSeconds(5)),
                store);
    }

    @Test
    void 근거가_충분한_초안은_한번에_승인_대기까지_간다() {
        Wired w = wire("analysis-text", null);
        w.analyzer.answers.add(draft(80, List.of("온도 224.1℃", "불량률 6%", "전류 +1.8σ")));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals("NCR_DRAFT", o.decision());
        assertEquals(1, w.analyzer.completedAttempts);
        assertEquals("PASS", o.verification().status());
        assertNull(w.analyzer.writtenWith, "analysis-text: 분석 문장 그대로");
        assertEquals("MEDIUM", o.triage().get("severity"));
        assertEquals(List.of("TRIAGED", "ANALYZING", "VERIFYING", "WRITING", "AWAITING_APPROVAL"),
                o.timeline().stream().map(m -> m.get("state")).toList());
        assertEquals(0, w.store.inFlight().size(), "끝난 사건은 진행 목록에서 빠진다");
        assertTrue(w.analyzer.completedPipeline.contains("AWAITING_APPROVAL"));
    }

    @Test
    void 근거가_약하면_지적을_붙여_한번_되돌리고_다시_검증한다() {
        Wired w = wire("analysis-text", null);
        w.analyzer.answers.add(draft(11, List.of("온도가 낮아짐")));             // 불량 미조회 + 수치 근거 부족
        w.analyzer.answers.add(draft(78, List.of("온도 224.1℃", "불량률 6%")));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals(2, w.analyzer.completedAttempts);
        assertEquals("PASS", o.verification().status());
        assertEquals(2, w.analyzer.prompts.size());
        assertTrue(w.analyzer.prompts.get(1).contains("[검증 에이전트 지적]")
                && w.analyzer.prompts.get(1).contains("getDefectSummary"), "되돌릴 때 지적을 넘긴다");
        assertEquals(List.of("TRIAGED", "ANALYZING", "VERIFYING", "ANALYZING", "VERIFYING", "WRITING",
                "AWAITING_APPROVAL"), o.timeline().stream().map(m -> m.get("state")).toList());
    }

    @Test
    void 되돌림_횟수를_다_쓰면_약한_결과를_그대로_기록한다() {
        Wired w = wire("analysis-text", null);
        w.analyzer.answers.add(draft(11, List.of("온도가 낮아짐")));
        w.analyzer.answers.add(draft(11, List.of("온도가 낮아짐")));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals(2, w.analyzer.prompts.size(), "verify-max-retries=1 → 분석은 최대 2번");
        assertEquals("WEAK", o.verification().status());
        assertTrue(o.toolTrace().stream().anyMatch(t -> t.startsWith("verify#2(WEAK)")));
    }

    @Test
    void 관찰_판단은_작성_없이_종결된다() {
        Wired w = wire("local", (p, t) -> {
            throw new AssertionError("관찰에는 작성 에이전트를 부르지 않는다");
        });
        w.analyzer.answers.add(observe());
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals("OBSERVE", o.decision());
        assertEquals("CLOSED", o.timeline().get(o.timeline().size() - 1).get("state"));
        assertNull(o.report());
    }

    @Test
    void 로컬_모델이_실패하면_클라우드가_아니라_근거_표_초안으로_대신한다() {
        Wired w = wire("local", (p, t) -> {
            throw new IllegalStateException("Ollama 연결 거부");
        });
        w.analyzer.answers.add(draft(80, List.of("온도 224.1℃", "불량률 6%", "전류 +1.8σ")));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals("TEMPLATE_FALLBACK", o.report().get("mode"));
        assertEquals(0, o.report().get("cloudCalls"));
        assertTrue(w.analyzer.writtenWith.title().contains("사람 작성 필요"));
        assertEquals("HEATER_DEGRADATION", w.analyzer.writtenWith.causeCode(), "작성 에이전트는 판단을 바꾸지 않는다");
    }

    @Test
    void 로컬_모델_문장은_초안에_쓰되_판단은_그대로다() {
        Wired w = wire("local", (p, t) -> {
            assertTrue(p.contains("HEATER_DEGRADATION"));
            return "{\"title\":\"실린더 온도 저하로 미성형 증가\",\"phenomenon\":\"온도 224℃\",\"suspectedCause\":\"히터 열화\","
                    + "\"recommendedAction\":\"히터 밴드 점검\"}";
        });
        w.analyzer.answers.add(draft(80, List.of("온도 224.1℃", "불량률 6%", "전류 +1.8σ")));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals("LOCAL", o.report().get("mode"));
        assertEquals("실린더 온도 저하로 미성형 증가", w.analyzer.writtenWith.title());
        assertEquals(80, w.analyzer.writtenWith.confidence());
    }

    @Test
    void 분석이_실패하면_사건을_실패로_닫는다() {
        Wired w = wire("analysis-text", null);
        w.analyzer.answers.add(new IllegalStateException("LLM 시간 초과"));
        var o = w.pipeline.analyzeAndWait("a-1", true, "t1");
        assertEquals("FAILED", o.decision());
        assertTrue(w.analyzer.failedError.contains("LLM 시간 초과"));
        assertEquals(0, w.store.inFlight().size());
    }

    @Test
    void 잘못된_요청은_사건을_열지_않고_바로_돌려준다() {
        Wired w = wire("analysis-text", null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> w.pipeline.analyzeAndWait("missing", true, "t1"));
        assertEquals(0, w.store.inFlight().size());
    }

    @Test
    void 분류_규칙() {
        var p = TriageAgent.classify("ALARM", List.of(Map.of("rule_code", "P_CHART", "sensor_key", "inspection")));
        assertEquals("HIGH", p.severity());
        assertEquals("QUALITY_IMPACT", p.type());
        var spike = TriageAgent.classify("ALARM", List.of(Map.of("rule_code", "INDIVIDUAL", "sensor_key", "cushion")));
        assertEquals("LOW", spike.severity());
        var multi = TriageAgent.classify("ALARM", List.of(Map.of("rule_code", "INDIVIDUAL", "sensor_key", "cushion"),
                Map.of("rule_code", "INDIVIDUAL", "sensor_key", "injectionPressure")));
        assertEquals("MEDIUM", multi.severity(), "여러 센서 동시 경보는 한 단계 올린다");
    }
}
