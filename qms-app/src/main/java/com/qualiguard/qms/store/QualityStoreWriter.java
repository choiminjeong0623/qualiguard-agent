package com.qualiguard.qms.store;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.bridge.KafkaTopics;
import com.qualiguard.qms.quality.Anomaly;
import com.qualiguard.qms.quality.CleanShot;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka → SQL Server 적재.
 *
 * - sensor.clean    → dbo.process_shot   (shot 단위 공정 데이터)
 * - quality.anomaly → dbo.anomaly_event  (이상 이력, 에이전트가 처리할 대기 목록)
 *
 * 중복 방어: INSERT ... WHERE NOT EXISTS 로 이미 저장된 메시지는 건너뛰고,
 * 테이블의 고유 인덱스(message_id, anomaly_id)가 마지막 안전장치 역할을 한다.
 * Kafka Streams 가 exactly-once 트랜잭션으로 기록하므로 read_committed 로 읽는다(application.yml).
 */
@Component
public class QualityStoreWriter {

    private static final Logger log = LoggerFactory.getLogger(QualityStoreWriter.class);

    private static final String INSERT_SHOT = """
            INSERT INTO dbo.process_shot
                (message_id, line_id, equipment_id, shot_no, shot_ts,
                 mold_temp, barrel_temp, injection_pressure, holding_pressure, cushion, cycle_time, motor_current,
                 inspection_result, defect_type, invalid_sensors)
            SELECT ?, ?, ?, ?, CAST(? AS DATETIMEOFFSET(3)),
                   ?, ?, ?, ?, ?, ?, ?,
                   ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM dbo.process_shot WHERE message_id = ?)
            """;

    private static final String INSERT_ANOMALY = """
            INSERT INTO dbo.anomaly_event
                (anomaly_id, line_id, equipment_id, shot_no, detected_at, rule_code, rule_name,
                 sensor_key, sensor_name, observed, baseline_mean, baseline_sigma, lower_limit, upper_limit,
                 recent_ng_rate, message, evidence_json)
            SELECT ?, ?, ?, ?, CAST(? AS DATETIMEOFFSET(3)), ?, ?,
                   ?, ?, ?, ?, ?, ?, ?,
                   ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM dbo.anomaly_event WHERE anomaly_id = ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public QualityStoreWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @KafkaListener(id = "qg-db-shot-writer", topics = KafkaTopics.SENSOR_CLEAN)
    public void onCleanShot(String value) {
        CleanShot s = objectMapper.readValue(value, CleanShot.class);
        Map<String, Double> v = s.values();
        int rows = jdbc.update(INSERT_SHOT,
                s.messageId(), s.lineId(), s.equipmentId(), s.shotNo(), s.ts(),
                v.get("moldTemp"), v.get("barrelTemp"), v.get("injectionPressure"), v.get("holdingPressure"),
                v.get("cushion"), v.get("cycleTime"), v.get("motorCurrent"),
                s.inspectionResult(), s.defectType(),
                s.invalidSensors() == null || s.invalidSensors().isEmpty() ? null : String.join(",", s.invalidSensors()),
                s.messageId());
        count("shot", rows);
    }

    @KafkaListener(id = "qg-db-anomaly-writer", topics = KafkaTopics.QUALITY_ANOMALY)
    public void onAnomaly(String value) {
        Anomaly a = objectMapper.readValue(value, Anomaly.class);

        // 에이전트가 원인 추정에 쓸 근거는 JSON 한 덩어리로 보관한다
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("recentValues", a.recentValues());
        evidence.put("sensorZScores", a.sensorZScores());
        evidence.put("recentDefectTypes", a.recentDefectTypes());

        int rows = jdbc.update(INSERT_ANOMALY,
                a.anomalyId(), a.lineId(), a.equipmentId(), a.shotNo(), a.ts(), a.rule(), a.ruleName(),
                a.sensor(), a.sensorName(), a.observed(), a.baselineMean(), a.baselineSigma(),
                a.lowerLimit(), a.upperLimit(), a.recentNgRate(), a.message(),
                objectMapper.writeValueAsString(evidence),
                a.anomalyId());
        count("anomaly", rows);
        if (rows > 0) {
            log.info("[DB 적재] 이상 이력 저장: {} {} {}", a.equipmentId(), a.rule(), a.sensorName());
        }
    }

    private void count(String type, int rows) {
        meters.counter("qg.store.records", "type", type, "result", rows > 0 ? "saved" : "duplicate").increment();
    }
}
