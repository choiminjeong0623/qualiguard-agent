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
import java.util.stream.Stream;

import com.qualiguard.qms.bridge.TelemetryMessage;
import com.qualiguard.qms.quality.DetectionMetrics.Confusion;
import com.qualiguard.qms.quality.DetectionMetrics.Episode;
import com.qualiguard.qms.quality.DetectionMetrics.Truth;
import com.qualiguard.qms.quality.stream.SpcProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * 이상 탐지 평가기 (로드맵 E2). 에이전트(LLM) 없이 SPC 엔진만 채점한다.
 *
 * 실행:  cd qms-app && ./gradlew detectionEval
 *        (시뮬레이터로 seed 1~5 파일을 만든 뒤 이 클래스를 돌려 ../eval/detection_report.md 를 쓴다)
 *
 * 흐름: eval/detection/runs/seed-N/telemetry.jsonl (시뮬레이터가 보낸 순서 그대로, 결측·중복·늦은 메시지 포함)
 *       → 운영과 같은 변환(SpcProcessor.toShot) → 운영과 같은 SpcEngine 을 설비별로 시간 순 재생 → 경보 shot 목록
 *       → ground_truth.csv(shot 마다 진행 중인 고장) 와 비교 → DetectionMetrics 로 채점 → seed 평균 ± 표준편차
 */
public final class DetectionEval {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** seed 1개 채점 결과. */
    record SeedResult(String seed, Confusion confusion, List<Episode> episodes, Map<String, int[]> rules,
            int messages, int duplicates, int late) {
    }

    public static void main(String[] args) throws IOException {
        Path runs = Path.of(args.length > 0 ? args[0] : "../eval/detection/runs");
        Path reportPath = Path.of(args.length > 1 ? args[1] : "../eval/detection_report.md");
        List<Path> seeds;
        try (Stream<Path> s = Files.list(runs)) {
            seeds = s.filter(p -> Files.isRegularFile(p.resolve("telemetry.jsonl"))).sorted().toList();
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException("평가할 seed 폴더가 없습니다: " + runs.toAbsolutePath()
                    + " (./gradlew detectionEval 이 시뮬레이터를 먼저 돌립니다)");
        }
        List<SeedResult> results = new ArrayList<>();
        for (Path dir : seeds) {
            SeedResult r = evaluate(dir);
            results.add(r);
            System.out.printf(Locale.ROOT, "%s: P %.3f R %.3f F1 %.3f FPR %.3f%n", r.seed(), r.confusion().precision(),
                    r.confusion().recall(), r.confusion().f1(), r.confusion().fpr());
        }
        String meta = Files.isRegularFile(seeds.get(0).resolve("ground_truth.meta.json"))
                ? Files.readString(seeds.get(0).resolve("ground_truth.meta.json"))
                : null;
        String report = report(results, meta);
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println("\n저장: " + reportPath.toAbsolutePath().normalize());
    }

