package com.qualiguard.qms.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** SpcEngine 단위 테스트 — Kafka·DB 없이 실행된다: ./gradlew test --tests '*SpcEngineTest' */
class SpcEngineTest {

    private static final Map<String, double[]> NOMINAL = new LinkedHashMap<>();

    static {
        NOMINAL.put("moldTemp", new double[] { 60.0, 0.6 });
        NOMINAL.put("barrelTemp", new double[] { 230.0, 0.8 });
        NOMINAL.put("injectionPressure", new double[] { 1200.0, 8.0 });
        NOMINAL.put("holdingPressure", new double[] { 600.0, 4.0 });
        NOMINAL.put("cushion", new double[] { 5.0, 0.08 });
        NOMINAL.put("cycleTime", new double[] { 30.0, 0.15 });
        NOMINAL.put("motorCurrent", new double[] { 45.0, 0.6 });
    }

    private final SpcEngine engine = new SpcEngine();
    private final Random rng = new Random(7);
    private long seq;

    private Shot shot(Map<String, Double> shift, boolean ng) {
        Map<String, Double> v = new LinkedHashMap<>();
        NOMINAL.forEach((k, p) -> v.put(k, p[0] + rng.nextGaussian() * p[1] + shift.getOrDefault(k, 0.0)));
        seq++;
        return new Shot("m-" + seq, "LINE1", "IMM-T", seq, "", v, ng, ng ? "SHORT_SHOT" : null);
    }

    private List<Anomaly> run(EquipmentState s, int shots, java.util.function.IntFunction<Map<String, Double>> shift) {
        List<Anomaly> all = new ArrayList<>();
        for (int i = 0; i < shots; i++) {
            all.addAll(engine.process(s, shot(shift.apply(i), false)).anomalies());
        }
        return all;
    }

    @Test
    void 정상_공정에서는_평균이동_경보가_없다() {
        EquipmentState s = new EquipmentState();
        List<Anomaly> alerts = run(s, 1100, i -> Map.of());
        assertTrue(alerts.stream().noneMatch(a -> a.rule().equals("XBAR")), "정상 데이터에서 X̄ 경보: " + alerts);
        assertTrue(alerts.size() <= 2, "정상 1,000 shot 오경보가 너무 많음: " + alerts.size());
    }

    @Test
    void 실린더_온도가_서서히_떨어지면_XBAR_로_탐지한다() {
        EquipmentState s = new EquipmentState();
        run(s, 100, i -> Map.of());                                        // 기준 구간
        List<Anomaly> alerts = run(s, 100, i -> Map.of("barrelTemp", -0.04 * i));
        Anomaly xbar = alerts.stream()
                .filter(a -> a.sensor().equals("barrelTemp") && a.rule().equals("XBAR"))
                .findFirst().orElseThrow();
        assertTrue(xbar.shotNo() - 100 <= 60, "탐지가 너무 늦음: " + (xbar.shotNo() - 100) + " shot");
        assertTrue(xbar.observed() < xbar.lowerLimit(), "평균이 하한 아래로 내려가야 함");
        assertTrue(xbar.sensorZScores().get("barrelTemp") < -3, "z 점수가 하락을 가리켜야 함");
    }

    @Test
    void 값이_튀면_INDIVIDUAL_로_탐지한다() {
        EquipmentState s = new EquipmentState();
        run(s, 100, i -> Map.of());
        List<Anomaly> alerts = engine.process(s, shot(Map.of("injectionPressure", 250.0), false)).anomalies();
        assertTrue(alerts.stream().anyMatch(a -> a.rule().equals("INDIVIDUAL") && a.sensor().equals("injectionPressure")));
    }

    @Test
    void 같은_messageId_는_한_번만_처리한다() {
        EquipmentState s = new EquipmentState();
        Shot first = shot(Map.of(), false);
        assertFalse(engine.process(s, first).duplicate());
        assertTrue(engine.process(s, first).duplicate());
        assertEquals(1, s.count);
    }

    @Test
    void 물리적으로_불가능한_값은_제외한다() {
        EquipmentState s = new EquipmentState();
        Map<String, Double> v = new LinkedHashMap<>();
        NOMINAL.forEach((k, p) -> v.put(k, p[0]));
        v.put("moldTemp", -50.0);
        var res = engine.process(s, new Shot("x", "LINE1", "IMM-T", 1, "", v, false, null));
        assertEquals(List.of("moldTemp"), res.invalidSensors());
        assertFalse(res.cleanValues().containsKey("moldTemp"));
    }

    @Test
    void 불량률이_오르면_P_CHART_로_탐지한다() {
        EquipmentState s = new EquipmentState();
        for (int i = 0; i < 100; i++) {
            engine.process(s, shot(Map.of(), false));
        }
        List<Anomaly> alerts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            alerts.addAll(engine.process(s, shot(Map.of(), i % 5 == 0)).anomalies());   // 불량률 20%
        }
        assertTrue(alerts.stream().anyMatch(a -> a.rule().equals("P_CHART")));
    }

    @Test
    void 다른_센서_목록으로도_같은_규칙을_적용한다() {
        // 공개 데이터(SECOM) 보조 검증처럼 센서 정의를 바꿔 끼워도 판정 규칙은 같아야 한다
        Map<String, SensorCatalog.Sensor> custom = new LinkedHashMap<>();
        custom.put("f7", new SensorCatalog.Sensor("f7", "센서 7", "", -Double.MAX_VALUE, Double.MAX_VALUE));
        SpcEngine other = new SpcEngine(custom);
        EquipmentState s = new EquipmentState();
        Random r = new Random(3);
        for (int i = 0; i < 100; i++) {
            other.process(s, new Shot("c-" + i, "L", "E", i, "", Map.of("f7", 10 + r.nextGaussian()), false, null));
        }
        var res = other.process(s, new Shot("c-x", "L", "E", 101, "", Map.of("f7", 30.0, "moldTemp", 60.0), false, null));
        assertTrue(res.anomalies().stream().anyMatch(a -> a.rule().equals("INDIVIDUAL") && a.sensor().equals("f7")));
        assertFalse(res.cleanValues().containsKey("moldTemp"), "목록에 없는 센서는 판정하지 않음");
    }
}
