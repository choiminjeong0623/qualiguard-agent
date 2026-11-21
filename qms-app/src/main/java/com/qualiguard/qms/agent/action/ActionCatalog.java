package com.qualiguard.qms.agent.action;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 조치 목록 (표준 작업서 대신 코드로 둔 표).
 * 원인마다 "할 수 있는 조치"와 그 위험도, 되돌리는 방법을 정해 둔다. LLM 이 조치를 지어내지 않게,
 * 에이전트는 이 표에 있는 조치만 고를 수 있다.
 */
public final class ActionCatalog {

    /** 조치 위험도. LOW = 되돌리기 쉽고 제품에 영향 없음, MEDIUM = 공정 설정 변경, HIGH = 라인 정지·자재 처분. */
    public enum Risk { LOW, MEDIUM, HIGH }

    /**
     * @param code         조치 코드
     * @param name         사람이 읽는 이름
     * @param risk         위험도
     * @param targetSensor 효과를 확인할 센서 (null 이면 경보가 멈췄는지만 본다)
     * @param rollback     효과가 없을 때 되돌리는 방법
     */
    public record Action(String code, String name, Risk risk, String targetSensor, String rollback) {
    }

    public static final Action HEATER_TEMP_COMPENSATE = new Action("HEATER_TEMP_COMPENSATE",
            "실린더 히터 설정 온도 +5℃ 보정", Risk.MEDIUM, "barrelTemp", "히터 설정 온도를 원래 값으로 되돌린다");
    public static final Action COOLING_FLOW_INCREASE = new Action("COOLING_FLOW_INCREASE",
            "금형 냉각수 유량 +10%", Risk.MEDIUM, "moldTemp", "냉각수 유량을 원래 값으로 되돌린다");
    public static final Action CHECK_RING_REPLACE = new Action("CHECK_RING_REPLACE",
            "라인 정지 후 체크 링 점검·교체", Risk.HIGH, "cushion", "정비 작업이라 되돌리지 않는다");
    public static final Action SENSOR_RECALIBRATE = new Action("SENSOR_RECALIBRATE",
            "센서 점검·교정 요청", Risk.LOW, null, "요청 취소");
    public static final Action MATERIAL_LOT_HOLD = new Action("MATERIAL_LOT_HOLD",
            "원재료 로트 사용 보류", Risk.HIGH, null, "보류 해제");
    public static final Action EXTRA_INSPECTION = new Action("EXTRA_INSPECTION",
            "다음 50 shot 전수 재검사 요청", Risk.LOW, null, "요청 취소");

    private static final Map<String, Action> BY_CAUSE = Map.of(
            "HEATER_DEGRADATION", HEATER_TEMP_COMPENSATE,
            "COOLING_ISSUE", COOLING_FLOW_INCREASE,
            "CHECK_RING_WEAR", CHECK_RING_REPLACE,
            "SENSOR_FAULT", SENSOR_RECALIBRATE,
            "MATERIAL", MATERIAL_LOT_HOLD,
            "UNKNOWN", EXTRA_INSPECTION);

    private static final List<Action> ALL = List.of(HEATER_TEMP_COMPENSATE, COOLING_FLOW_INCREASE,
            CHECK_RING_REPLACE, SENSOR_RECALIBRATE, MATERIAL_LOT_HOLD, EXTRA_INSPECTION);

    private ActionCatalog() {
    }

    /** 원인에 맞는 조치. 관찰(OBSERVE)이거나 NOISE 처럼 조치할 게 없으면 null. */
    public static Action forCause(String decision, String causeCode) {
        if (decision == null || "OBSERVE".equalsIgnoreCase(decision) || causeCode == null) {
            return null;
        }
        return BY_CAUSE.get(causeCode.toUpperCase(Locale.ROOT));
    }

    public static Action byCode(String code) {
        return ALL.stream().filter(a -> a.code().equals(code)).findFirst().orElse(null);
    }
}