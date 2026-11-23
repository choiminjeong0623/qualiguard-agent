package com.qualiguard.qms.quality;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 사출성형기 센서 정의: 한글 이름, 단위, 물리적으로 가능한 값의 범위(이 범위를 벗어나면 측정 오류로 보고 제외). */
public final class SensorCatalog {

    public record Sensor(String key, String name, String unit, double min, double max) {
    }

    /** 읽기 전용 (밖에서 바꾸면 SPC 엔진과 텔레메트리 수신이 서로 다른 센서 목록을 보게 된다). */
    public static final Map<String, Sensor> SENSORS;

    static {
        Map<String, Sensor> m = new LinkedHashMap<>();
        add(m, "moldTemp", "금형 온도", "℃", 0, 200);
        add(m, "barrelTemp", "실린더 온도", "℃", 0, 400);
        add(m, "injectionPressure", "사출 압력", "bar", 0, 3000);
        add(m, "holdingPressure", "보압", "bar", 0, 2000);
        add(m, "cushion", "쿠션량", "mm", 0, 50);
        add(m, "cycleTime", "사이클 타임", "s", 1, 600);
        add(m, "motorCurrent", "모터 전류", "A", 0, 500);
        SENSORS = Collections.unmodifiableMap(m);
    }

    private static void add(Map<String, Sensor> m, String key, String name, String unit, double min, double max) {
        m.put(key, new Sensor(key, name, unit, min, max));
    }

    private SensorCatalog() {
    }
}
