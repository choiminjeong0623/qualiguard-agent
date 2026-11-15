package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 관찰(OBSERVE) 뒤 재확인 규칙의 거절 조건을 DB 없이 검증한다.
 * (LLM 을 부르기 전 단계에서 걸러지는지 — 비용과 미래 정보 누수를 막는 장치)
 */
class IncidentAnalyzerRecheckTest {

    /** 필요한 조회만 흉내 내는 JdbcTemplate. */
    static class FakeJdbc extends JdbcTemplate {
        Map<String, Object> parent;
        /** analysis_id → {trigger_type, parent_analysis_id} */
        Map<Integer, Map<String, Object>> chain = new HashMap<>();
        boolean shotExists = true;
        Object lastShotQueried;

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            return parent;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("trigger_type, parent_analysis_id")) {
                Map<String, Object> row = chain.get((Integer) args[0]);
                return row == null ? List.of() : List.of(row);
            }
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            if (sql.contains("FROM dbo.process_shot")) {
                lastShotQueried = args[1];
                return shotExists ? (List<T>) List.of("2026-11-14T10:24:50.000Z") : List.of();
            }
            return List.of();
        }
    }

    private static Map<String, Object> parent(String decision) {
        Map<String, Object> m = new HashMap<>();
        m.put("analysis_id", 10);
        m.put("equipment_id", "IMM-01");
        m.put("seed_anomaly_id", "a-1");
        m.put("anomaly_ids", "a-1");
        m.put("decision", decision);
        m.put("run_tag", "replay-test");
        m.put("base_shot", 325L);
        m.put("base_ts", "2026-11-14T10:24:48.942Z");
        m.put("seed_ts", "2026-11-14T10:24:48.942Z");
        return m;
    }

    private static Map<String, Object> link(String trigger, Integer parentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("trigger_type", trigger);
        m.put("parent_analysis_id", parentId);
        return m;
    }

    private static IncidentAnalyzer analyzer(FakeJdbc jdbc, int recheckShots, int recheckMax) {
        AgentProperties props = new AgentProperties(false, 5000, 100, 30, 60, recheckShots, recheckMax, 1);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.chat.client.ChatClient.Builder> noLlm =
                org.mockito.Mockito.mock(ObjectProvider.class);
        return new IncidentAnalyzer(jdbc, JsonMapper.builder().build(), null, props, noLlm, "test-model");
    }

    @Test
    void 관찰이_아닌_판단은_재확인하지_않는다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.parent = parent("NCR_DRAFT");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer(jdbc, 40, 3).prepareRecheck(10, null));
        assertTrue(e.getMessage().contains("관찰"));
    }

    @Test
    void 새_경보_없는_재확인이_상한에_닿으면_거절한다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.parent = parent("OBSERVE");
        // 10 ← 9(재확인) ← 8(재확인) ← 7(경보): 이미 재확인 2회
        jdbc.chain.put(10, link("RECHECK", 9));
        jdbc.chain.put(9, link("RECHECK", 8));
        jdbc.chain.put(8, link("ALARM", null));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> analyzer(jdbc, 40, 2).prepareRecheck(10, null));
        assertTrue(e.getMessage().contains("상한"));
    }

    @Test
    void 재확인_시점의_데이터가_아직_없으면_거절한다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.parent = parent("OBSERVE");
        jdbc.chain.put(10, link("ALARM", null));
        jdbc.shotExists = false;
        assertThrows(IllegalStateException.class, () -> analyzer(jdbc, 40, 3).prepareRecheck(10, null));
        // 기본 재확인 shot = 관찰 기준 shot 325 + 40
        assertEquals(365L, jdbc.lastShotQueried);
    }

    @Test
    void 재확인_shot_은_관찰_기준_shot_보다_뒤여야_한다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.parent = parent("OBSERVE");
        jdbc.chain.put(10, link("ALARM", null));
        assertThrows(IllegalArgumentException.class, () -> analyzer(jdbc, 40, 3).prepareRecheck(10, 300L));
    }
}
