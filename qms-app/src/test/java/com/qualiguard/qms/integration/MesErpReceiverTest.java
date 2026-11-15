package com.qualiguard.qms.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/** 가상 MES/ERP 수신(F-09): 이벤트 하나에 MES·ERP 업무를 하나씩 기록하고, 같은 이벤트를 다시 받으면 기록하지 않는다. */
class MesErpReceiverTest {

    /** (event_id, system) 고유 제약 + NOT EXISTS 를 흉내 낸다. */
    static class FakeJdbc extends JdbcTemplate {
        Set<String> rows = new HashSet<>();

        @Override
        public int update(String sql, Object... args) {
            return rows.add(args[0] + "/" + args[1]) ? 1 : 0;
        }
    }

    private static final String EVENT = """
            {"eventId":"8d1c1c6e-0000-4000-8000-000000000001","eventType":"NCR_APPROVED","ncrNo":"AI-2026-0035",
             "equipmentId":"IMM-01","causeCode":"HEATER_DEGRADATION","defectType":"SHORT_SHOT",
             "recommendedAction":"2번 존 히터 밴드 저항 측정","approvedBy":"품질팀"}
            """;

    @Test
    void 이벤트_하나에_MES_와_ERP_업무를_하나씩_기록한다() {
        FakeJdbc jdbc = new FakeJdbc();
        int rows = new MesErpReceiver(jdbc, JsonMapper.builder().build()).receive(EVENT);
        assertEquals(2, rows);
        assertTrue(jdbc.rows.contains("8d1c1c6e-0000-4000-8000-000000000001/MES"));
        assertTrue(jdbc.rows.contains("8d1c1c6e-0000-4000-8000-000000000001/ERP"));
    }

    @Test
    void 같은_이벤트를_다시_받으면_기록하지_않는다() {
        FakeJdbc jdbc = new FakeJdbc();
        MesErpReceiver receiver = new MesErpReceiver(jdbc, JsonMapper.builder().build());
        receiver.receive(EVENT);
        assertEquals(0, receiver.receive(EVENT));
        assertEquals(2, jdbc.rows.size());
    }

    @Test
    void 식별자가_없는_이벤트는_건너뛴다() {
        FakeJdbc jdbc = new FakeJdbc();
        assertEquals(0, new MesErpReceiver(jdbc, JsonMapper.builder().build()).receive("{\"eventType\":\"X\"}"));
        assertTrue(jdbc.rows.isEmpty());
    }
}
