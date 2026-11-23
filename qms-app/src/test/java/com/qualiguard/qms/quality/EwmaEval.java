package com.qualiguard.qms.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.qualiguard.qms.bridge.TelemetryMessage;
import com.qualiguard.qms.quality.DetectionMetrics.Confusion;
import com.qualiguard.qms.quality.DetectionMetrics.Episode;
import com.qualiguard.qms.quality.DetectionMetrics.Truth;
import com.qualiguard.qms.quality.stream.SpcProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * EWMA 관리도 비교 (로드맵 A4). 운영 코드는 바꾸지 않고, 지금 규칙과 EWMA 를 같은 데이터·같은 채점으로 비교한다.
 *
 * 실행: cd qms-app && ./gradlew ewmaEval  → ../eval/ewma_report.md
 *
 * - 같은 입력: DetectionEval 과 같은 seed 파일을 운영 SpcEngine 에 재생하고, 엔진이 걸러 낸 깨끗한 값
 *   (중복·범위 밖 값 제외, 늦게 온 shot 제외)을 센서별 EWMA 에 넣는다.
 * - EWMA: 기준 100 shot 으로 μ·σ, z_t = λx_t + (1−λ)z_{t−1} (z_0 = μ),
 *   한계 μ ± L·σ·√(λ/(2−λ)·(1−(1−λ)^{2t})). 넘으면 경보, 센서별로 운영과 같은 100 shot 반복 억제.
 * - 비교 대상: 지금 규칙 / EWMA 단독(CONFIGS) / 지금 규칙 + EWMA(UNIONS). EWMA 는 센서 값만 보므로
 *   불량률(P 관리도)이 없다 → 실제로 고를 수 있는 선택지는 "지금 규칙에 EWMA 를 더할지" 다.
 * - 채점은 DetectionMetrics 그대로 (50 shot 창 혼동행렬, 고장 구간별 탐지 지연, 첫 불량 전 탐지).
 */
public final class EwmaEval {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 비교할 EWMA 설정 {λ, L}. L 3.0 이 교과서 기본값, 3.5·4.0 은 오경보를 줄이려 한계를 넓힌 것. */
    static final double[][] CONFIGS = {{0.1, 3.0}, {0.2, 3.0}, {0.3, 3.0}, {0.2, 3.5}, {0.2, 4.0}};
    /** "지금 규칙 + EWMA" 로 함께 볼 설정 */
    static final double[][] UNIONS = {{0.2, 3.0}, {0.2, 4.0}};

    /** 센서 1개의 EWMA 상태. */
    static final class Ewma {
        final double lambda;
        final double l;
        int n;
        double sum;
        double sumSq;
        double mu;
        double sigma;
        double z;
        long t;

        Ewma(double lambda) {
            this(lambda, 3.0);
        }

        Ewma(double lambda, double l) {
            this.lambda = lambda;
            this.l = l;
        }

        /** 값 하나를 넣고 관리 한계를 벗어났는지 돌려준다 (기준 구간에서는 항상 false). */
        boolean add(double x) {
            if (n < SpcEngine.BASELINE_SHOTS) {
                n++;
                sum += x;
                sumSq += x * x;
                if (n == SpcEngine.BASELINE_SHOTS) {
                    mu = sum / n;
                    sigma = Math.max(Math.sqrt(Math.max((sumSq - n * mu * mu) / (n - 1), 0)), 1e-6);
                    z = mu;
                }
                return false;
            }
            t++;
            z = lambda * x + (1 - lambda) * z;
            double width = l * sigma * Math.sqrt(lambda / (2 - lambda) * (1 - Math.pow(1 - lambda, 2 * t)));
            return Math.abs(z - mu) > width;
        }
    }

    /** seed 하나에서 방식별 설비 → 경보 shot. */
    record Alarms(Map<String, NavigableSet<Long>> current, Map<String, Map<String, NavigableSet<Long>>> ewma) {
    }

    record Scored(Confusion confusion, List<Episode> episodes) {
    }

