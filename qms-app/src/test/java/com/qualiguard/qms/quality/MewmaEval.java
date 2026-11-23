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
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.qualiguard.qms.bridge.TelemetryMessage;
import com.qualiguard.qms.quality.DetectionMetrics.Confusion;
import com.qualiguard.qms.quality.DetectionMetrics.Episode;
import com.qualiguard.qms.quality.EwmaEval.Scored;
import com.qualiguard.qms.quality.stream.SpcProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * 다변량 이상 탐지(MEWMA) 비교. 지금 SPC 규칙과 같은 데이터·같은 채점(E2)으로 비교한다.
 *
 * 실행: cd qms-app && ./gradlew mewmaEval  → ../eval/mewma_report.md
 *
 * - 입력: DetectionEval·EwmaEval 과 같은 seed 파일을 운영 SpcEngine 에 재생하고, 엔진이 걸러 낸 깨끗한 값을 {@link MewmaMonitor} 에 넣는다
 * - 한계 h: 센서 7개 χ² 분포의 상위 0.1%·0.01%·0.001% (24.3 / 29.9 / 35.3). 클수록 오경보가 줄고 늦어진다
 * - 비교: 지금 규칙 / MEWMA 단독 / 지금 규칙 + MEWMA (실제로 고를 수 있는 선택지는 '더할지')
 */
public final class MewmaEval {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** {λ, h} */
    static final double[][] CONFIGS = {{0.1, 24.32}, {0.1, 29.88}, {0.1, 35.26}, {0.2, 29.88}, {0.2, 35.26}};
    static final double[][] UNIONS = {{0.1, 29.88}, {0.1, 35.26}, {0.2, 35.26}};

    static String name(double[] c) {
        return String.format(Locale.ROOT, "λ=%.1f h=%.1f", c[0], c[1]);
    }

    public static void main(String[] args) throws IOException {
        Path runs = Path.of(args.length > 0 ? args[0] : "../eval/detection/runs");
        Path reportPath = Path.of(args.length > 1 ? args[1] : "../eval/mewma_report.md");
        List<Path> seeds;
        try (Stream<Path> s = Files.list(runs)) {
            seeds = s.filter(p -> Files.isRegularFile(p.resolve("telemetry.jsonl"))).sorted().toList();
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException("seed 폴더가 없습니다: " + runs.toAbsolutePath());
        }
        Map<String, List<Scored>> byMethod = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> topSensors = new LinkedHashMap<>();
        for (Path dir : seeds) {
            var truth = DetectionEval.readTruth(dir.resolve("ground_truth.csv"));
            Replay r = replay(dir);
            put(byMethod, "지금 규칙", EwmaEval.score(truth, r.current));
            for (double[] c : CONFIGS) {
                put(byMethod, "MEWMA " + name(c), EwmaEval.score(truth, r.mewma.get(name(c))));
            }
            for (double[] c : UNIONS) {
                put(byMethod, "지금 규칙 + MEWMA " + name(c),
                        EwmaEval.score(truth, EwmaEval.union(r.current, r.mewma.get(name(c)))));
            }
            r.top.forEach((k, v) -> v.forEach((s, n) -> topSensors.computeIfAbsent(k, x -> new TreeMap<>())
                    .merge(s, n, Integer::sum)));
        }
        String report = report(byMethod, topSensors, seeds.size());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("저장: " + reportPath.toAbsolutePath().normalize());
    }

    private static void put(Map<String, List<Scored>> m, String k, Scored s) {
        m.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
    }

    /** 재생 결과: 방식별 설비 → 경보 shot, 그리고 고장별 첫 MEWMA 경보의 최대 기여 센서 (기본 설정). */
    record Replay(Map<String, NavigableSet<Long>> current, Map<String, Map<String, NavigableSet<Long>>> mewma,
            Map<String, Map<String, Integer>> top) {
    }

