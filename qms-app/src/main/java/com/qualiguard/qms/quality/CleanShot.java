package com.qualiguard.qms.quality;

import java.util.List;
import java.util.Map;

/**
 * 전처리를 거친 shot 데이터 (Kafka sensor.clean 메시지). 중복이 제거되고, 측정 범위를 벗어났거나
 * 값이 없는 센서는 values 에서 빠지고 invalidSensors 에 들어간다.
 * shotNo·inspectionResult·serialNo 는 원본에 없으면 null 이다 (ADR-017).
 * lateArrival 은 늦게 와서(그 설비에서 이미 판정한 shot 보다 ts 가 앞섬) 실시간 판정 순서를 벗어난 shot 이다. 저장은 한다.
 * lateStatus 는 늦게 온 shot 을 어떻게 다시 판정했는지 (ADR-019):
 *   REJUDGED      — 최근 30 shot 안이라 시간 순서에 끼워 모든 규칙으로 다시 판정
 *   OUT_OF_WINDOW — 더 늦어서 순서와 무관한 규칙(개별값·불량률)만 판정
 * 늦지 않은 shot 은 null.
 */
public record CleanShot(String messageId, String lineId, String equipmentId, Long shotNo, String ts,
        Map<String, Double> values, List<String> invalidSensors, String inspectionResult, String defectType,
        String serialNo, boolean lateArrival, String lateStatus) {
}
