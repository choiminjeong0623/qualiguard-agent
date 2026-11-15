package com.qualiguard.qms.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** 아웃박스 발행기(F-09): Kafka 기록이 확인된 이벤트만 발행 완료로 표시하고, 실패하면 다음 주기에 다시 보낸다. */
class OutboxPublisherTest {

    static class FakeJdbc extends JdbcTemplate {
        List<Map<String, Object>> pending = new ArrayList<>();
        List<String> updates = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return pending;
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql.contains("published_at = SYSDATETIMEOFFSET()") ? "PUBLISHED " + args[0]
                    : "FAILED " + args[1]);
            return 1;
        }
    }

    private static Map<String, Object> event(String id) {
        Map<String, Object> e = new HashMap<>();
        e.put("event_id", id);
        e.put("event_type", "NCR_APPROVED");
        e.put("topic", "quality.ncr.approved");
        e.put("msg_key", "IMM-01");
        e.put("payload", "{\"eventId\":\"" + id + "\"}");
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 기록이_확인된_이벤트만_발행_완료로_표시한다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.pending.add(event("e1"));
        jdbc.pending.add(event("e2"));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int sent = new OutboxPublisher(jdbc, kafka, true, 5).publishPending();

        assertEquals(2, sent);
        assertEquals(List.of("PUBLISHED e1", "PUBLISHED e2"), jdbc.updates);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 전송이_실패하면_시도_횟수만_올리고_이번_주기는_멈춘다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.pending.add(event("e1"));
        jdbc.pending.add(event("e2"));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int sent = new OutboxPublisher(jdbc, kafka, true, 5).publishPending();

        assertEquals(0, sent);
        assertEquals(List.of("FAILED e1"), jdbc.updates, "e2 는 순서를 지키기 위해 이번 주기에 보내지 않음");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 메시지_key_는_설비_ID_이고_헤더에_이벤트_ID_를_담는다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.pending.add(event("e1"));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        List<ProducerRecord<String, String>> sentRecords = new ArrayList<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            sentRecords.add(inv.getArgument(0));
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        new OutboxPublisher(jdbc, kafka, true, 5).publishPending();

        ProducerRecord<String, String> r = sentRecords.get(0);
        assertEquals("quality.ncr.approved", r.topic());
        assertEquals("IMM-01", r.key());
        assertTrue(new String(r.headers().lastHeader("eventId").value()).equals("e1"));
    }
}
