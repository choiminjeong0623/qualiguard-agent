package com.qualiguard.qms.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * 가상 MES/ERP 수신(F-09, ADR-012): 이벤트 하나에 MES·ERP 업무를 하나씩 기록하고, 같은 이벤트를 다시 받으면 업무는 그대로 두고
 * 같은 응답(같은 response_id)을 다시 보낸다. 시험용 실패 설정이면 그 시스템은 실패 응답.
 */
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

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String, Object>> sent = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private MesErpReceiver receiver(FakeJdbc jdbc, String failing) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sent.add(json.readValue((String) inv.getArgument(2), Map.class));
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });
        return new MesErpReceiver(jdbc, json, kafka, failing);
    }

    @Test
    void 이벤트_하나에_MES_와_ERP_업무를_하나씩_기록하고_수신_확인과_처리_완료를_보낸다() {
        FakeJdbc jdbc = new FakeJdbc();
        assertEquals(2, receiver(jdbc, "").receive(EVENT));
        assertEquals(4, sent.size());
        assertEquals(List.of("MES RECEIVED 1", "MES PROCESSED 2", "ERP RECEIVED 1", "ERP PROCESSED 2"),
                sent.stream().map(a -> a.get("system") + " " + a.get("status") + " " + a.get("seq")).toList());
        assertEquals("MES-WO-8D1C1C6E", sent.get(1).get("referenceNo"));
    }

    @Test
    void 같은_이벤트를_다시_받으면_업무는_그대로_두고_같은_응답을_다시_보낸다() {
        FakeJdbc jdbc = new FakeJdbc();
        MesErpReceiver r = receiver(jdbc, "");
        r.receive(EVENT);
        assertEquals(0, r.receive(EVENT));
        assertEquals(2, jdbc.rows.size());
        assertEquals(8, sent.size());
        assertEquals(sent.get(1).get("responseId"), sent.get(5).get("responseId"), "같은 단계 응답은 같은 ID");
    }

    @Test
    void 시험용_실패_설정이면_그_시스템은_실패_응답을_보낸다() {
        receiver(new FakeJdbc(), "erp").receive(EVENT);
        Map<String, Object> erp = sent.get(3);
        assertEquals("FAILED", erp.get("status"));
        assertTrue(((String) erp.get("message")).contains("ERP"));
        assertEquals("PROCESSED", sent.get(1).get("status"), "MES 는 그대로 처리");
    }

    @Test
    void 다시_보내기_이벤트는_대상_시스템만_처리한다() {
        FakeJdbc jdbc = new FakeJdbc();
        String retry = EVENT.replace("\"approvedBy\":\"품질팀\"", "\"approvedBy\":\"품질팀\",\"targetSystems\":\"ERP\"");
        assertEquals(1, receiver(jdbc, "").receive(retry));
        assertTrue(jdbc.rows.stream().allMatch(r -> r.endsWith("/ERP")));
        assertTrue(sent.stream().allMatch(a -> "ERP".equals(a.get("system"))));
    }

    @Test
    void 식별자가_없는_이벤트는_건너뛴다() {
        FakeJdbc jdbc = new FakeJdbc();
        assertEquals(0, receiver(jdbc, "").receive("{\"eventType\":\"X\"}"));
        assertTrue(jdbc.rows.isEmpty());
        assertTrue(sent.isEmpty());
    }
}
