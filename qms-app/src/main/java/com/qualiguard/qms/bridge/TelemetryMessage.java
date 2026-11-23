package com.qualiguard.qms.bridge;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 설비가 MQTT로 보내는 shot 단위 공정 데이터.
 *
 * 실제 설비 데이터는 결측이 흔하다(ADR-017). 그래서 꼭 있어야 하는 것은 추적·순서에 쓰는
 * messageId·lineId·equipmentId·ts 뿐이고, 나머지는 비어 있어도 받아서 sensor.raw 로 보낸다.
 * - shotNo: 없을 수 있음 (있으면 양수)
 * - sensors: null·빈 맵·센서 일부 누락·value null 모두 허용 (SPC 에서 해당 센서를 invalid 로 처리)
 * - inspection: null 허용 (불량률 집계에서 제외)
 * - serialNo: 출하 전에는 고유 키가 아님 → null·중복 허용, 검증하지 않고 그대로 전달
 * - lotNo: 생산 로트 번호. 없을 수 있음(로트 관리 없는 설비). 영향 범위(어느 로트를 묶어 둘지) 계산에 쓴다
 * 필수 항목이 없거나 JSON 이 깨진 메시지만 DLQ 로 보낸다.
 */
public record TelemetryMessage(
        @NotBlank String messageId,
        @NotBlank String lineId,
        @NotBlank String equipmentId,
        @Positive Long shotNo,
        @NotBlank String ts,
        Map<String, @Valid SensorValue> sensors,
        @Valid Inspection inspection,
        String serialNo,
        String lotNo) {

    /** 로트 번호 없이 만든다 (예전 메시지 형식). */
    public TelemetryMessage(String messageId, String lineId, String equipmentId, Long shotNo, String ts,
            Map<String, SensorValue> sensors, Inspection inspection, String serialNo) {
        this(messageId, lineId, equipmentId, shotNo, ts, sensors, inspection, serialNo, null);
    }

    /** 센서 값 1개. 값·단위 모두 비어 있을 수 있다. */
    public record SensorValue(Double value, String unit) {
    }

    /** 검사 결과가 있으면 result 는 OK/NG 여야 한다. 검사 결과가 아예 없으면 inspection 자체가 null. */
    public record Inspection(@Pattern(regexp = "OK|NG") String result, String defectType) {
    }

    /** sensors 가 null 이어도 안전하게 쓰도록 빈 맵으로 바꿔 준다. */
    public Map<String, SensorValue> sensorsOrEmpty() {
        return sensors == null ? Map.of() : sensors;
    }
}