    static Replay replay(Path dir) throws IOException {
        SpcEngine engine = new SpcEngine();
        Map<String, EquipmentState> states = new HashMap<>();
        Map<String, NavigableSet<Long>> current = new TreeMap<>();
        Map<String, Map<String, NavigableSet<Long>>> mewma = new LinkedHashMap<>();
        Map<String, MewmaMonitor> monitors = new LinkedHashMap<>();
        for (double[] c : CONFIGS) {
            mewma.put(name(c), new TreeMap<>());
            monitors.put(name(c), new MewmaMonitor(c[0], c[1]));
        }
        var truth = DetectionEval.readTruth(dir.resolve("ground_truth.csv"));
        Map<String, Map<String, Integer>> top = new LinkedHashMap<>();
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
                continue;
            }
            long shotNo = shot.shotNo() != null ? shot.shotNo() : (st.lastShotNo == null ? st.count : st.lastShotNo);
            for (var e : monitors.entrySet()) {
                MewmaMonitor.Alarm a = e.getValue().add(shot.equipmentId(), shotNo, r.cleanValues());
                if (a != null) {
                    mewma.get(e.getKey()).computeIfAbsent(shot.equipmentId(), k -> new TreeSet<>()).add(shotNo);
                    if (e.getKey().equals(name(UNIONS[0]))) {
                        var t = truth.getOrDefault(shot.equipmentId(), new TreeMap<>()).get(shotNo);
                        String fault = t == null || t.faults().isEmpty() ? "NONE" : String.join("|", t.faults());
                        top.computeIfAbsent(fault, k -> new TreeMap<>()).merge(a.topSensor(), 1, Integer::sum);
                    }
                }
            }
        }
        return new Replay(current, mewma, top);
    }

    static String report(Map<String, List<Scored>> byMethod, Map<String, Map<String, Integer>> top, int seeds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 다변량 이상 탐지 (MEWMA) 비교\n\n");
        sb.append("`cd qms-app && ./gradlew mewmaEval` 로 다시 만들 수 있습니다.\n\n");
        sb.append("- 데이터: 탐지 평가(E2)·EWMA 비교와 같은 시뮬레이터 seed ").append(seeds)
                .append("개. 운영 SpcEngine 이 걸러 낸 깨끗한 값을 `MewmaMonitor` 에 넣음\n");
        sb.append("- MEWMA: 기준 ").append(SpcEngine.BASELINE_SHOTS)
                .append(" shot 으로 센서 7개 평균·공분산, z_t = λ(x_t−μ) + (1−λ)z_{t−1}, T² = zᵀΣ_z⁻¹z > h 면 경보, 설비별 ")
                .append(SpcEngine.COOLDOWN_SHOTS).append(" shot 반복 억제\n");
        sb.append("- h: 센서 7개 χ² 의 상위 0.1%(24.3)·0.01%(29.9)·0.001%(35.3)\n");
        sb.append("- 채점: E2 와 같은 50 shot 창 혼동행렬·고장별 탐지 지연. seed 평균 ± 표본 표준편차\n\n");
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
                sb.append(' ').append(d.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f", DetectionMetrics.mean(d)))
                        .append(" (탐지 ").append(d.size()).append('/').append(eps.size())
                        .append(", 불량 전 ").append(early).append('/').append(eps.size()).append(") |");
            }
            sb.append('\n');
        });
        sb.append("\n## MEWMA ").append(name(UNIONS[0])).append(" 경보의 최대 기여 센서 (경보 시점의 고장별)\n\n");
        sb.append("| 경보 시점 고장 | 최대 기여 센서 (경보 수) |\n|---|---|\n");
        top.forEach((f, m) -> sb.append("| ").append(f).append(" | ").append(m.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("-")).append(" |\n"));
        sb.append("\n## 읽는 법\n\n");
        sb.append("- MEWMA 는 센서 사이의 정상 관계(공분산)에서 벗어난 작은 변화를 누적해서 봅니다. 서서히 진행되는 고장의 지연이 줄어드는지가 핵심입니다.\n");
        sb.append("- 센서별 EWMA(eval/ewma_report.md)는 센서 7개를 따로 봐서 오경보가 많았습니다. MEWMA 는 통계량이 하나라 같은 오경보 수준에서 더 민감할 수 있습니다.\n");
        sb.append("- 불량률(P 관리도)은 보지 않으므로 단독보다는 '지금 규칙 + MEWMA' 행으로 판단합니다. 경보가 늘면 LLM 비용·검토 부담도 늘어납니다.\n");
        return sb.toString();
    }

    private static String ms(List<Scored> rs, java.util.function.ToDoubleFunction<Confusion> f) {
        List<Double> v = rs.stream().map(r -> f.applyAsDouble(r.confusion())).toList();
        return String.format(Locale.ROOT, "%.3f ± %.3f", DetectionMetrics.mean(v), DetectionMetrics.stdev(v));
    }

    private MewmaEval() {
    }
}
