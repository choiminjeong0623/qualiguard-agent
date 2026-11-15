package com.qualiguard.qms.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 공개 실제 데이터 보조 검증: UCI SECOM(반도체 공정 센서 590개 × 1,567건, 합격/불합격)에 같은 SPC 엔진을 적용한다.
 *
 * 실행:  ./scripts/fetch_secom.sh  →  cd qms-app && ./gradlew secomValidation
 * 결과:  화면 + data/secom/secom_report.md
 *
 * 전처리 (판정 규칙은 시뮬레이터와 같고, 센서 고르기만 한다):
 *   - 시간 순으로 정렬, 처음 100건을 기준 구간으로 사용 (엔진과 같음)
 *   - 기준 구간에서 값이 절반 넘게 비었거나 값이 하나뿐인(표준편차 0) 센서는 제외 — 기준을 잡을 수 없음
 *   - 빈 값(NaN)은 그 건에서만 판정 제외 (엔진의 측정 오류 처리와 같음)
 */
public final class SecomValidation {

    private static final int CONTEXT = 10;   // "직전 10건 안에 경보" 를 조기 경보로 본다

    record Sample(int index, LocalDateTime ts, boolean fail, double[] values) {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "../data/secom");
        List<Sample> samples = load(dir);
        int features = samples.get(0).values().length;

        // ── 센서 고르기 (기준 구간 100건) ──
        Map<String, SensorCatalog.Sensor> sensors = new LinkedHashMap<>();
        int droppedNaN = 0;
        int droppedConst = 0;
        for (int f = 0; f < features; f++) {
            List<Double> base = new ArrayList<>();
            for (int i = 0; i < SpcEngine.BASELINE_SHOTS; i++) {
                double v = samples.get(i).values()[f];
                if (!Double.isNaN(v)) {
                    base.add(v);
                }
            }
            if (base.size() < SpcEngine.BASELINE_SHOTS / 2) {
                droppedNaN++;
                continue;
            }
            if (base.stream().distinct().count() <= 1) {
                droppedConst++;
                continue;
            }
            String key = "f" + f;
            sensors.put(key, new SensorCatalog.Sensor(key, "센서 " + f, "", -Double.MAX_VALUE, Double.MAX_VALUE));
        }

        // ── 같은 엔진으로 시간 순 재생 ──
        SpcEngine engine = new SpcEngine(sensors);
        EquipmentState state = new EquipmentState();
        List<List<Anomaly>> alarmsAt = new ArrayList<>();
        for (Sample s : samples) {
            Map<String, Double> values = new HashMap<>();
            for (String key : sensors.keySet()) {
                values.put(key, s.values()[Integer.parseInt(key.substring(1))]);
            }
            Shot shot = new Shot("secom-" + s.index(), "SECOM", "SECOM", s.index() + 1L, s.ts().toString(), values,
                    s.fail(), s.fail() ? "FAIL" : null);
            alarmsAt.add(engine.process(state, shot).anomalies());
        }