    public static void main(String[] args) throws IOException {
        Path runs = Path.of(args.length > 0 ? args[0] : "../eval/detection/runs");
        Path reportPath = Path.of(args.length > 1 ? args[1] : "../eval/ewma_report.md");
        List<Path> seeds;
        try (Stream<Path> s = Files.list(runs)) {
            seeds = s.filter(p -> Files.isRegularFile(p.resolve("telemetry.jsonl"))).sorted().toList();
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException("seed 폴더가 없습니다: " + runs.toAbsolutePath());
        }
        Map<String, List<Scored>> byMethod = new LinkedHashMap<>();
        for (Path dir : seeds) {
            var truth = DetectionEval.readTruth(dir.resolve("ground_truth.csv"));
            Alarms a = replay(dir);
            put(byMethod, "지금 규칙", score(truth, a.current()));
            for (double[] c : CONFIGS) {
                put(byMethod, "EWMA " + name(c), score(truth, a.ewma().get(name(c))));
            }
            for (double[] c : UNIONS) {
                put(byMethod, "지금 규칙 + EWMA " + name(c), score(truth, union(a.current(), a.ewma().get(name(c)))));
            }
        }
        String report = report(byMethod, seeds.size());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("저장: " + reportPath.toAbsolutePath().normalize());
    }

    static String name(double[] c) {
        return "λ=" + c[0] + " L=" + c[1];
    }

    private static void put(Map<String, List<Scored>> m, String k, Scored s) {
        m.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
    }

