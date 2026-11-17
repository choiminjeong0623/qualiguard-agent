package com.qualiguard.qms.quality;

import java.util.Map;

/**
 * shot 1건의 공정 데이터 (Kafka sensor.raw 메시지에서 변환).
 *
 * 실제 설비 데이터는 결측이 흔하다(ADR-017).
 * @param shotNo    설비 shot 번호. 메시지에 없으면 null
 * @param values    센서 키 → 값. 누락 센서는 키가 없거나 값이 null
 * @param ng        검사 결과가 NG 인지 (inspected 가 false 면 의미 없음)
 * @param inspected 검사 결과가 있는지. 없으면 불량률 집계에서 뺀다 (OK 로 세지 않음)
 * @param serialNo  제품 시리얼. 출하 전에는 null·중복 가능, 판정에 쓰지 않고 전달만 한다
 */
public record Shot(String messageId, String lineId, String equipmentId, Long shotNo, String ts,
        Map<String, Double> values, boolean ng, String defectType, boolean inspected, String serialNo) {

    /** 검사 결과가 있고 시리얼이 없는 shot (시험·보조 검증용). */
    public Shot(String messageId, String lineId, String equipmentId, long shotNo, String ts,
            Map<String, Double> values, boolean ng, String defectType) {
        this(messageId, lineId, equipmentId, shotNo, ts, values, ng, defectType, true, null);
    }
}
