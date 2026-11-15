package com.qualiguard.qms.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** MCP 도구(ADR-013): 기준 경보로 기준 시점을 정해 저장 프로시저에 넘기고, 시각을 직접 받지 않는다. */
class McpQualityToolsTest {

    static class FakeJdbc extends JdbcTemplate {
        List<Object[]> procCalls = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            return "a-325".equals(args[0]) ? (List<T>) List.of("2026-11-14T10:24:48.942Z") : List.of();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            procCalls.add(args);
            return List.of();
        }
    }

    @Test
    void 기준_경보를_주면_그_경보_시각까지만_조회한다() {
        FakeJdbc jdbc = new FakeJdbc();
        new McpQualityTools(jdbc).getSpcSummary("IMM-01", 50, "a-325");
        Object[] args = jdbc.procCalls.get(0);
        assertEquals("2026-11-14T10:24:48.942Z", args[args.length - 1]);
    }

    @Test
    void 기준_경보가_없으면_지금까지_조회한다() {
        FakeJdbc jdbc = new FakeJdbc();
        new McpQualityTools(jdbc).findSimilarNcr("IMM-01", "barrelTemp", null, null);
        Object[] args = jdbc.procCalls.get(0);
        assertNull(args[args.length - 1]);
    }

    @Test
    void 없는_기준_경보는_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpQualityTools(new FakeJdbc()).getDefectSummary("IMM-01", 300, 50, "nope"));
    }
}