        String report = report(samples, alarmsAt, features, sensors.size(), droppedNaN, droppedConst);
        System.out.println(report);
        Files.writeString(dir.resolve("secom_report.md"), report + "\n", StandardCharsets.UTF_8);
        System.out.println("\n저장: " + dir.resolve("secom_report.md"));
    }

    static List<Sample> load(Path dir) throws IOException {
        List<String> data = Files.readAllLines(dir.resolve("secom.data"));
        List<String> labels = Files.readAllLines(dir.resolve("secom_labels.data"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT);
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            String[] parts = data.get(i).trim().split("\\s+");
            double[] v = new double[parts.length];
            for (int j = 0; j < parts.length; j++) {
                v[j] = parts[j].equalsIgnoreCase("NaN") ? Double.NaN : Double.parseDouble(parts[j]);
            }
            String[] lab = labels.get(i).trim().split("\\s+", 2);
            LocalDateTime ts = LocalDateTime.parse(lab[1].replace("\"", ""), fmt);
            out.add(new Sample(i, ts, "1".equals(lab[0]), v));
        }
        out.sort(Comparator.comparing(Sample::ts).thenComparing(Sample::index));
        return out;
    }

    static String report(List<Sample> samples, List<List<Anomaly>> alarmsAt, int features, int used, int droppedNaN,
            int droppedConst) {
        int start = SpcEngine.BASELINE_SHOTS;
        int n = samples.size() - start;
        int fails = 0;
        int failWith = 0;
        int passWith = 0;
        int failCtx = 0;
        int passCtx = 0;
        int alarmed = 0;
        int alarmedFail = 0;
        Map<String, Integer> byRule = new TreeMap<>();
        Set<String> alarmSensors = new HashSet<>();
        int sensorAlarms = 0;
        List<String> pChart = new ArrayList<>();
        for (int i = start; i < samples.size(); i++) {
            Sample s = samples.get(i);
            List<Anomaly> here = alarmsAt.get(i).stream().filter(a -> !"P_CHART".equals(a.rule())).toList();
            for (Anomaly a : alarmsAt.get(i)) {
                byRule.merge(a.rule(), 1, Integer::sum);
                if ("P_CHART".equals(a.rule())) {
                    pChart.add(s.ts() + " (불량률 " + Math.round(a.observed() * 1000) / 10.0 + "%)");
                } else {
                    alarmSensors.add(a.sensor());
                    sensorAlarms++;
                }
            }
            boolean ctx = false;
            for (int k = Math.max(start, i - CONTEXT + 1); k <= i && !ctx; k++) {
                ctx = alarmsAt.get(k).stream().anyMatch(a -> !"P_CHART".equals(a.rule()));
            }
            if (!here.isEmpty()) {
                alarmed++;
                if (s.fail()) {
                    alarmedFail++;
                }
            }
            if (s.fail()) {
                fails++;
                failWith += here.isEmpty() ? 0 : 1;
                failCtx += ctx ? 1 : 0;
            } else {
                passWith += here.isEmpty() ? 0 : 1;
                passCtx += ctx ? 1 : 0;
            }
        }
        int passes = n - fails;
        StringBuilder sb = new StringBuilder();
        sb.append("## UCI SECOM 보조 검증 — 같은 SPC 엔진(시뮬레이터와 같은 규칙)\n\n");
        sb.append(String.format("- 데이터: %d건(시간 순), 센서 %d개, 불합격 %d건 (%.1f%%)%n", samples.size(), features,
                samples.stream().filter(Sample::fail).count(),
                100.0 * samples.stream().filter(Sample::fail).count() / samples.size()));
        sb.append(String.format("- 사용한 센서 %d개 (기준 구간에서 절반 넘게 빈 센서 %d개, 값이 하나뿐인 센서 %d개 제외)%n", used,
                droppedNaN, droppedConst));
        sb.append(String.format("- 감시 구간: %d건 (불합격 %d, 합격 %d), 기준 구간 처음 %d건%n%n", n, fails, passes, start));
        sb.append("| 지표 | 불합격 건 | 합격 건 |\n|---|---|---|\n");
        sb.append(String.format("| 그 건에서 센서 경보 | %d/%d (%.1f%%) | %d/%d (%.1f%%) |%n", failWith, fails,
                pct(failWith, fails), passWith, passes, pct(passWith, passes)));
        sb.append(String.format("| 직전 %d건 안에 센서 경보 | %d/%d (%.1f%%) | %d/%d (%.1f%%) |%n%n", CONTEXT, failCtx,
                fails, pct(failCtx, fails), passCtx, passes, pct(passCtx, passes)));
        sb.append(String.format("- 센서 경보 %d건, 경보가 난 센서 %d개 (규칙별 %s), 100건당 %.1f건%n", sensorAlarms,
                alarmSensors.size(), byRule, 100.0 * sensorAlarms / n));
        sb.append(String.format("- 센서 경보가 난 건 %d건 중 불합격 %d건 (%.1f%%, 감시 구간 불합격 비율 %.1f%%)%n", alarmed,
                alarmedFail, pct(alarmedFail, alarmed), pct(fails, n)));
        sb.append(String.format("- p 관리도(불합격률) 경보 %d건: %s%n", pChart.size(),
                pChart.isEmpty() ? "-" : String.join(", ", pChart)));
        return sb.toString();
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    private SecomValidation() {
    }
}
