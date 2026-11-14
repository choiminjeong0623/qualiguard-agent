package com.qualiguard.qms.bridge;

/** Kafka 토픽 이름 (docker-compose.yml 의 kafka-init 에서 생성). */
public final class KafkaTopics {

    public static final String SENSOR_RAW = "sensor.raw";
    public static final String SENSOR_CLEAN = "sensor.clean";
    public static final String QUALITY_ANOMALY = "quality.anomaly";
    public static final String QUALITY_NCR_APPROVED = "quality.ncr.approved";
    public static final String QUALITY_DLQ = "quality.dlq";

    private KafkaTopics() {
    }
}
