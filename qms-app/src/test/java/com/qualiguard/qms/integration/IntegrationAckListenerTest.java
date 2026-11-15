package com.qualiguard.qms.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/** QMS 응답 수신(ADR-012): 응답을 프로시저에 그대로 넘기고, 형식이 틀린 응답은 DB 를 건드리지 않는다. */
class IntegrationAckListenerTest {

    static class FakeJdbc extends JdbcTemplate {
        Object[] args;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            this.args = args;
            return (T) "APPLIED";
        }
    }

    @Test
    void 응답을_프로시저로_반영한다() {
        FakeJdbc jdbc = new FakeJdbc();
        String ack = """
                {"responseId":"6f0e5a3c-1111-3222-8333-444455556666","eventId":"8d1c1c6e-0000-4000-8000-000000000001",
                 "ncrNo":"AI-2026-0036","system":"MES","status":"PROCESSED","seq":2,"referenceNo":"MES-WO-8D1C1C6E"}
                """;
        assertEquals("APPLIED", new IntegrationAckListener(jdbc, JsonMapper.builder().build()).apply(ack));
        assertEquals("MES", jdbc.args[2]);
        assertEquals(2, jdbc.args[4]);
        assertEquals("MES-WO-8D1C1C6E", jdbc.args[5]);
    }

    @Test
    void 형식이_틀린_응답은_건너뛴다() {
        FakeJdbc jdbc = new FakeJdbc();
        assertEquals("INVALID", new IntegrationAckListener(jdbc, JsonMapper.builder().build())
                .apply("{\"eventId\":\"x\",\"status\":\"PROCESSED\"}"));
        assertNull(jdbc.args);
    }
}
