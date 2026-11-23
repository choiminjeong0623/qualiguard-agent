package com.qualiguard.qms.agent.ml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.JdbcTemplate;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.IncidentAnalyzer.Job;
import com.qualiguard.qms.agent.QualityTools;
import com.qualiguard.qms.agent.ToolObservations;
import com.qualiguard.qms.quality.SensorCatalog;

/**
 * ML 먼저, 애매하면 LLM (실험 기능, 기본 꺼짐).
 *
 * 경보가 들어오면 랜덤 포레스트({@link CauseForest})가 먼저 원인을 고른다.
 *  - off    : 아무것도 하지 않는다 (기본)
 *  - shadow : ML 판단을 기록만 하고 LLM 은 그대로 분석한다 (실제 경보에서 일치율을 보는 단계)
 *  - skip   : ML 이 '학습한 고장 원인'을 기준 확률 이상으로 고르면 LLM 을 부르지 않는다
 *
 * 단발성(NOISE)은 ML 이 확신해도 LLM 으로 넘긴다. 학습하지 않은 고장은 '단발성'으로 들어가기 때문이다
 * (eval/ml_cause_report.md 3절). 건너뛸 때도 같은 조회 도구로 실제 수치를 근거로 붙이고,
 * 검증 에이전트와 사람 승인을 그대로 거친다 (검증이 되돌리면 두 번째 시도는 LLM).
 *
 * 안전장치 (eval/ml_novel_report.md)
 *  - 낯선 경보 검사: 고른 원인의 학습 경보들과 특징이 많이 다르면 확률이 높아도 LLM 으로 (기본 켬)
 *  - 표본 점검: 건너뛸 수 있는 사건 중 audit-rate 비율은 LLM 이 분석하고 ML 답과 함께 기록
 *  - 판단 기록: 사건마다 ML 원인·확률·경로·LLM 원인을 agent_ml_judgment 에 남겨 일치율을 센다
 */
public final class MlTriage {

    public enum Mode { OFF, SHADOW, SKIP }

    /** 사건이 어디로 갔는가 (agent_ml_judgment.route). */
    public enum Route {
        /** shadow 모드: LLM 이 분석, ML 은 기록만 */
        SHADOW,
        /** LLM 을 건너뛰고 ML 결과로 진행 */
        SKIPPED,
        /** 건너뛸 수 있었지만 표본 점검으로 LLM 이 분석 */
        AUDIT,
        /** ML 이 단발성이라 LLM 으로 */
        LLM_NOISE,
        /** 확률이 기준보다 낮아 LLM 으로 */
        LLM_LOW,
        /** 학습 경보들과 많이 달라 LLM 으로 */
        LLM_UNFAMILIAR;

        public boolean usesLlm() {
            return this != SKIPPED;
        }
    }

    /** LLM 없이 끝낼 수 있는 원인: 학습한 고장 원인만 (NOISE 제외). */
    public static final Set<String> KNOWN_FAULTS = Set.of("HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR",
            "SENSOR_FAULT");

    /**
     * ML 판단 하나.
     *
     * @param cause       가장 확률이 높은 원인
     * @param probability 그 확률
     * @param proba       원인별 확률
     * @param features    계산한 특징 (근거 문장에 쓸 센서를 고를 때 씀)
     * @param novelty     고른 원인의 학습 경보들과의 거리 (모델에 정보가 없으면 NaN)
     * @param noveltyLimit 이보다 멀면 낯선 경보
     */
    public record Prediction(String cause, double probability, Map<String, Double> proba, long alarmShot,
            String alarmRule, String alarmSensor, double[] features, long millis, double novelty,
            double noveltyLimit) {

        public Prediction(String cause, double probability, Map<String, Double> proba, long alarmShot,
                String alarmRule, String alarmSensor, double[] features, long millis) {
            this(cause, probability, proba, alarmShot, alarmRule, alarmSensor, features, millis, Double.NaN,
                    Double.NaN);
        }

        /** 학습 경보들과 많이 다른가 (낯선 경보 검사 정보가 없으면 false). */
        public boolean unfamiliar() {
            return !Double.isNaN(novelty) && !Double.isNaN(noveltyLimit) && novelty > noveltyLimit;
        }

        public String summary(double threshold) {
            return String.format(Locale.ROOT, "%s %.2f (기준 %.2f, 낯선 정도 %.2f/%.2f, 경보 %s·%s shot %d, %d ms)", cause,
                    probability, threshold, novelty, noveltyLimit, alarmRule, alarmSensor, alarmShot, millis);
        }
    }

    private final JdbcTemplate jdbc;
    private final CauseForest forest;
    private final Mode mode;
    private final double threshold;
    private final boolean noveltyCheck;
    private final double auditRate;

