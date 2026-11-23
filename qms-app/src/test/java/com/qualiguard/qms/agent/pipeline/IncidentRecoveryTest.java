package com.qualiguard.qms.agent.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.agent.VerifierAgent;
import com.qualiguard.qms.agent.pipeline.AgentPipelineTest.FakeAnalyzer;

import tools.jackson.databind.json.JsonMapper;

/**
 * 재시작 복구 (로드맵 A2): 사건 기록(checkpoint)으로 되살린 사건이 멈춘 단계부터 이어 가는지.
 * "앱이 멈춤"은 특정 토픽의 이벤트를 보내지 못하고 끝나는 것으로 흉내 낸다.
 */
class IncidentRecoveryTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** stop 이 참인 이벤트는 보내지 못하고 멈춘다 (그 시점에 앱이 꺼졌다고 친다). */
    static class StoppingBus implements EventBus {
        final AgentEventRouter router;
        final BiPredicate<String, AgentEvent> stop;

        StoppingBus(AgentEventRouter router, BiPredicate<String, AgentEvent> stop) {
            this.router = router;
            this.stop = stop;
        }

        @Override
        public void publish(String topic, AgentEvent event) {
            if (!stop.test(topic, event)) {
                router.route(topic, event);
            }
        }
    }

    record App(FakeAnalyzer analyzer, IncidentStore store, IncidentOrchestrator orchestrator, AgentPipeline pipeline,
            StoppingBus bus) {
    }

    /** 앱 한 번 켜기: 새 분석기·저장소·에이전트 (메모리는 비어 있다). */
    static App start(Set<String> dropTopics) {
        return start((topic, e) -> dropTopics.contains(topic));
    }

    static App start(BiPredicate<String, AgentEvent> stop) {
        var analyzer = new FakeAnalyzer();
        var router = new AgentEventRouter();
        var bus = new StoppingBus(router, stop);
        var store = new IncidentStore(null, AgentMetrics.none(), JSON);
        new AnalysisAgent(analyzer, store, bus, router);
        new VerifierStage(new VerifierAgent(new AgentPipelineTest.NoFacts()), analyzer, store, bus, router);
        new ReportWriterAgent(new ReportWriterAgent.Settings("analysis-text", "http://x", "m", 5), null, null, JSON,
                store, bus, router);
        var orchestrator = new IncidentOrchestrator(analyzer, store, bus, JSON, 1, router);
        var pipeline = new AgentPipeline(new TriageAgent(analyzer, store, bus), store, Duration.ofSeconds(5));
        return new App(analyzer, store, orchestrator, pipeline, bus);
    }

    /** DB 에 남았을 기록 = 사건의 snapshot 을 JSON 으로 저장했다가 읽은 것. */
    static List<Map<String, Object>> checkpointRows(Incident i, long ageMinutes) {
        return List.of(Map.of("incident_id", i.id, "checkpoint_json", JSON.writeValueAsString(i.snapshot()),
                "age_min", ageMinutes));
    }

    static List<String> states(Incident i) {
        return i.timeline.stream().map(m -> String.valueOf(m.get("state"))).toList();
    }

    @Test
    void 분석_결과를_기록한_뒤_멈추면_LLM_을_다시_부르지_않고_이어_간다() {
        App before = start(Set.of(AgentTopics.ANALYSIS_COMPLETED));
        before.analyzer.answers.add(AgentPipelineTest.draft(80, List.of("온도 224.1℃", "불량률 6%")));
        Incident crashed = before.pipeline.startAlarm("a-1", false, null);
        assertEquals(Incident.State.ANALYZING, crashed.state);
        assertNotNull(crashed.result, "분석 결과는 기록됨");

        App after = start(Set.of());   // 다시 켬: 분석기에 준비된 답이 없다 → LLM 을 부르면 실패
        List<Incident> restored = after.store.restoreFrom(checkpointRows(crashed, 3));
        assertEquals(1, restored.size());
        Incident i = restored.get(0);
        assertEquals("HEATER_DEGRADATION", i.result.causeCode());
        assertEquals(1, after.orchestrator.resume(restored, 1440));

        assertEquals(0, after.analyzer.prompts.size(), "LLM 다시 호출 없음");
        assertEquals("NCR_DRAFT", after.analyzer.completedWith.decision());
        assertEquals(Incident.State.AWAITING_APPROVAL, i.state);
        assertEquals(List.of("TRIAGED", "ANALYZING", "ANALYZING", "VERIFYING", "WRITING", "AWAITING_APPROVAL"),
                states(i), "재시작 표시(ANALYZING 유지) 뒤 이어 감");
        assertTrue(after.analyzer.completedPipeline.contains(AgentTopics.RESUMED));
        assertEquals(0, after.store.inFlight().size());
    }

    @Test
    void 분석_중에_멈추면_그_시도를_다시_분석한다() {
        App before = start(Set.of(AgentTopics.ANALYSIS_REQUESTED));
        Incident crashed = before.pipeline.startAlarm("a-2", false, null);
        assertEquals(Incident.State.ANALYZING, crashed.state);
        assertNull(crashed.result);

        App after = start(Set.of());
        after.analyzer.answers.add(AgentPipelineTest.draft(80, List.of("온도 224.1℃", "불량률 6%")));
        List<Incident> restored = after.store.restoreFrom(checkpointRows(crashed, 1));
        after.orchestrator.resume(restored, 1440);
        assertEquals(1, after.analyzer.prompts.size());
        assertEquals("사건 a-2", after.analyzer.prompts.get(0), "기록해 둔 질문으로 다시 분석");
        assertEquals(Incident.State.AWAITING_APPROVAL, restored.get(0).state);
    }

    @Test
    void 되돌림_뒤_재분석_요청_전에_멈춰도_시도_번호와_지적이_이어진다() {
        App before = start((topic, e) -> topic.equals(AgentTopics.ANALYSIS_REQUESTED) && e.attempt() == 2);
        before.analyzer.answers.add(AgentPipelineTest.draft(11, List.of("온도가 낮아짐")));   // 근거 약함 → 되돌림
        Incident crashed = before.pipeline.startAlarm("a-3", false, null);
        assertEquals(Incident.State.ANALYZING, crashed.state);
        assertEquals(2, crashed.attempts);

        App after = start(Set.of());
        after.analyzer.answers.add(AgentPipelineTest.draft(78, List.of("온도 224.1℃", "불량률 6%")));
        List<Incident> restored = after.store.restoreFrom(checkpointRows(crashed, 1));
        after.orchestrator.resume(restored, 1440);
        assertEquals(1, after.analyzer.prompts.size());
        assertTrue(after.analyzer.prompts.get(0).contains("사건 a-3") && after.analyzer.prompts.get(0).length() > "사건 a-3".length(),
                "검증 지적이 붙은 재분석 질문을 그대로 씀");
        assertEquals(2, after.analyzer.completedAttempts);
        assertEquals("PASS", restored.get(0).verification.status());
    }

    @Test
    void 평가_재생_사건과_오래된_사건은_이어_가지_않고_실패로_닫는다() {
        App before = start(Set.of(AgentTopics.ANALYSIS_REQUESTED));
        Incident replay = before.pipeline.startAlarm("a-4", true, "eval-1");
        Incident old = before.pipeline.startAlarm("a-5", false, null);

        App after = start(Set.of());
        List<Map<String, Object>> rows = new ArrayList<>(checkpointRows(replay, 2));
        rows.addAll(checkpointRows(old, 3000));
        List<Incident> restored = after.store.restoreFrom(rows);
        assertEquals(0, after.orchestrator.resume(restored, 1440));
        assertEquals(0, after.analyzer.prompts.size());
        assertTrue(after.analyzer.failedError.contains("재시작"));
        restored.forEach(i -> assertEquals(Incident.State.FAILED, i.state));
        assertEquals(0, after.store.inFlight().size());
    }

    @Test
    void 같은_요청이_두_번_와도_한_번만_처리한다() {
        App app = start(Set.of(AgentTopics.VERIFICATION_REQUESTED));
        app.analyzer.answers.add(AgentPipelineTest.draft(80, List.of("온도 224.1℃", "불량률 6%")));
        Incident i = app.pipeline.startAlarm("a-6", false, null);
        assertEquals(Incident.State.VERIFYING, i.state);
        // 지난 단계의 이벤트가 다시 옴 (Kafka 재전달): 무시돼야 한다
        app.orchestrator.onTriaged(new AgentEvent(i.id, 0, Map.of()));
        app.orchestrator.onAnalysisCompleted(new AgentEvent(i.id, 1, Map.of()));
        assertEquals(Incident.State.VERIFYING, i.state);
        assertEquals(1, app.analyzer.prompts.size());
    }

    @Test
    void 기록이_없거나_깨진_사건은_되살리지_않는다() {
        App app = start(Set.of());
        var rows = new ArrayList<Map<String, Object>>();
        var noCheckpoint = new java.util.HashMap<String, Object>();
        noCheckpoint.put("incident_id", "old-1");
        noCheckpoint.put("checkpoint_json", null);
        noCheckpoint.put("age_min", 5);
        rows.add(noCheckpoint);
        rows.add(Map.of("incident_id", "bad-1", "checkpoint_json", "{\"state\":\"NOPE\"}", "age_min", 5));
        assertEquals(0, app.store.restoreFrom(rows).size());
    }
}
