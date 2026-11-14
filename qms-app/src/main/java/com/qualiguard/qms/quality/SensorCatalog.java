package com.qualiguard.qms.quality;

import java.util.LinkedHashMap;
import java.util.Map;

/** 사출성형기 센서 정의: 한글 이름, 단위, 물리적으로 가능한 값의 범위(이 범위를 벗어나면 측정 오류로 보고 제외). */
public final class SensorCatalog {

    public record Sensor(String key, String name, String unit, double min, double max) {
    }

    public static final Map<String, Sensor> SENSORS = new LinkedHashMap<>();

    static {
        add("moldTemp", "금형 온도", "℃", 0, 200);
        add("barrelTemp", "실린더 온도", "℃", 0, 400);
        add("injectionPressure", "사출 압력", "bar", 0, 3000);
        add("holdingPressure", "보압", "bar", 0, 2000);
        add("cushion", "쿠션량", "mm", 0, 50);
        add("cycleTime", "사이클 타임", "s", 1, 600);
        add("motorCurrent", "모터 전류", "A", 0, 500);
    }

    private static void add(String key, String name, String unit, double min, double max) {
        SENSORS.put(key, new Sensor(key, name, unit, min, max));
    }

    private SensorCatalog() {
    }
}
