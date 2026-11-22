package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 유사 사례 검색(F-10)이 분석 기준 시점을 넘겨, 그 뒤에 승인된 사례(미래 지식)를 보지 않는지 검증한다.
 * 승인된 AI 초안이 지식저장소에 들어오면서 생긴 누수 경로를 막는 장치다 (장애 분석 04, ADR-007 결정 4).
 */
class QualityToolsAsOfTest {

    static class Capture extends JdbcTemplate {
        String sql;
        Object[] args;

        List<Map<String, Object>> rows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return rows;
        }
    }

    @Test
    void 유사_사례_검색에_기준_시점을_넘긴다() {
        Capture jdbc = new Capture();
        List<String> trace = new ArrayList<>();
        new QualityTools(jdbc, trace, "2026-11-14T10:24:48.942Z").findSimilarNcr("IMM-01", "barrelTemp", "", null);
        assertTrue(jdbc.sql.contains("usp_FindSimilarNcr"));
        assertEquals("2026-11-14T10:24:48.942Z", jdbc.args[4]);
        assertEquals(null, jdbc.args[5], "현상 설명이 없으면 의미 검색 벡터도 없음");
        assertEquals(null, jdbc.args[2], "빈 불량 유형은 NULL 로");
        assertTrue(trace.get(0).startsWith("findSimilarNcr("));
    }

    @Test
    void 현상_설명을_주면_벡터를_넘기고_기록에는_설명만_남긴다() {
        Capture jdbc = new Capture();
        List<String> trace = new ArrayList<>();
        new QualityTools(jdbc, trace, "2026-11-14T10:24:48.942Z", System.nanoTime(), s -> "[0.1,0.2]")
                .findSimilarNcr("IMM-01", "barrelTemp", null, "온도가 서서히 낮아짐");
        assertEquals("[0.1,0.2]", jdbc.args[5]);
        assertTrue(trace.get(0).contains("situation=\"온도가 서서히 낮아짐\"") && trace.get(0).contains("+의미검색"));
        assertTrue(!trace.get(0).contains("[0.1,0.2]"), "벡터 값은 기록에 남기지 않음");
    }

    @Test
    void 도구가_돌려준_수치와_조회_범위를_검증용으로_모은다() {
        Capture jdbc = new Capture();
        jdbc.rows = List.of(Map.of("avg_value", new java.math.BigDecimal("224.1300"), "ng_count", 3));
        ToolObservations observed = new ToolObservations();
        new QualityTools(jdbc, new ArrayList<>(), "2026-11-14T10:24:48.942Z", System.nanoTime(), null, observed)
                .getSensorTrend("IMM-01", "barrelTemp", 300, 20);
        List<String> values = observed.values().stream().map(java.math.BigDecimal::toPlainString).toList();
        assertTrue(values.containsAll(List.of("224.13", "3", "300", "20")), values.toString());
    }
}