    public MlTriage(JdbcTemplate jdbc, CauseForest forest, Mode mode, double threshold) {
        this(jdbc, forest, mode, threshold, true, 0.0);
    }

    /**
     * @param noveltyCheck 낯선 경보는 LLM 으로 (기본 true)
     * @param auditRate    skip 모드에서 건너뛸 수 있는 사건 중 LLM 으로 다시 보는 비율 (0~1)
     */
    public MlTriage(JdbcTemplate jdbc, CauseForest forest, Mode mode, double threshold, boolean noveltyCheck,
            double auditRate) {
        if (mode != Mode.OFF && forest == null) {
            throw new IllegalArgumentException("ML 1차 분류를 켜려면 모델이 필요합니다");
        }
        if (auditRate < 0 || auditRate > 1) {
            throw new IllegalArgumentException("audit-rate 는 0~1 이어야 합니다: " + auditRate);
        }
        this.jdbc = jdbc;
        this.forest = forest;
        this.mode = mode;
        this.threshold = threshold;
        this.noveltyCheck = noveltyCheck;
        this.auditRate = auditRate;
    }

    public static MlTriage off() {
        return new MlTriage(null, null, Mode.OFF, 1.0);
    }

    public boolean noveltyCheck() {
        return noveltyCheck;
    }

    public double auditRate() {
        return auditRate;
    }

    public Mode mode() {
        return mode;
    }

