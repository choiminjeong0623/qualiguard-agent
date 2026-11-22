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

    // ── 결측 허용 (ADR-017) ──

    @Test
    void 값이_null_이거나_빠진_센서는_invalid_로_처리한다() {
        EquipmentState s = new EquipmentState();
        Map<String, Double> v = new LinkedHashMap<>();
        NOMINAL.forEach((k, p) -> v.put(k, p[0]));
        v.put("moldTemp", null);          // 값이 비어 있음
        v.remove("cushion");              // 키 자체가 없음
        var res = engine.process(s, new Shot("n-1", "LINE1", "IMM-T", null, "", v, false, null, true, null));
        assertFalse(res.duplicate());
        assertTrue(res.invalidSensors().contains("moldTemp"));
        assertTrue(res.invalidSensors().contains("cushion"));
        assertEquals(5, res.cleanValues().size());
    }

    @Test
    void 센서가_하나도_없어도_처리된다() {
        EquipmentState s = new EquipmentState();
        var res = engine.process(s, new Shot("n-2", "LINE1", "IMM-T", 1L, "", Map.of(), false, null, false, null));
        assertEquals(NOMINAL.size(), res.invalidSensors().size());
        assertTrue(res.anomalies().isEmpty());
    }

    @Test
    void 검사_결과가_없는_shot_은_불량률에_넣지_않는다() {
        EquipmentState s = new EquipmentState();
        for (int i = 0; i < 100; i++) {
            engine.process(s, shot(Map.of(), i % 10 == 0));              // 기준 구간: 검사 100 shot, 불량 10
        }
        for (int i = 0; i < 30; i++) {                                     // 검사 결과 없는 30 shot
            Shot x = shot(Map.of(), false);
            engine.process(s, new Shot(x.messageId(), x.lineId(), x.equipmentId(), x.shotNo(), x.ts(), x.values(),
                    false, null, false, null));
        }
        assertEquals(100, s.inspectedCount);
        assertEquals(0.1, s.baselineNgRate, 1e-9);
        assertTrue(s.ngWindow.isEmpty(), "검사 안 한 shot 이 OK 로 들어감");
        assertEquals(50, s.recentDefects.size());
    }

    @Test
    void 기준_불량률은_검사한_shot_100개로_정한다() {
        EquipmentState s = new EquipmentState();
        for (int i = 0; i < 150; i++) {
            Shot x = shot(Map.of(), i < 20);                                // 처음 20 shot 불량
            boolean inspected = i % 3 != 0;                                 // 3 shot 중 1 shot 검사 결과 없음
            engine.process(s, new Shot(x.messageId(), x.lineId(), x.equipmentId(), x.shotNo(), x.ts(), x.values(),
                    inspected && x.ng(), inspected ? x.defectType() : null, inspected, null));
        }
        assertEquals(100, s.inspectedCount);
        assertTrue(s.baselineNgRate > 0 && s.baselineNgRate < 0.2, "기준 불량률: " + s.baselineNgRate);
    }

    @Test
    void shotNo_가_없는_shot_의_경보는_직전_shot_번호로_어림한다() {
        EquipmentState s = new EquipmentState();
        run(s, 100, i -> Map.of());
        Shot last = shot(Map.of(), false);
        engine.process(s, last);
        Map<String, Double> v = new LinkedHashMap<>(shot(Map.of(), false).values());
        v.put("barrelTemp", 200.0);                                         // 개별값 한계 이탈
        var res = engine.process(s, new Shot("no-shot", "LINE1", "IMM-T", null, "", v, false, null, true, "S-1"));
        Anomaly a = res.anomalies().stream().filter(x -> x.rule().equals("INDIVIDUAL")).findFirst().orElseThrow();
        assertTrue(a.shotNoEstimated());
        assertEquals(last.shotNo(), a.shotNo());
    }

    // ── 연속 결측·전송 이상 (ADR-017) ──

    private static final java.time.OffsetDateTime T0 = java.time.OffsetDateTime.parse("2026-11-17T09:00:00+09:00");

    /** seq 번째 shot 을 seq 초 시각으로 만든다 (순서 판단용 ts). */
    private Shot timed(Map<String, Double> values, long shotNo, long sec, String id) {
        return new Shot(id, "LINE1", "IMM-T", shotNo, T0.plusSeconds(sec).toString(), values, false, null, true, null);
    }

    private Map<String, Double> normal() {
        Map<String, Double> v = new LinkedHashMap<>();
        NOMINAL.forEach((k, p) -> v.put(k, p[0] + rng.nextGaussian() * p[1]));
        return v;
    }

    @Test
    void 센서가_30_shot_연속_끊겨도_기준선과_판정이_깨지지_않는다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 150; i++) {
            engine.process(s, timed(normal(), i, i, "o-" + i));
        }
        double mean = s.sensors.get("barrelTemp").mean;
        double sigma = s.sensors.get("barrelTemp").sigma;
        List<String> started = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        List<Anomaly> alerts = new ArrayList<>();
        for (int i = 151; i <= 260; i++) {
            Map<String, Double> v = normal();
            if (i <= 180) {
                v.put("barrelTemp", null);                       // 30 shot 연속 끊김
            }
            var r = engine.process(s, timed(v, i, i, "o-" + i));
            started.addAll(r.outageStarted());
            ended.addAll(r.outageEnded());
            alerts.addAll(r.anomalies());
        }
        assertEquals(List.of("barrelTemp"), started, "끊김은 한 번만 알림");
        assertEquals(List.of("barrelTemp"), ended);
        assertEquals(mean, s.sensors.get("barrelTemp").mean, 1e-12, "기준 평균이 바뀜");
        assertEquals(sigma, s.sensors.get("barrelTemp").sigma, 1e-12);
        assertTrue(alerts.stream().noneMatch(a -> a.sensor().equals("barrelTemp")), "끊김 뒤 오경보: " + alerts);
        assertEquals(0, s.sensors.get("barrelTemp").missingRun);
    }

    @Test
    void 끊김이_짧으면_알리지_않는다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 10; i++) {
            Map<String, Double> v = normal();
            if (i <= SpcEngine.OUTAGE_SHOTS - 1) {
                v.put("cushion", null);
            }
            var r = engine.process(s, timed(v, i, i, "s-" + i));
            assertTrue(r.outageStarted().isEmpty() && r.outageEnded().isEmpty());
        }
    }

    @Test
    void shot_이_통째로_빠져_shotNo_가_건너뛰어도_정상_판정한다() {
        EquipmentState s = new EquipmentState();
        long shotNo = 0;
        List<Anomaly> alerts = new ArrayList<>();
        for (int i = 1; i <= 600; i++) {
            shotNo += (i % 37 == 0) ? 4 : 1;                    // 가끔 3 shot 씩 누락
            alerts.addAll(engine.process(s, timed(normal(), shotNo, shotNo, "g-" + i)).anomalies());
        }
        assertEquals(600, s.count);
        assertEquals(0, s.lateCount);
        assertTrue(s.sensors.get("moldTemp").ready);
        assertTrue(alerts.size() <= 2, "누락만으로 오경보: " + alerts);
    }

    @Test
    void 같은_messageId_는_한_번만_반영한다() {
        EquipmentState s = new EquipmentState();
        Shot x = timed(normal(), 1, 1, "dup-1");
        assertFalse(engine.process(s, x).duplicate());
        long count = s.count;
        int n = s.sensors.get("moldTemp").n;
        var again = engine.process(s, x);
        assertTrue(again.duplicate());
        assertEquals(count, s.count);
        assertEquals(n, s.sensors.get("moldTemp").n);
    }

    @Test
    void 늦게_온_shot_은_실시간_판정_상태를_건드리지_않고_따로_판정한다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 120; i++) {
            engine.process(s, timed(normal(), i, i, "l-" + i));
        }
        long count = s.count;
        int subgroup = s.sensors.get("moldTemp").subgroup.size();
        List<Double> recent = List.copyOf(s.sensors.get("moldTemp").recent);
        Map<String, Double> v = normal();
        v.put("moldTemp", 90.0);                                 // 개별값 한계(±4σ)를 크게 벗어난 값
        var late = engine.process(s, timed(v, 115, 115, "l-late"));   // 120 번째 뒤에 115 번째가 옴
        assertTrue(late.late());
        assertEquals(SpcEngine.LATE_REJUDGED, late.lateStatus());
        assertEquals(List.of("INDIVIDUAL|moldTemp"),
                late.anomalies().stream().map(a -> a.rule() + "|" + a.sensor()).toList());
        Anomaly a = late.anomalies().get(0);
        assertTrue(a.lateRejudged());
        assertEquals(115, a.shotNo());                           // 경보 위치는 늦게 온 shot 자신
        assertTrue(a.message().startsWith("[늦게 도착한 shot 재판정]"));
        assertEquals(90.0, late.cleanValues().get("moldTemp"));  // 저장용 값은 그대로
        // 실시간 상태는 그대로
        assertEquals(count, s.count);
        assertEquals(subgroup, s.sensors.get("moldTemp").subgroup.size());
        assertEquals(recent, s.sensors.get("moldTemp").recent);
        assertEquals(120L, s.lastShotNo);
        assertEquals(1, s.lateCount);
        // 다음 정상 shot 은 그대로 판정
        assertFalse(engine.process(s, timed(normal(), 121, 121, "l-121")).late());
        // 늦게 온 shot 의 재전송도 중복으로 거른다
        assertTrue(engine.process(s, timed(v, 115, 115, "l-late")).duplicate());
    }

    @Test
    void 늦게_온_shot_을_시간_순서에_끼우면_실시간에선_놓친_평균_이동을_잡는다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 120; i++) {                         // 기준 100 + 부분군 4개(101~120)
            engine.process(s, timed(normal(), i, i, "x-" + i));
        }
        EquipmentState.SensorState ms = s.sensors.get("moldTemp");
        double lim = SpcEngine.XBAR_K * ms.sigma / Math.sqrt(SpcEngine.SUBGROUP_SIZE);
        double up = ms.mean + 1.5 * lim;                         // 부분군 평균 한계를 넘는 높이 (개별값 한계 4σ 안)
        List<Anomaly> realtime = new ArrayList<>();
        // 125 번째가 늦게 와서 실시간 부분군은 [121,122,123,124,126] [127,128,129,130,131] 로 묶인다
        for (int i = 121; i <= 131; i++) {
            if (i == 125) {
                continue;
            }
            Map<String, Double> v = normal();
            v.put("moldTemp", i == 131 ? ms.mean - 6 * lim : up);   // 131 이 둘째 부분군 평균을 끌어내려 '연속 2회'가 끊김
            realtime.addAll(engine.process(s, timed(v, i, i, "x-" + i)).anomalies());
        }
        assertTrue(realtime.stream().noneMatch(a -> a.rule().equals("XBAR")), "실시간에서 이미 잡힘: " + realtime);

        Map<String, Double> v = normal();
        v.put("moldTemp", up);
        var late = engine.process(s, timed(v, 125, 125, "x-125"));
        assertEquals(SpcEngine.LATE_REJUDGED, late.lateStatus());
        // 시간 순서로는 [121..125] 와 [126..130] 이 연속으로 한계를 넘는다
        assertTrue(late.anomalies().stream().anyMatch(a -> a.rule().equals("XBAR") && a.sensor().equals("moldTemp")
                && a.lateRejudged()), "재판정 경보 없음: " + late.anomalies());
    }

    @Test
    void 보관한_30_shot_보다_더_늦으면_순서와_무관한_규칙만_본다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 160; i++) {
            engine.process(s, timed(normal(), i, i, "w-" + i));
        }
        Map<String, Double> v = normal();
        v.put("cushion", s.sensors.get("cushion").mean + 6 * s.sensors.get("cushion").sigma);
        var late = engine.process(s, timed(v, 50, 50, "w-late"));  // 110 shot 늦게 옴
        assertEquals(SpcEngine.LATE_OUT_OF_WINDOW, late.lateStatus());
        assertEquals(List.of("INDIVIDUAL"), late.anomalies().stream().map(Anomaly::rule).toList());
    }

    @Test
    void 실시간에서_이미_낸_경보는_재판정에서_다시_내지_않는다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 130; i++) {
            engine.process(s, timed(normal(), i, i, "d-" + i));
        }
        double high = s.sensors.get("moldTemp").mean + 8 * s.sensors.get("moldTemp").sigma;
        Map<String, Double> v = normal();
        v.put("moldTemp", high);
        assertEquals(1, engine.process(s, timed(v, 132, 132, "d-132")).anomalies().size());   // 실시간 경보
        Map<String, Double> w = normal();
        w.put("moldTemp", high);
        var late = engine.process(s, timed(w, 131, 131, "d-131"));
        assertTrue(late.late());
        assertTrue(late.anomalies().isEmpty(), "같은 규칙·센서 경보가 반복됨: " + late.anomalies());
    }

    @Test
    void 이전_버전_상태처럼_시각_목록이_없으면_범위_밖으로_본다() {
        EquipmentState s = new EquipmentState();
        for (int i = 1; i <= 120; i++) {
            engine.process(s, timed(normal(), i, i, "o-" + i));
        }
        s.sensors.values().forEach(ss -> ss.recentTs.clear());   // 시각 목록이 없던 상태를 흉내
        assertEquals(SpcEngine.LATE_OUT_OF_WINDOW, engine.process(s, timed(normal(), 110, 110, "o-late")).lateStatus());
        engine.process(s, timed(normal(), 121, 121, "o-121"));   // 새 shot 부터 시각이 다시 쌓인다
        assertEquals(s.sensors.get("moldTemp").recent.size(), s.sensors.get("moldTemp").recentTs.size());
    }

    @Test
    void 설비_카운터가_리셋돼도_ts_가_뒤면_늦은_shot_이_아니다() {
        EquipmentState s = new EquipmentState();
        engine.process(s, timed(normal(), 500, 10, "r-1"));
        assertFalse(engine.process(s, timed(normal(), 1, 11, "r-2")).late());
    }
}
