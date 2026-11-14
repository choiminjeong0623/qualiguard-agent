package com.qualiguard.qms.quality;

import java.util.Map;

/** shot 1건의 공정 데이터 (Kafka sensor.raw 메시지에서 변환). */
public record Shot(String messageId, String lineId, String equipmentId, long shotNo, String ts,
        Map<String, Double> values, boolean ng, String defectType) {
}
