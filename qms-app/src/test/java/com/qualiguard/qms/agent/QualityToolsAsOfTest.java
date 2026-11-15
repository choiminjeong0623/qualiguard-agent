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

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return List.of();
        }
    }

    @Test
    void 유사_사례_검색에_기준_시점을_넘긴다() {
        Capture jdbc = new Capture();
        List<String> trace = new ArrayList<>();
        new QualityTools(jdbc, trace, "2026-11-14T10:24:48.942Z").findSimilarNcr("IMM-01", "barrelTemp", "");
        assertTrue(jdbc.sql.contains("usp_FindSimilarNcr"));
        assertEquals("2026-11-14T10:24:48.942Z", jdbc.args[jdbc.args.length - 1]);
        assertEquals(null, jdbc.args[2], "빈 불량 유형은 NULL 로");
        assertTrue(trace.get(0).startsWith("findSimilarNcr("));
    }
}
