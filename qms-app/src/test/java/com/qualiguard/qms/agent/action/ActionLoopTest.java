package com.qualiguard.qms.agent.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.agent.pipeline.AgentEvent;
import com.qualiguard.qms.agent.pipeline.AgentEventRouter;
import com.qualiguard.qms.agent.pipeline.AgentPipeline;
import com.qualiguard.qms.agent.pipeline.Incident;

/** 조치 에이전트와 효과 검증 루프를 DB 없이 시험한다 (ActionStore 를 메모리로 흉내). */
class ActionLoopTest {

    /** 메모리 조치 기록. */
    static class MemoryStore extends ActionStore {
        final Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
        EffectEvaluator.Window window;
        int autoCount;

        MemoryStore() {
            super(null);
        }

        @Override
        public int insert(int analysisId, String equipmentId, String causeCode, ActionCatalog.Action a,
                ActionPolicy.Decision d, int attempt, Integer parentActionId) {
            int id = rows.size() + 1;
            Map<String, Object> r = new HashMap<>();
            r.put("action_id", id);
            r.put("analysis_id", analysisId);
            r.put("equipment_id", equipmentId);
            r.put("cause_code", causeCode);
            r.put("action_code", a.code());
            r.put("mode", d.mode().name());
            r.put("status", d.mode() == ActionPolicy.Mode.AUTO ? "EXECUTED" : "WAITING_APPROVAL");
            r.put("attempt", attempt);
            r.put("parent_action_id", parentActionId);
            rows.put(id, r);
            return id;
        }

        @Override
        public void markExecuted(int actionId, String decidedBy) {
            rows.get(actionId).put("status", "EXECUTED");
            rows.get(actionId).put("base_shot", 500L);
        }

        @Override
        public int autoCountLast24h(String equipmentId) {
            return autoCount;
        }

        @Override
        public Optional<Map<String, Object>> retriedFrom(Integer parentAnalysisId) {
            return rows.values().stream()
                    .filter(r -> r.get("analysis_id").equals(parentAnalysisId) && "NOT_RESOLVED".equals(r.get("status")))
                    .findFirst();
        }

        @Override
        public List<Map<String, Object>> dueForCheck(int checkShots) {
            return rows.values().stream().filter(r -> "EXECUTED".equals(r.get("status"))).toList();
        }

        @Override
        public EffectEvaluator.Window window(String equipmentId, String sensorKey, long baseShot, int before, int after) {
            return window;
        }

        @Override
        public void markChecked(int actionId, String status, String detail) {
            rows.get(actionId).put("status", status);
            rows.get(actionId).put("effect_detail", detail);
        }
    }

    /** 재분석 요청만 기록하는 파이프라인. */
    static class RecordingPipeline extends AgentPipeline {
        final List<String> retries = new ArrayList<>();

        RecordingPipeline() {
            super(null, null, null);
        }

        @Override
        public Incident startRetry(int parentAnalysisId, String excludedCause) {
            retries.add(parentAnalysisId + ":" + excludedCause);
            return null;
        }

        @Override
        public int inFlight() {
            return 0;
        }
    }

    final MemoryStore store = new MemoryStore();
    final RecordingPipeline pipeline = new RecordingPipeline();
    final ActionAgent agent = new ActionAgent(store, new AgentEventRouter(), 3);
    final EffectCheckScheduler checker = new EffectCheckScheduler(store, pipeline, 40, 2);

    static AgentEvent analysisDone(int analysisId, Integer parent, String decision, String cause, int conf,
            String severity) {
        Map<String, Object> d = new HashMap<>();
        d.put("analysisId", analysisId);
        d.put("parentAnalysisId", parent);
        d.put("equipmentId", "IMM-01");
        d.put("decision", decision);
        d.put("causeCode", cause);
        d.put("confidence", conf);
        d.put("severity", severity);
        d.put("verified", true);
        return new AgentEvent("inc-" + analysisId, 1, d);
    }

    @Test
    void 저위험_조치는_자동_실행되고_효과가_있으면_해소로_끝난다() {
        agent.onRequested(analysisDone(10, null, "SENSOR_CHECK", "SENSOR_FAULT", 70, "LOW"));
        assertThat(store.rows.get(1)).containsEntry("mode", "AUTO").containsEntry("status", "EXECUTED");

        store.window = new EffectEvaluator.Window(null, null, null, 0, 40);
        checker.poll();
        assertThat(store.rows.get(1)).containsEntry("status", "RESOLVED");
        assertThat(pipeline.retries).isEmpty();
    }

    @Test
    void 고위험_조치는_승인_대기로_남고_효과_검증_대상이_아니다() {
        agent.onRequested(analysisDone(11, null, "NCR_DRAFT", "CHECK_RING_WEAR", 95, "HIGH"));
        assertThat(store.rows.get(1)).containsEntry("mode", "APPROVAL").containsEntry("status", "WAITING_APPROVAL");
        checker.poll();
        assertThat(store.rows.get(1)).containsEntry("status", "WAITING_APPROVAL");
    }

    @Test
    void 관찰_판단은_조치를_만들지_않는다() {
        agent.onRequested(analysisDone(12, null, "OBSERVE", "UNKNOWN", 40, "LOW"));
        assertThat(store.rows).isEmpty();
    }

    @Test
    void 효과가_없으면_그_원인을_빼고_재분석하고_두번째도_실패하면_사람에게_넘긴다() {
        // 1차: 히터 열화로 보고 자동 보정 (MEDIUM, 확신도 85)
        agent.onRequested(analysisDone(20, null, "NCR_DRAFT", "HEATER_DEGRADATION", 85, "MEDIUM"));
        assertThat(store.rows.get(1)).containsEntry("status", "EXECUTED");

        // 조치 뒤에도 경보 → 효과 없음 → 히터 열화를 뺀 재분석 요청
        store.window = new EffectEvaluator.Window(220.0, 221.0, 230.0, 3, 40);
        checker.poll();
        assertThat(store.rows.get(1)).containsEntry("status", "NOT_RESOLVED");
        assertThat(pipeline.retries).containsExactly("20:HEATER_DEGRADATION");

        // 재분석(부모 분석 20)이 냉각 문제로 판단 → 2번째 조치로 이어 센다
        agent.onRequested(analysisDone(21, 20, "NCR_DRAFT", "COOLING_ISSUE", 85, "MEDIUM"));
        assertThat(store.rows.get(2)).containsEntry("attempt", 2).containsEntry("parent_action_id", 1);

        // 2번째도 효과 없음 → 횟수(2) 소진 → 에스컬레이션, 더 이상 재분석하지 않는다
        checker.poll();
        assertThat(store.rows.get(2)).containsEntry("status", "ESCALATED");
        assertThat((String) store.rows.get(2).get("effect_detail")).contains("담당자 확인 필요", "되돌리기");
        assertThat(pipeline.retries).hasSize(1);
    }
}