    /** seed 폴더 하나: 메시지를 재생하고 정답과 비교한다. */
    static SeedResult evaluate(Path dir) throws IOException {
        Map<String, NavigableMap<Long, Truth>> truth = readTruth(dir.resolve("ground_truth.csv"));
        SpcEngine engine = new SpcEngine();
        Map<String, EquipmentState> states = new HashMap<>();
        Map<String, NavigableSet<Long>> alarms = new TreeMap<>();
        List<Anomaly> all = new ArrayList<>();
        int messages = 0;
        int duplicates = 0;
        int late = 0;
        for (String line : Files.readAllLines(dir.resolve("telemetry.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            messages++;
            Shot shot = SpcProcessor.toShot(JSON.readValue(line, TelemetryMessage.class));
            SpcEngine.Result r = engine.process(states.computeIfAbsent(shot.equipmentId(), k -> new EquipmentState()),
                    shot);
            duplicates += r.duplicate() ? 1 : 0;
            late += r.late() ? 1 : 0;
            for (Anomaly a : r.anomalies()) {
                alarms.computeIfAbsent(a.equipmentId(), k -> DetectionMetrics.alarmSet()).add(a.shotNo());
                all.add(a);
            }
        }
        Confusion total = new Confusion(0, 0, 0, 0);
        List<Episode> episodes = new ArrayList<>();
        for (var e : truth.entrySet()) {
            NavigableSet<Long> shots = alarms.getOrDefault(e.getKey(), DetectionMetrics.alarmSet());
            total = total.plus(DetectionMetrics.confusion(e.getValue(), shots, SpcEngine.BASELINE_SHOTS));
            episodes.addAll(DetectionMetrics.episodes(e.getKey(), e.getValue(), shots));
        }
        // 규칙별 경보 수: {전체, 고장 중인 shot 에서 난 것}
        Map<String, int[]> rules = new TreeMap<>();
        for (Anomaly a : all) {
            int[] c = rules.computeIfAbsent(a.rule(), k -> new int[2]);
            c[0]++;
            Truth t = truth.getOrDefault(a.equipmentId(), DetectionMetrics.truthMap()).get(a.shotNo());
            c[1] += t != null && t.faulty() ? 1 : 0;
        }
        return new SeedResult(dir.getFileName().toString(), total, episodes, rules, messages, duplicates, late);
    }

    /** ground_truth.csv → 설비별 shot 번호 → 정답. 쉼표가 들어가는 칸이 없는 형식이라 단순 분리로 읽는다. */
    static Map<String, NavigableMap<Long, Truth>> readTruth(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<String> header = List.of(lines.get(0).split(",", -1));
        int eq = header.indexOf("equipmentId");
        int shot = header.indexOf("shotNo");
        int fault = header.indexOf("activeFault");
        int insp = header.indexOf("inspection");
        Map<String, NavigableMap<Long, Truth>> out = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(",", -1);
            List<String> faults = "NONE".equals(c[fault]) || c[fault].isBlank() ? List.of()
                    : List.of(c[fault].split("\\|"));
            out.computeIfAbsent(c[eq], k -> DetectionMetrics.truthMap())
                    .put(Long.parseLong(c[shot]), new Truth(faults, "NG".equals(c[insp])));
        }
        return out;
    }

    static String report(List<SeedResult> results, String simulatorMeta) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 이상 탐지 평가 (DetectionEval)\n\n");
        sb.append("`cd qms-app && ./gradlew detectionEval` 로 다시 만들 수 있습니다. 에이전트(LLM) 없이 SPC 엔진만 채점합니다.\n\n");
        sb.append("- 데이터: 시뮬레이터 `--out` 파일 출력, seed ").append(results.size()).append("개 (")
                .append(String.join(", ", results.stream().map(SeedResult::seed).toList()))
                .append("). 결측·중복·늦은 메시지가 섞인 그대로 재생\n");
        sb.append("- 엔진: 운영과 같은 `SpcEngine` (기준 ").append(SpcEngine.BASELINE_SHOTS)
                .append(" shot, 개별값 ±").append(fmt1(SpcEngine.INDIVIDUAL_K)).append("σ, X̄ ±")
                .append(fmt1(SpcEngine.XBAR_K)).append("σ 연속 ").append(SpcEngine.CONFIRM_RUNS)
                .append("회, S 관리도, P 관리도 ").append(SpcEngine.P_WINDOW).append(" shot)\n");
        sb.append("- 채점 단위: 기준 구간 뒤 ").append(DetectionMetrics.WINDOW)
                .append(" shot 창. 창 안에 고장 shot 이 있으면 고장 창, 경보가 있으면 경보 창\n");
        sb.append("- 수치는 seed 평균 ± 표본 표준편차\n\n");

        sb.append("## 창 단위 혼동행렬\n\n");
        sb.append("| seed | TP | FP | FN | TN | 정밀도 | 재현율 | F1 | 오경보율(FPR) | 놓침률(FNR) |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (SeedResult r : results) {
            Confusion c = r.confusion();
            sb.append("| ").append(r.seed()).append(" | ").append(c.tp()).append(" | ").append(c.fp()).append(" | ")
                    .append(c.fn()).append(" | ").append(c.tn()).append(" | ").append(fmt(c.precision()))
                    .append(" | ").append(fmt(c.recall())).append(" | ").append(fmt(c.f1())).append(" | ")
                    .append(fmt(c.fpr())).append(" | ").append(fmt(c.fnr())).append(" |\n");
        }
        sb.append("| **평균 ± 표준편차** | | | | | ").append(ms(results, Confusion::precision)).append(" | ")
                .append(ms(results, Confusion::recall)).append(" | ").append(ms(results, Confusion::f1))
                .append(" | ").append(ms(results, Confusion::fpr)).append(" | ").append(ms(results, Confusion::fnr))
                .append(" |\n\n");

        sb.append("## 고장별 탐지 지연\n\n");
        sb.append("탐지 지연 = 고장 구간 안 첫 경보 shot − 고장 시작 shot. 놓친 구간은 지연 평균에서 빼고 탐지율에 반영합니다.\n\n");
        sb.append("| 고장 | 설비 | 탐지율 | 지연(shot) 평균 ± 표준편차 | 첫 불량 전 탐지 |\n");
        sb.append("|---|---|---:|---:|---:|\n");
        Map<String, List<Episode>> byFault = new LinkedHashMap<>();
        results.stream().flatMap(r -> r.episodes().stream())
                .sorted((a, b) -> Long.compare(a.start(), b.start()))
                .forEach(e -> byFault.computeIfAbsent(e.fault() + "|" + e.equipmentId(), k -> new ArrayList<>()).add(e));
        for (var e : byFault.entrySet()) {
            List<Episode> eps = e.getValue();
            long detected = eps.stream().filter(Episode::detected).count();
            long early = eps.stream().filter(Episode::beforeFirstDefect).count();
            List<Double> delays = eps.stream().filter(Episode::detected).map(x -> (double) x.delay()).toList();
            String[] k = e.getKey().split("\\|");
            sb.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(detected).append('/')
                    .append(eps.size()).append(" | ")
                    .append(delays.isEmpty() ? "-" : fmt1(DetectionMetrics.mean(delays)) + " ± "
                            + fmt1(DetectionMetrics.stdev(delays)))
                    .append(" | ").append(early).append('/').append(eps.size()).append(" |\n");
        }

        sb.append("\n## 규칙별 경보 수 (seed 합계)\n\n");
        sb.append("| 규칙 | 경보 | 고장 중 shot | 정상 shot |\n|---|---:|---:|---:|\n");
        Map<String, int[]> rules = new TreeMap<>();
        for (SeedResult r : results) {
            r.rules().forEach((rule, c) -> {
                int[] t = rules.computeIfAbsent(rule, x -> new int[2]);
                t[0] += c[0];
                t[1] += c[1];
            });
        }
        rules.forEach((rule, c) -> sb.append("| ").append(rule).append(" | ").append(c[0]).append(" | ")
                .append(c[1]).append(" | ").append(c[0] - c[1]).append(" |\n"));

        sb.append("\n## 재생한 메시지\n\n| seed | 메시지 | 중복 무시 | 늦게 온 shot 재판정 |\n|---|---:|---:|---:|\n");
        for (SeedResult r : results) {
            sb.append("| ").append(r.seed()).append(" | ").append(r.messages()).append(" | ").append(r.duplicates())
                    .append(" | ").append(r.late()).append(" |\n");
        }
        sb.append("\n## 읽는 법과 한계\n\n");
        sb.append("- 고장은 서서히 진행되므로 고장 초반 창은 센서 변화가 작아 놓치는 것이 정상입니다. 재현율보다 탐지 지연과 ")
                .append("'첫 불량 전 탐지' 를 함께 봐야 합니다.\n");
        sb.append("- 고장이 끝난 직후 창의 경보는 오경보(FP)로 셉니다. 실제로는 고장의 여파일 수 있어 정밀도를 조금 낮게 잡는 방식입니다.\n");
        sb.append("- '첫 불량 전 탐지' 의 첫 불량에는 고장과 무관한 평상시 불량(약 0.6%)이 섞일 수 있습니다. 그래서 이 열은 조금 엄격하게 나옵니다.\n");
        sb.append("- 시뮬레이터 데이터이므로 실제 설비 성능을 뜻하지 않습니다. 실제 공개 데이터 확인은 SECOM 보조 검증(`./gradlew secomValidation`)을 봅니다.\n");
        if (simulatorMeta != null) {
            // 만든 시각(createdAt)은 빼서, 같은 코드로 다시 돌리면 보고서가 한 글자도 안 바뀌게 한다
            String stable = simulatorMeta.strip().replaceAll("(?m)^\\s*\"createdAt\":.*\\R", "");
            sb.append("\n## 시뮬레이터 조건 (첫 seed 의 ground_truth.meta.json)\n\n```json\n").append(stable)
                    .append("\n```\n");
        }
        return sb.toString();
    }

    private static String ms(List<SeedResult> results, java.util.function.ToDoubleFunction<Confusion> f) {
        List<Double> v = results.stream().map(r -> f.applyAsDouble(r.confusion())).toList();
        return fmt(DetectionMetrics.mean(v)) + " ± " + fmt(DetectionMetrics.stdev(v));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt1(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }
}
