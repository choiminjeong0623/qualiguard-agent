package com.qualiguard.qms.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 발행기 (F-09): integration_outbox 의 아직 보내지 않은 이벤트를 Kafka 로 보낸다.
 *
 * - 승인과 이벤트 기록은 같은 DB 트랜잭션에서 이미 커밋됐다 (usp_ReviewNcr → usp_EnqueueNcrApproved).
 * - 여기서는 Kafka 기록 성공을 확인한 뒤에만 published_at 을 채운다. 실패하면 attempts 를 올리고 다음 주기에 다시 보낸다.
 * - 따라서 전송은 최소 1번(at-least-once)이고, 수신 쪽이 event_id 로 중복을 거른다.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final boolean enabled;
    private final long sendTimeoutSeconds;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            @Value("${qg.integration.publish-enabled:true}") boolean enabled,
            @Value("${qg.bridge.kafka-send-timeout-seconds:5}") long sendTimeoutSeconds) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.enabled = enabled;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${qg.integration.poll-ms:2000}", initialDelayString = "${qg.integration.poll-ms:2000}")
    public void poll() {
        if (enabled) {
            publishPending();
        }
    }

    /** 보내지 않은 이벤트를 오래된 순서로 최대 20건 보낸다. 보낸 건수를 돌려준다. */
    public int publishPending() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT TOP 20 CONVERT(VARCHAR(36), event_id) AS event_id, event_type, topic, msg_key, payload
                FROM dbo.integration_outbox
                WHERE published_at IS NULL AND attempts < ?
                ORDER BY created_at, event_id
                """, MAX_ATTEMPTS);
        int sent = 0;
        for (Map<String, Object> e : pending) {
            String eventId = (String) e.get("event_id");
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>((String) e.get("topic"),
                        (String) e.get("msg_key"), (String) e.get("payload"));
                record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventType", ((String) e.get("event_type")).getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);   // 기록 성공을 확인한 뒤에만
                jdbc.update("""
                        UPDATE dbo.integration_outbox SET published_at = SYSDATETIMEOFFSET(), attempts = attempts + 1,
                               last_error = NULL
                        WHERE event_id = ?
                        """, eventId);
                sent++;
                log.info("[연계] 이벤트 발행 {} {} → {}", e.get("event_type"), eventId, e.get("topic"));
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String error = ex.toString();
                jdbc.update("""
                        UPDATE dbo.integration_outbox SET attempts = attempts + 1, last_error = ?
                        WHERE event_id = ?
                        """, error.length() > 1000 ? error.substring(0, 1000) : error, eventId);
                log.warn("[연계] 이벤트 발행 실패 {} — 다음 주기에 다시 보냄: {}", eventId, error);
                break;   // 같은 설비 이벤트의 순서를 지키기 위해 이번 주기는 여기서 멈춘다
            }
        }
        return sent;
    }
}