    public double threshold() {
        return threshold;
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    /** 모드와 관계없이, skip 규칙이라면 LLM 을 건너뛸 판단인가 (학습한 고장 · 기준 확률 이상 · 낯설지 않음). */
    public boolean wouldSkip(Prediction p) {
        return p != null && KNOWN_FAULTS.contains(p.cause()) && p.probability() >= threshold
                && !(noveltyCheck && p.unfamiliar());
    }

    /** LLM 을 건너뛰어도 되는가 (표본 점검은 따지지 않음, {@link #route} 참고). */
    public boolean skipLlm(Prediction p) {
        return mode == Mode.SKIP && wouldSkip(p);
    }

    /**
     * 사건의 경로. 표본 점검은 사건 번호로 정해 같은 사건은 다시 돌려도(재시작 복구) 같은 경로가 된다.
     */
    public Route route(Prediction p, String incidentId) {
        if (mode == Mode.SHADOW) {
            return Route.SHADOW;
        }
        if (!KNOWN_FAULTS.contains(p.cause())) {
            return Route.LLM_NOISE;
        }
        if (p.probability() < threshold) {
            return Route.LLM_LOW;
        }
        if (noveltyCheck && p.unfamiliar()) {
            return Route.LLM_UNFAMILIAR;
        }
        return audited(incidentId) ? Route.AUDIT : Route.SKIPPED;
    }

    boolean audited(String incidentId) {
        if (auditRate <= 0 || incidentId == null) {
            return false;
        }
        return Math.floorMod(incidentId.hashCode() * 31 + 17, 10_000) < Math.round(auditRate * 10_000);
    }

    /**
     * 판단 기록 (agent_ml_judgment). LLM 이 분석했으면 llmCause 에 첫 시도의 원인을 넣는다.
     * 기록 실패는 분석을 막지 않는다 (호출하는 쪽에서 잡는다).
     */
    public void record(String incidentId, Job job, Prediction p, Route route, String llmCause) {
        if (jdbc == null || incidentId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO dbo.agent_ml_judgment (incident_id, equipment_id, alarm_shot, mode, route, ml_cause,
                    ml_probability, novelty, novelty_limit, would_skip, llm_cause, threshold)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM dbo.agent_ml_judgment WHERE incident_id = ?)
                """, incidentId, job.equipmentId(), p.alarmShot(), mode.name(), route.name(), p.cause(),
                round3(p.probability()), finite(p.novelty()), finite(p.noveltyLimit()), wouldSkip(p),
                llmCause, round3(threshold), incidentId);
    }

    private static BigDecimal round3(double v) {
        return BigDecimal.valueOf(Math.round(v * 1000) / 1000.0);
    }

    private static BigDecimal finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : round3(Math.min(v, 999_999.0));
    }

    /**
     * 경보 사건의 가장 늦은 경보를 분류한다. 경보 사건이 아니거나(재확인·재분석) 데이터가 없으면 비어 있다.
     */
    public Optional<Prediction> predict(Job job) {
        if (!enabled() || job == null || !"ALARM".equals(job.trigger()) || job.alarms() == null
                || job.alarms().isEmpty() || job.asOf() == null) {
            return Optional.empty();
        }
        Map<String, Object> alarm = job.alarms().stream()
                .filter(a -> a.get("shot_no") instanceof Number)
                .max(Comparator.comparingLong(a -> ((Number) a.get("shot_no")).longValue())).orElse(null);
        if (alarm == null) {
            return Optional.empty();
        }
        long shot = ((Number) alarm.get("shot_no")).longValue();
        if (shot <= CauseFeatures.BASELINE) {
            return Optional.empty();   // 기준 구간 안의 경보는 학습에도 쓰지 않았다
        }
        long t0 = System.nanoTime();
        CauseFeatures.Series series = load(job.equipmentId(), shot, job.asOf());
        if (series.isEmpty()) {
            return Optional.empty();
        }
        String rule = (String) alarm.get("rule_code");
        String sensor = (String) alarm.get("sensor_key");
        double[] x = CauseFeatures.compute(series, shot, rule, sensor);
        return Optional.of(classify(x, shot, rule, sensor, (System.nanoTime() - t0) / 1_000_000));
    }

    Prediction classify(double[] x, long shot, String rule, String sensor, long millis) {
        double[] p = forest.proba(x);
        int best = IntStream.range(0, p.length).reduce((a, b) -> p[b] > p[a] ? b : a).orElse(0);
        Map<String, Double> proba = new LinkedHashMap<>();
        for (int c = 0; c < p.length; c++) {
            proba.put(forest.classes().get(c), Math.round(p[c] * 1000) / 1000.0);
        }
        String cause = forest.classes().get(best);
        return new Prediction(cause, p[best], proba, shot, rule, sensor, x, millis, forest.novelty(x, cause),
                forest.noveltyLimit(cause));
    }

    /**
     * 경보 shot 까지의 같은 실행(run) 데이터. 시뮬레이터를 다시 돌리면 shot 번호가 1 부터 다시 시작하므로,
     * 기준 시점 이전에 shot 1~3 이 마지막으로 들어온 시각부터 본다 (1시간 여유: 첫 shot 들이 빠진 경우).
     */
    CauseFeatures.Series load(String equipmentId, long shot, String asOf) {
        CauseFeatures.Series s = new CauseFeatures.Series();
        jdbc.query("""
                WITH run_start AS (
                    SELECT DATEADD(HOUR, -1, MAX(shot_ts)) AS ts FROM dbo.process_shot
                    WHERE equipment_id = ? AND shot_no <= 3 AND shot_ts <= CAST(? AS DATETIMEOFFSET(3))
                )
                SELECT p.shot_no, p.mold_temp, p.barrel_temp, p.injection_pressure, p.holding_pressure, p.cushion,
                       p.cycle_time, p.motor_current, p.inspection_result, p.defect_type
                FROM dbo.process_shot AS p CROSS JOIN run_start AS r
                WHERE p.equipment_id = ? AND p.shot_no BETWEEN 1 AND ?
                  AND p.shot_ts <= CAST(? AS DATETIMEOFFSET(3))
                  AND (r.ts IS NULL OR p.shot_ts >= r.ts)
                ORDER BY p.shot_id
                """, rs -> {
            Double[] v = new Double[CauseFeatures.SENSORS.size()];
            for (int j = 0; j < v.length; j++) {
                BigDecimal d = rs.getBigDecimal(2 + j);
                v[j] = d == null ? null : d.doubleValue();
            }
            s.put(rs.getLong(1), v, rs.getString(9) == null ? null : rs.getString(9).strip(), rs.getString(10));
        }, equipmentId, asOf, equipmentId, shot, asOf);
        return s;
    }

    /**
     * LLM 없이 만든 분석 결과. 조회 도구(SPC 요약·센서 추이·불량 요약)를 실제로 불러 그 값만 근거에 쓴다
     * (도구 호출은 trace 에, 값은 observed 에 남아 검증 에이전트가 LLM 결과와 똑같이 대조한다).
     */
    public AnalysisResult result(Job job, Prediction p, long startNanos, List<String> trace,
            ToolObservations observed) {
        QualityTools tools = new QualityTools(jdbc, trace, job.asOf(), startNanos, null, observed);
        String eq = job.equipmentId();
        List<String> sensors = topSensors(p, 2);
        List<String> evidence = new ArrayList<>();

        List<Map<String, Object>> spc = tools.getSpcSummary(eq, 50);
        for (String k : sensors) {
            spc.stream().filter(r -> k.equals(r.get("sensor_key"))).findFirst().ifPresent(r -> evidence.add(
                    name(k) + " 최근 50 shot 평균 " + num(r.get("mean_value")) + unit(r)
                            + (r.get("cpk") == null ? "" : ", Cpk " + num(r.get("cpk")))));
        }
        String main = sensors.isEmpty() ? null : sensors.get(0);
        if (main != null) {
            List<Map<String, Object>> trend = tools.getSensorTrend(eq, main, 300, 20);
            if (trend.size() >= 2 && trend.get(0).get("avg_value") != null) {
                Map<String, Object> first = trend.get(0);
                Map<String, Object> last = trend.get(trend.size() - 1);
                evidence.add(name(main) + " 20 shot 구간 평균이 " + num(first.get("avg_value")) + unit(first) + " 에서 "
                        + num(last.get("avg_value")) + unit(last) + " 로 바뀜 (최근 300 shot 추이)");
            }
        }
        List<Map<String, Object>> defects = tools.getDefectSummary(eq, 300, 50);
        if (!defects.isEmpty()) {
            Map<String, Object> last = defects.get(defects.size() - 1);
            if (last.get("ng_rate_pct") != null) {
                evidence.add("최근 구간 불량률 " + num(last.get("ng_rate_pct")) + "% (검사 " + num(last.get("inspected"))
                        + "건 중 불량 " + num(last.get("ng_count")) + "건)");
            }
        }

        Template t = TEMPLATES.get(p.cause());
        int confidence = (int) Math.round(p.probability() * 100);
        String phenomenon = sensors.stream()
                .map(k -> String.format(Locale.ROOT, "%s 최근 20 shot 평균이 기준 구간 대비 %+.1fσ", name(k),
                        p.features()[CauseFeatures.SENSORS.indexOf(k) * CauseFeatures.PER_SENSOR + 1]))
                .reduce((a, b) -> a + ", " + b).orElse("센서 변화") + " (경보 " + p.alarmRule() + ")";
        String summary = String.format(Locale.ROOT,
                "[ML 1차 분류] 랜덤 포레스트가 이 경보를 '%s'(으)로 분류했습니다 (확률 %.2f, 기준 %.2f). "
                        + "학습한 고장 원인이고 확률이 기준 이상이라 LLM 을 부르지 않았고, 근거 수치는 조회 도구 결과에서 옮겼습니다. "
                        + "과거 사례는 찾지 않았습니다. 사람이 검토·승인해야 확정됩니다.",
                t.ko, p.probability(), threshold);
        return new AnalysisResult(t.decision, p.cause(), confidence, eq + " " + t.ko + " 의심 (ML 1차 분류)",
                phenomenon, t.cause, List.copyOf(evidence), List.of(), t.action, summary,
                sensors.stream().map(k -> name(k) + " 추이").toList(), candidates(p));
    }

    /** 근거로 쓸 센서: 최근 20 shot 평균 |z| 가 큰 순서. */
    static List<String> topSensors(Prediction p, int n) {
        List<String> keys = new ArrayList<>(CauseFeatures.SENSORS);
        keys.sort(Comparator.comparingDouble((String k) -> -Math.abs(
                p.features()[CauseFeatures.SENSORS.indexOf(k) * CauseFeatures.PER_SENSOR + 1])));
        return keys.subList(0, Math.min(n, keys.size()));
    }

    private static List<String> candidates(Prediction p) {
        return p.proba().entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).toList();
    }

    private static String name(String key) {
        SensorCatalog.Sensor s = SensorCatalog.SENSORS.get(key);
        return s == null ? key : s.name();
    }

    private static String unit(Map<String, Object> row) {
        Object u = row.get("unit");
        return u == null ? "" : u.toString();
    }

    /** 도구가 돌려준 값을 그대로 적는다 (검증 에이전트의 수치 대조와 맞게). */
    static String num(Object v) {
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    /** 원인 코드별 문장 틀 (판단 규칙은 에이전트와 같음: 고장 3종 → 초안, 압력 센서 → 센서 점검). */
    private record Template(String ko, String decision, String cause, String action) {
    }

    private static final Map<String, Template> TEMPLATES = Map.of(
            "HEATER_DEGRADATION", new Template("히터 열화", "NCR_DRAFT",
                    "실린더 히터 열화로 가열 능력이 떨어진 것으로 추정",
                    "히터 밴드·열전대 점검, 설정 온도 대비 실제 실린더 온도 확인"),
            "COOLING_ISSUE", new Template("냉각 막힘", "NCR_DRAFT",
                    "금형 냉각 유로 막힘 또는 냉각수 유량 저하로 추정",
                    "냉각수 유량·입출구 온도 확인, 냉각 채널 스케일 점검"),
            "CHECK_RING_WEAR", new Template("체크 링 마모", "NCR_DRAFT",
                    "체크 링 마모로 사출 중 수지 역류가 늘어난 것으로 추정",
                    "체크 링·스크루 헤드 마모 점검, 쿠션량 추이 확인"),
            "SENSOR_FAULT", new Template("압력 센서 이상", "SENSOR_CHECK",
                    "압력 센서(측정 계통) 이상으로 추정, 제품 품질 영향은 확인되지 않음",
                    "압력 센서 배선·커넥터 점검과 교정"));
}
