package com.qualiguard.qms.quality;

import java.util.List;
import java.util.Map;

/** 전처리를 거친 shot 데이터 (Kafka sensor.clean 메시지). 중복이 제거되고, 측정 범위를 벗어난 센서는 values 에서 빠진다. */
public record CleanShot(String messageId, String lineId, String equipmentId, long shotNo, String ts,
        Map<String, Double> values, List<String> invalidSensors, String inspectionResult, String defectType) {
}
