package com.qualiguard.qms.bridge;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MQTT 메시지 1건을 검증 → 중복 확인 → Kafka 기록 순서로 처리하고,
 * MQTT 에 ack 해도 되는지(true/false)를 돌려준다. MQTT 연결·ack 는 MqttTelemetrySubscriber 가 맡는다.
 *
 * - 정상: sensor.raw 토픽, key = equipmentId (같은 설비의 메시지는 같은 파티션 → 순서 보장) → ack
 * - 형식 오류: quality.dlq 토픽 (오류 사유를 헤더에 기록) → ack
 * - 중복: 버림 (QoS 1 은 같은 메시지가 두 번 올 수 있음) → ack
 * - Kafka 기록 실패: ack 하지 않음 → 재접속 시 브로커가 다시 보내 준다
 */
@Component
public class TelemetryBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBridgeHandler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MessageIdDeduplicator deduplicator;
    private final long sendTimeoutSeconds;

    private final Counter published;
    private final Counter duplicate;
    private final Counter invalid;
    private final Counter failed;

    public TelemetryBridgeHandler(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
            Validator validator, MessageIdDeduplicator deduplicator, BridgeProperties.Bridge props,
            MeterRegistry meters) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.deduplicator = deduplicator;
        this.sendTimeoutSeconds = props.kafkaSendTimeoutSeconds();
        this.published = counter(meters, "published");
        this.duplicate = counter(meters, "duplicate");
        this.invalid = counter(meters, "invalid");
        this.failed = counter(meters, "failed");
    }

    private static Counter counter(MeterRegistry meters, String result) {
        return Counter.builder("qg.bridge.messages")
                .description("MQTT→Kafka 브리지 처리 건수")
                .tag("result", result)
                .register(meters);
    }

    /** @return MQTT 에 ack 해도 되면 true */
    public boolean handle(String sourceTopic, String body) {
        TelemetryMessage telemetry;
        try {
            telemetry = objectMapper.readValue(body, TelemetryMessage.class);
        } catch (RuntimeException e) {
            return toDlq(sourceTopic, body, "JSON 파싱 실패: " + e.getMessage());
        }

        Set<ConstraintViolation<TelemetryMessage>> violations = validator.validate(telemetry);
        if (!violations.isEmpty()) {
            String reason = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return toDlq(sourceTopic, body, "형식 오류: " + reason);
        }

        if (deduplicator.alreadyProcessed(telemetry.messageId())) {
            duplicate.increment();
            log.debug("중복 메시지 무시: {}", telemetry.messageId());
            return true;
        }

        try {
            String enriched = enrich(body, sourceTopic);
            var record = new ProducerRecord<>(KafkaTopics.SENSOR_RAW, telemetry.equipmentId(), enriched);
            record.headers().add("messageId", telemetry.messageId().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);   // 기록 성공을 확인한 뒤에만
        } catch (Exception e) {
            failed.increment();
            log.error("Kafka 기록 실패 — MQTT ack 보류(재접속 시 재전송): messageId={}, 원인={}",
                    telemetry.messageId(), e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
        deduplicator.markProcessed(telemetry.messageId());
        published.increment();
        return true;                                                         // ack 허용
    }

    /** 원본 JSON 에 브리지 처리 정보를 덧붙인다 (추적용). */
    private String enrich(String body, String sourceTopic) {
        ObjectNode root = (ObjectNode) objectMapper.readTree(body);
        ObjectNode bridge = root.putObject("bridge");
        bridge.put("receivedAt", OffsetDateTime.now(KST).toString());
        bridge.put("sourceTopic", sourceTopic);
        return objectMapper.writeValueAsString(root);
    }

    private boolean toDlq(String sourceTopic, String body, String reason) {
        try {
            var record = new ProducerRecord<String, String>(KafkaTopics.QUALITY_DLQ, "bridge", body);
            record.headers().add("errorReason", reason.getBytes(StandardCharsets.UTF_8));
            record.headers().add("sourceTopic", String.valueOf(sourceTopic).getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            invalid.increment();
            log.warn("DLQ 전송: topic={}, 사유={}", sourceTopic, reason);
            return true;
        } catch (Exception e) {
            failed.increment();
            log.error("DLQ 전송 실패 — MQTT ack 보류: {}", e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
