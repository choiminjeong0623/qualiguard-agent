package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.knowledge.SopLibrary;

/** 분석 1회 안의 도구 호출 상한 (가드레일 G-03, docs/agent/guardrail-policy.md). */
class ToolCallLimitTest {

    static class Counting extends QualityToolsAsOfTest.Capture {
        int queries;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queries++;
            return super.queryForList(sql, args);
        }
    }

    @Test
    void 상한을_넘은_호출은_DB를_조회하지_않고_결론을_내라는_오류를_돌려준다() {
        Counting jdbc = new Counting();
        List<String> trace = new ArrayList<>();
        QualityTools tools = new QualityTools(jdbc, trace, "2026-11-14T10:24:48Z").withLimit(new ToolCallLimit(2));

        tools.getSpcSummary("IMM-01", 50);
        tools.getMaintenanceHistory("IMM-01");
        List<Map<String, Object>> third = tools.getSensorTrend("IMM-01", "barrelTemp", 60, 5);

        assertEquals(2, jdbc.queries, "세 번째 호출은 DB 조회 없음");
        assertTrue(String.valueOf(third.get(0).get("error")).contains("도구 호출 상한(2회)"));
        assertTrue(trace.stream().anyMatch(t -> t.contains("도구 호출 상한 2회 초과: getSensorTrend")));
    }

    @Test
    void SOP_검색과_공정_조회가_같은_상한을_나눠_쓴다() {
        Counting jdbc = new Counting();
        List<String> trace = new ArrayList<>();
        ToolCallLimit limit = new ToolCallLimit(1);
        QualityTools tools = new QualityTools(jdbc, trace, "2026-11-14T10:24:48Z").withLimit(limit);
        SopTools sop = new SopTools(new SopLibrary(""), trace, System.nanoTime()).withLimit(limit);

        tools.getSpcSummary("IMM-01", 50);
        List<Map<String, Object>> hits = sop.searchSop("barrelTemp 하락 히터");

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).containsKey("error"));
    }

    @Test
    void reset_하면_처음부터_세고_0이면_상한이_없다() {
        ToolCallLimit limit = new ToolCallLimit(1);
        assertTrue(limit.tryAcquire());
        assertTrue(!limit.tryAcquire());
        limit.reset();
        assertTrue(limit.tryAcquire());

        ToolCallLimit off = new ToolCallLimit(0);
        for (int i = 0; i < 100; i++) {
            assertTrue(off.tryAcquire());
        }
    }
}
