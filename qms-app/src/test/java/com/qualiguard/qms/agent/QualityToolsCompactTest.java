package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 도구 결과 줄이기: 모델 왕복마다 다시 보내는 입력 토큰을 줄이되, 판단 근거(수치·규격·원인)는 남긴다. */
class QualityToolsCompactTest {

    static class Capture extends JdbcTemplate {
        Object[] args;
        List<Map<String, Object>> rows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.args = args;
            return rows;
        }
    }

    private static Map<String, Object> trendRow(int no) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket_no", no);
        m.put("from_ts", "2026-11-14T10:0" + no + ":00Z");
        m.put("avg_value", 224.1 - no);
        m.put("lsl", 215);
        m.put("target", 225);
        m.put("usl", 235);
        m.put("unit", "°C");
        return m;
    }

    @Test
    void 추이는_규격을_첫_행에만_시각은_첫_행과_마지막_행에만_둔다() {
        List<Map<String, Object>> out = QualityTools.compactTrend(List.of(trendRow(1), trendRow(2), trendRow(3)));
        assertEquals(225, out.get(0).get("target"));
        assertTrue(out.get(0).containsKey("from_ts"));
        assertFalse(out.get(1).containsKey("target"));
        assertFalse(out.get(1).containsKey("from_ts"));
        assertTrue(out.get(2).containsKey("from_ts"));
        assertFalse(out.get(2).containsKey("usl"));
        assertEquals(222.1, out.get(1).get("avg_value"), "수치는 그대로");
    }

    @Test
    void 추이_구간이_15개를_넘지_않게_구간_크기를_키운다() {
        Capture jdbc = new Capture();
        QualityTools tools = new QualityTools(jdbc, new ArrayList<>(), "2026-11-14T10:24:48Z");
        tools.getSensorTrend("IMM-01", "barrelTemp", 300, 5);
        assertEquals(300, jdbc.args[2]);
        assertEquals(20, jdbc.args[3], "300 shot / 5 → 60구간 대신 20씩 15구간");
        tools.getSensorTrend("IMM-01", "barrelTemp", 60, 5);
        assertEquals(5, jdbc.args[3], "권장 인자(60/5)는 그대로");
        tools.getSensorTrend("IMM-01", "barrelTemp", 2000, 20);
        assertEquals(500, jdbc.args[2], "최대 500 shot");
    }

    @Test
    void 유사_사례는_세부_점수를_빼고_긴_글을_자른다() {
        Map<String, Object> r = new HashMap<>();
        r.put("ncr_no", "NCR-2025-0012");
        r.put("root_cause", "히터 밴드 열화");
        r.put("evidence", "가".repeat(400));
        r.put("sensor_match", 2);
        r.put("defect_match", 1);
        r.put("same_equipment", 1);
        r.put("semantic_similarity", null);
        r.put("score", 7);
        Map<String, Object> out = QualityTools.compactSimilar(List.of(r)).get(0);
        assertEquals("히터 밴드 열화", out.get("root_cause"));
        assertEquals(7, out.get("score"));
        assertFalse(out.containsKey("sensor_match"));
        assertFalse(out.containsKey("semantic_similarity"));
        assertEquals(QualityTools.MAX_CASE_TEXT + 1, ((String) out.get("evidence")).length());
    }

    @Test
    void 검증용_수치는_줄인_뒤의_값으로_모은다() {
        Capture jdbc = new Capture();
        jdbc.rows = List.of(trendRow(1), trendRow(2));
        ToolObservations observed = new ToolObservations();
        new QualityTools(jdbc, new ArrayList<>(), "2026-11-14T10:24:48Z", System.nanoTime(), null, observed)
                .getSensorTrend("IMM-01", "barrelTemp", 60, 5);
        assertTrue(observed.values().stream().anyMatch(v -> v.compareTo(new java.math.BigDecimal("225")) == 0), "첫 행의 규격은 남음");
        assertTrue(observed.values().stream().anyMatch(v -> v.compareTo(new java.math.BigDecimal("222.1")) == 0));
    }
}
