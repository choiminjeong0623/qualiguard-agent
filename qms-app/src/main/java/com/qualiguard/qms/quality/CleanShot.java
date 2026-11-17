package com.qualiguard.qms.quality;

import java.util.List;
import java.util.Map;

/**
 * 전처리를 거친 shot 데이터 (Kafka sensor.clean 메시지). 중복이 제거되고, 측정 범위를 벗어났거나
 * 값이 없는 센서는 values 에서 빠지고 invalidSensors 에 들어간다.
 * shotNo·inspectionResult·serialNo 는 원본에 없으면 null 이다 (ADR-017).
 * lateArrival 은 늦게 와서(그 설비에서 이미 판정한 shot 보다 ts 가 앞섬) 실시간 판정을 건너뛴 shot 이다. 저장은 한다.
 */
public record CleanShot(String messageId, String lineId, String equipmentId, Long shotNo, String ts,
        Map<String, Double> values, List<String> invalidSensors, String inspectionResult, String defectType,
        String serialNo, boolean lateArrival) {
}