    static Alarms replay(Path dir) throws IOException {
        SpcEngine engine = new SpcEngine();
        Map<String, EquipmentState> states = new HashMap<>();
        Map<String, NavigableSet<Long>> current = new TreeMap<>();
        Map<String, Map<String, NavigableSet<Long>>> ewmaAlarms = new LinkedHashMap<>();
        Map<String, Ewma> ewmas = new HashMap<>();          // 설정|설비|센서
        Map<String, Long> lastAlarm = new HashMap<>();      // 설정|설비|센서 → 마지막 경보 count
        for (double[] c : CONFIGS) {
            ewmaAlarms.put(name(c), new TreeMap<>());
        }
        for (String line : Files.readAllLines(dir.resolve("telemetry.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Shot shot = SpcProcessor.toShot(JSON.readValue(line, TelemetryMessage.class));
            EquipmentState st = states.computeIfAbsent(shot.equipmentId(), k -> new EquipmentState());
            SpcEngine.Result r = engine.process(st, shot);
            for (Anomaly an : r.anomalies()) {
                current.computeIfAbsent(an.equipmentId(), k -> new TreeSet<>()).add(an.shotNo());
            }
            if (r.duplicate() || r.late()) {
                continue;                                   // EWMA 는 순서가 중요해 늦게 온 값은 넣지 않는다
            }
            long shotNo = shot.shotNo() != null ? shot.shotNo() : (st.lastShotNo == null ? st.count : st.lastShotNo);
            for (var v : r.cleanValues().entrySet()) {
                for (double[] c : CONFIGS) {
                    String key = name(c) + "|" + shot.equipmentId() + "|" + v.getKey();
                    boolean out = ewmas.computeIfAbsent(key, k -> new Ewma(c[0], c[1])).add(v.getValue());
                    Long last = lastAlarm.get(key);
                    if (out && (last == null || st.count - last >= SpcEngine.COOLDOWN_SHOTS)) {
                        lastAlarm.put(key, st.count);
                        ewmaAlarms.get(name(c)).computeIfAbsent(shot.equipmentId(), k -> new TreeSet<>()).add(shotNo);
                    }
                }
            }
        }
        return new Alarms(current, ewmaAlarms);
    }

    static Map<String, NavigableSet<Long>> union(Map<String, NavigableSet<Long>> a, Map<String, NavigableSet<Long>> b) {
        Map<String, NavigableSet<Long>> u = new TreeMap<>();
        a.forEach((k, v) -> u.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        b.forEach((k, v) -> u.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        return u;
    }

    static Scored score(Map<String, NavigableMap<Long, Truth>> truth, Map<String, NavigableSet<Long>> alarms) {
        Confusion total = new Confusion(0, 0, 0, 0);
        List<Episode> eps = new ArrayList<>();
        for (var e : truth.entrySet()) {
            NavigableSet<Long> shots = alarms.getOrDefault(e.getKey(), DetectionMetrics.alarmSet());
            total = total.plus(DetectionMetrics.confusion(e.getValue(), shots, SpcEngine.BASELINE_SHOTS));
            eps.addAll(DetectionMetrics.episodes(e.getKey(), e.getValue(), shots));
        }
        return new Scored(total, eps);
    }

    static String report(Map<String, List<Scored>> byMethod, int seeds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EWMA 관리도 비교 (로드맵 A4)\n\n");
        sb.append("`cd qms-app && ./gradlew ewmaEval` 로 다시 만들 수 있습니다. 운영 코드는 바꾸지 않았습니다.\n\n");
        sb.append("- 데이터: 탐지 평가(E2)와 같은 시뮬레이터 seed ").append(seeds)
                .append("개. 운영 SpcEngine 이 걸러 낸 깨끗한 값을 센서별 EWMA 에 넣음 (늦게 온 shot 제외)\n");
        sb.append("- EWMA: 기준 ").append(SpcEngine.BASELINE_SHOTS).append(" shot 으로 μ·σ, 한계 μ ± L·σ_z")
                .append(", 시간에 따라 넓어지는 정확한 한계, 센서별 ").append(SpcEngine.COOLDOWN_SHOTS)
                .append(" shot 반복 억제 (운영과 같음)\n");
        sb.append("- 채점: E2 와 같은 50 shot 창 혼동행렬·고장별 탐지 지연. 수치는 seed 평균 ± 표본 표준편차\n\n");

        sb.append("## 창 단위 성능\n\n| 방식 | 정밀도 | 재현율 | F1 | 오경보율(FPR) |\n|---|---:|---:|---:|---:|\n");
        byMethod.forEach((m, rs) -> sb.append("| ").append(m).append(" | ").append(ms(rs, Confusion::precision))
                .append(" | ").append(ms(rs, Confusion::recall)).append(" | ").append(ms(rs, Confusion::f1))
                .append(" | ").append(ms(rs, Confusion::fpr)).append(" |\n"));

        sb.append("\n## 고장별 탐지 지연 (shot, 탐지된 구간 평균) · 첫 불량 전 탐지\n\n| 방식 |");
        List<String> faults = byMethod.values().iterator().next().stream().flatMap(s -> s.episodes().stream())
                .map(e -> e.fault() + " (" + e.equipmentId() + ")").distinct().sorted().toList();
        faults.forEach(f -> sb.append(' ').append(f).append(" |"));
        sb.append("\n|---|");
        faults.forEach(f -> sb.append("---:|"));
        sb.append('\n');
        byMethod.forEach((m, rs) -> {
            sb.append("| ").append(m).append(" |");
            for (String f : faults) {
                List<Episode> eps = rs.stream().flatMap(s -> s.episodes().stream())
                        .filter(e -> (e.fault() + " (" + e.equipmentId() + ")").equals(f)).toList();
                List<Double> d = eps.stream().filter(Episode::detected).map(e -> (double) e.delay()).toList();
                long early = eps.stream().filter(Episode::beforeFirstDefect).count();
                sb.append(' ').append(d.isEmpty() ? "-" : fmt1(DetectionMetrics.mean(d)))
                        .append(" (탐지 ").append(d.size()).append('/').append(eps.size())
                        .append(", 불량 전 ").append(early).append('/').append(eps.size()).append(") |");
            }
            sb.append('\n');
        });
        sb.append("\n## 읽는 법\n\n");
        sb.append("- EWMA 는 작은 평균 이동을 누적해서 보므로, 서서히 진행되는 고장(히터 열화·냉각 막힘·체크 링 마모)에서 ")
                .append("지연이 줄어드는지가 핵심입니다. 대신 센서 7개를 각각 보므로 정상 구간 오경보(FPR)가 늘 수 있습니다.\n");
        sb.append("- EWMA 단독은 불량률(P 관리도)을 보지 않아 공정 값이 멀쩡한 불량 증가는 놓칩니다. ")
                .append("채택 여부는 '지금 규칙 + EWMA' 행을 지금 규칙과 비교해 판단합니다.\n");
        sb.append("- 채택하면 경보가 LLM 분석으로 이어지므로, 오경보 증가는 LLM 비용·검토 부담 증가와 같습니다.\n");
        return sb.toString();
    }

    private static String ms(List<Scored> rs, java.util.function.ToDoubleFunction<Confusion> f) {
        List<Double> v = rs.stream().map(r -> f.applyAsDouble(r.confusion())).toList();
        return String.format(Locale.ROOT, "%.3f ± %.3f", DetectionMetrics.mean(v), DetectionMetrics.stdev(v));
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private EwmaEval() {
    }
}
