package com.qualiguard.qms.bridge;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 설비가 MQTT로 보내는 shot 단위 공정 데이터.
 * 형식이 틀린 메시지는 Kafka 본 토픽에 넣지 않고 DLQ로 보낸다.
 */
public record TelemetryMessage(
        @NotBlank String messageId,
        @NotBlank String lineId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long shotNo,
        @NotBlank String ts,
        @NotEmpty Map<String, @Valid @NotNull SensorValue> sensors,
        @Valid Inspection inspection) {

    public record SensorValue(@NotNull Double value, @NotBlank String unit) {
    }

    public record Inspection(@NotNull @Pattern(regexp = "OK|NG") String result, String defectType) {
    }
}
