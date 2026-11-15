package com.qualiguard.qms.bridge;

/** Kafka 토픽 이름 (docker-compose.yml 의 kafka-init 에서 생성). */
public final class KafkaTopics {

    public static final String SENSOR_RAW = "sensor.raw";
    public static final String SENSOR_CLEAN = "sensor.clean";
    public static final String QUALITY_ANOMALY = "quality.anomaly";
    public static final String QUALITY_NCR_APPROVED = "quality.ncr.approved";
    /** 가상 MES/ERP 의 처리 결과 응답 (수신 확인·처리 완료·실패, ADR-012) */
    public static final String INTEGRATION_NCR_ACK = "integration.ncr.ack";
    public static final String QUALITY_DLQ = "quality.dlq";

    private KafkaTopics() {
    }
}
