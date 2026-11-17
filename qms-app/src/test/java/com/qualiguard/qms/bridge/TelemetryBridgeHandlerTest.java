package com.qualiguard.qms.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결측이 많은 실제 설비 데이터(ADR-017): 필수 항목(messageId·lineId·equipmentId·ts)만 있으면
 * DLQ 가 아니라 sensor.raw 로 가야 한다.
 */
class TelemetryBridgeHandlerTest {

    private KafkaTemplate<String, String> kafka;
    private SimpleMeterRegistry meters;
    private TelemetryBridgeHandler handler;
    private int seq;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        meters = new SimpleMeterRegistry();
        var props = new BridgeProperties.Bridge(1000, 5);
        handler = new TelemetryBridgeHandler(kafka, JsonMapper.builder().build(),
                Validation.buildDefaultValidatorFactory().getValidator(), new MessageIdDeduplicator(props), props, meters);
    }

    /** 결측 없는 기본 메시지에서 일부를 바꾼다. */
    private String msg(String messageId, String shotNo, String sensors, String inspection, String serialNo) {
        return """
                {"messageId": %s, "lineId": "LINE1", "equipmentId": "IMM-01", %s"ts": "2026-11-17T18:00:00.000+09:00",
                 "sensors": %s, "inspection": %s, "serialNo": %s}
                """.formatted(messageId, shotNo == null ? "" : "\"shotNo\": " + shotNo + ", ", sensors, inspection,
                serialNo);
    }

    private String id() {
        return "\"m-" + (++seq) + "\"";
    }

    private static final String FULL_SENSORS = "{\"moldTemp\": {\"value\": 60.1, \"unit\": \"C\"}, \"cushion\": {\"value\": 5.0, \"unit\": \"mm\"}}";
    private static final String OK = "{\"result\": \"OK\", \"defectType\": null}";

    private String sentTopic() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(cap.capture());
        return cap.getValue().topic();
    }

    private double missing(String field, String kind) {
        var c = meters.find("qg.bridge.missing_fields").tag("field", field).tag("kind", kind).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 센서_값이_null_이어도_sensor_raw_로_간다() {
        assertTrue(handler.handle("t", msg(id(), "1",
                "{\"moldTemp\": {\"value\": null, \"unit\": \"C\"}, \"cushion\": {\"value\": null, \"unit\": null}}", OK,
                "\"S-1\"")));
        assertEquals(KafkaTopics.SENSOR_RAW, sentTopic());
        assertEquals(1, missing("sensors.moldTemp", "null"));
    }

    @Test
    void 센서_키가_빠지거나_sensors_가_null_이어도_sensor_raw_로_간다() {
        assertTrue(handler.handle("t", msg(id(), "1", "{\"moldTemp\": null}", OK, "\"S-1\"")));
        assertTrue(handler.handle("t", msg(id(), "2", "null", OK, "\"S-2\"")));
        assertTrue(handler.handle("t", msg(id(), "3", "{}", OK, "\"S-3\"")));
        assertEquals(3, meters.find("qg.bridge.messages").tag("result", "published").counter().count());
        assertEquals(1, missing("sensors.moldTemp", "null"));
        assertEquals(1, missing("sensors.cushion", "absent"));
        assertEquals(2, missing("sensors", "absent"));
    }

    @Test
    void inspection_없음과_shotNo_없음도_sensor_raw_로_간다() {
        assertTrue(handler.handle("t", msg(id(), null, FULL_SENSORS, "null", "\"S-1\"")));
        assertEquals(KafkaTopics.SENSOR_RAW, sentTopic());
        assertEquals(1, missing("shotNo", "absent"));
        assertEquals(1, missing("inspection", "absent"));
    }

    @Test
    void serialNo_가_null_이거나_중복이어도_sensor_raw_로_간다() {
        assertTrue(handler.handle("t", msg(id(), "1", FULL_SENSORS, OK, "null")));
        assertTrue(handler.handle("t", msg(id(), "2", FULL_SENSORS, OK, "\"S-DUP\"")));
        assertTrue(handler.handle("t", msg(id(), "3", FULL_SENSORS, OK, "\"S-DUP\"")));
        assertEquals(3, meters.find("qg.bridge.messages").tag("result", "published").counter().count());
        assertEquals(1, missing("serialNo", "absent"));
    }

    @Test
    void 알_수_없는_필드가_있어도_실패하지_않는다() {
        String body = msg(id(), "1", FULL_SENSORS, OK, "\"S-1\"").replace("\"lineId\"", "\"plcVersion\": \"v9\", \"lineId\"");
        assertTrue(handler.handle("t", body));
        assertEquals(KafkaTopics.SENSOR_RAW, sentTopic());
    }

    @Test
    void messageId_가_없으면_여전히_DLQ_로_간다() {
        assertTrue(handler.handle("t", msg("null", "1", FULL_SENSORS, OK, "\"S-1\"")));
        assertEquals(KafkaTopics.QUALITY_DLQ, sentTopic());
    }

    @Test
    void shotNo_가_0_이하이거나_검사_결과가_이상하면_DLQ_로_간다() {
        assertTrue(handler.handle("t", msg(id(), "0", FULL_SENSORS, OK, "null")));
        assertTrue(handler.handle("t", msg(id(), "1", FULL_SENSORS, "{\"result\": \"MAYBE\"}", "null")));
        assertEquals(2, meters.find("qg.bridge.messages").tag("result", "invalid").counter().count());
    }

    @Test
    void 같은_messageId_재전송은_한_번만_sensor_raw_로_간다() {
        String body = msg("\"same-1\"", "1", FULL_SENSORS, OK, "\"TMP-1\"");
        assertTrue(handler.handle("t", body));
        assertTrue(handler.handle("t", body));
        assertTrue(handler.handle("t", body));
        assertEquals(1, meters.find("qg.bridge.messages").tag("result", "published").counter().count());
        assertEquals(2, meters.find("qg.bridge.messages").tag("result", "duplicate").counter().count());
        verify(kafka, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
    }
}
