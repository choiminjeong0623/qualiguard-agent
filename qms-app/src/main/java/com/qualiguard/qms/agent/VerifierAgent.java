package com.qualiguard.qms.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 검증 에이전트 (ADR-016): 원인 분석 결과를 데이터와 대조해, 근거가 약하면 분석 에이전트로 되돌린다.
 *
 * LLM 을 쓰지 않는 규칙 기반 검증이다. 분석 1건이 이미 평균 45초 안팎이라 LLM 검증을 하나 더 붙이면
 * 1분 목표에서 더 멀어지고, "같은 모델이 자기 답을 다시 보는" 검증은 독립적인 확인이 되기 어렵기 때문이다.
 * 대신 모델이 바꿀 수 없는 것(도구 호출 기록, DB 의 실제 값, 경보 규칙)과 결과를 맞춰 본다.
 *
 * 규칙은 모두 특정 고장과 무관한 일반 원칙이다 (특정 고장을 맞히기 위한 힌트 금지).
 *  - FAIL(되돌림): 조사를 건너뛰었거나, 결과가 데이터·자기 자신과 모순될 때
 *                 (근거에 적은 수치가 도구 결과에 없는 경우 포함 — 규칙 6, {@link EvidenceNumbers})
 *  - WARN(기록만): 사람이 검토할 때 볼 만한 약점
 */
@Component
public class VerifierAgent {

    /** 지속된 변화를 뜻하는 경보 규칙 (INDIVIDUAL 만 단발성일 수 있다). */
    static final Set<String> SUSTAINED_RULES = Set.of("XBAR", "S_CHART", "P_CHART");
    /** 사람이 승인할 때 받아 주지 않는 원인 코드 (ADR-009 결정 2) — 초안이면 원인을 정해야 한다. */
    static final Set<String> NOT_A_CAUSE = Set.of("UNKNOWN", "NOISE");
    static final int RECENT_SHOTS = 50;
    static final int BASELINE_SHOTS = 250;
    /** 수치 대조 지적에 보여 줄 예시 수 */
    static final int MAX_SAMPLES = 3;

    /**
     * 검증 결과.
     *
     * @param status PASS(통과) / WEAK(되돌릴 만큼 약함)
     * @param fails  되돌림 사유 (분석 에이전트에게 그대로 전달)
     * @param warns  기록만 하는 약점
     * @param numberClaims   근거에 적힌 수치 개수 (도구 결과와 대조했을 때만, 아니면 null. 로드맵 E5 평가용)
     * @param numberVerified 그중 도구 결과와 맞은 수치 개수
     */
    public record Verification(String status, List<String> fails, List<String> warns, Integer numberClaims,
            Integer numberVerified) {

        public Verification(String status, List<String> fails, List<String> warns) {
            this(status, fails, warns, null, null);
        }

        public boolean passed() {
            return "PASS".equals(status);
        }
    }

    /**
     * 검증 입력: 분석 결과와, 모델이 바꿀 수 없는 사실들.
     *
     * @param observed 분석 중 도구가 돌려준 수치. null 이거나 비어 있으면 수치 대조(규칙 6)를 건너뛴다.
     */
    public record Input(String equipmentId, String asOf, List<String> alarmRules, AnalysisResult result,
            List<String> toolTrace, ToolObservations observed) {

        /** 수치 대조 없이 쓰던 예전 형태 (단위 시험 등). */
        public Input(String equipmentId, String asOf, List<String> alarmRules, AnalysisResult result,
                List<String> toolTrace) {
            this(equipmentId, asOf, alarmRules, result, toolTrace, null);
        }
    }

    /** 검증에 필요한 DB 조회. 단위 시험에서는 흉내 낸다. */
    public interface Facts {
        /** 기준 시점까지 승인·조치 완료된 사례 번호 중 주어진 번호에 해당하는 것 (미래 승인·반려 사례는 제외). */
        Set<String> approvedCases(List<String> ncrNos, String asOf);

        /** 기준 시점까지 최근 recent shot 의 불량률과, 그 앞 baseline shot 의 불량률. 데이터가 없으면 null. */
        double[] defectRates(String equipmentId, String asOf, int recent, int baseline);
    }

    private final Facts facts;

    /** 앱에서 쓰는 생성자. 생성자가 둘이라 Spring 이 고를 수 있게 표시한다 (없으면 앱 시작 실패). */
    @Autowired
    public VerifierAgent(JdbcTemplate jdbc) {
        this(new JdbcFacts(jdbc));
    }

    public VerifierAgent(Facts facts) {
        this.facts = facts;
    }

    public Verification verify(Input in) {
        List<String> fails = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        AnalysisResult r = in.result();
        String decision = upper(r.decision());
        String cause = upper(r.causeCode());
        boolean concluded = "NCR_DRAFT".equals(decision) || "SENSOR_CHECK".equals(decision);
        Set<String> tools = calledTools(in.toolTrace());
        boolean sustained = in.alarmRules() != null
                && in.alarmRules().stream().map(VerifierAgent::upper).anyMatch(SUSTAINED_RULES::contains);

        // 1. 조사 충분성: 결론(초안·센서 점검)을 내렸다면 추이와 불량을 직접 봤어야 한다
        if (concluded) {
            if (!tools.contains("getSensorTrend") && !tools.contains("getSpcSummary")) {
                fails.add("센서 추이를 조회하지 않고 결론을 냈습니다. getSensorTrend 또는 getSpcSummary 로 확인하세요.");
            }
            if (!tools.contains("getDefectSummary")) {
                fails.add("실제 불량을 조회하지 않고 결론을 냈습니다. getDefectSummary 로 품질 영향을 확인하세요.");
            }
        }

        // 2. 근거 수치: 결론에는 도구에서 확인한 수치가 담긴 근거가 2개 이상 있어야 한다
        //    도구 결과를 받았으면 "숫자가 있는가"가 아니라 "도구 결과와 맞는 숫자가 있는가"로 센다 (규칙 6과 같은 대조)
        EvidenceNumbers.Report numbers = in.observed() == null || in.observed().isEmpty() ? null
                : EvidenceNumbers.check(r.evidence(), in.observed());
        long numeric = numbers != null ? numbers.groundedSentences()
                : r.evidence() == null ? 0 : r.evidence().stream().filter(VerifierAgent::hasDigit).count();
        if (concluded && numeric < 2) {
            fails.add((numbers != null ? "도구 결과와 맞는 수치가 담긴 근거가 " : "수치가 담긴 근거가 ") + numeric
                    + "개뿐입니다. 도구에서 확인한 실제 값을 2개 이상 근거로 쓰세요.");
        }

        // 3. 판단과 원인 코드의 정합성
        if ("NCR_DRAFT".equals(decision) && NOT_A_CAUSE.contains(cause)) {
            fails.add("부적합 초안인데 원인 코드가 " + cause + " 입니다. 원인을 정할 근거가 없으면 OBSERVE 로 두세요.");
        }
        if (sustained && "NOISE".equals(cause)) {
            fails.add("지속성 경보(XBAR·S_CHART·P_CHART)가 있는데 원인을 단발성 변동(NOISE)으로 두었습니다.");
        }
        if ("OBSERVE".equals(decision) && (r.watchItems() == null || r.watchItems().isEmpty())) {
            fails.add("관찰(OBSERVE) 판단인데 다음에 확인할 항목(watchItems)이 없습니다.");
        }

        // 4. 과거 사례 실재: 기준 시점까지 승인된 사례만 인용할 수 있다 (지어낸 번호·미래 사례 차단)
        List<String> cited = r.similarCases() == null ? List.of()
                : r.similarCases().stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
        if (!cited.isEmpty()) {
            Set<String> ok = facts.approvedCases(cited, in.asOf());
            List<String> bad = cited.stream().filter(c -> !ok.contains(c)).toList();
            if (!bad.isEmpty()) {
                fails.add("참고 사례 " + bad + " 는 기준 시점까지 승인된 사례에 없습니다. findSimilarNcr 결과에 있는 번호만 쓰세요.");
            }
        }

        // 5. 데이터와 대조: "제품 영향 없음"(센서 점검) 판단은 실제 불량률이 늘지 않았어야 한다
        if ("SENSOR_CHECK".equals(decision)) {
            if (in.alarmRules() != null && in.alarmRules().stream().map(VerifierAgent::upper).anyMatch("P_CHART"::equals)) {
                fails.add("불량률 경보(P_CHART)가 있는데 제품 영향이 없는 센서 점검으로 판단했습니다.");
            } else {
                double[] rates = facts.defectRates(in.equipmentId(), in.asOf(), RECENT_SHOTS, BASELINE_SHOTS);
                if (rates != null && defectRose(rates[0], rates[1])) {
                    fails.add(String.format(Locale.ROOT,
                            "최근 %d shot 불량률 %.1f%% 가 그 앞 %d shot (%.1f%%) 보다 크게 높습니다. 제품 영향이 없다는 판단과 맞지 않습니다.",
                            RECENT_SHOTS, rates[0] * 100, BASELINE_SHOTS, rates[1] * 100));
                }
            }
        }

        // 6. 수치 대조: 근거에 적은 수치가 도구가 실제로 돌려준 값에 있는가 (지어낸·잘못 옮긴 수치 차단)
        //    절반 넘게 확인되지 않으면 되돌리고, 일부만 어긋나면 사람이 볼 수 있게 기록만 한다
        if (numbers != null && numbers.unverifiedCount() > 0) {
            String msg = "근거의 수치 " + numbers.unverifiedCount() + "개(전체 " + numbers.claimCount() + "개)가 "
                    + "이번 조사에서 도구가 돌려준 값과 맞지 않습니다: " + numbers.unverifiedSamples(MAX_SAMPLES);
            if (concluded && numbers.unverifiedCount() * 2 > numbers.claimCount()) {
                fails.add(msg + ". 도구 결과의 값을 그대로 옮기거나, 확인하지 못한 수치는 빼세요.");
            } else {
                warns.add(msg);
            }
        }

        // 기록만: 확신도와 근거 수가 맞지 않음
        Integer conf = r.confidence();
        if (conf != null && conf >= 80 && numeric < 3) {
            warns.add("확신도 " + conf + " 에 비해 수치 근거가 " + numeric + "개로 적습니다.");
        }
        if ("NCR_DRAFT".equals(decision) && (r.recommendedAction() == null || r.recommendedAction().isBlank())) {
            warns.add("권고 조치가 비어 있습니다.");
        }
        return new Verification(fails.isEmpty() ? "PASS" : "WEAK", List.copyOf(fails), List.copyOf(warns),
                numbers == null ? null : numbers.claimCount(),
                numbers == null ? null : numbers.claimCount() - numbers.unverifiedCount());
    }

    /** 분석 에이전트에게 돌려보낼 지적 문장. */
    public static String feedback(Verification v) {
        StringBuilder sb = new StringBuilder("검증 에이전트가 앞선 분석 결과를 되돌렸습니다. 아래 지적을 해결한 뒤 결과를 다시 작성하세요.\n");
        for (int i = 0; i < v.fails().size(); i++) {
            sb.append(i + 1).append(". ").append(v.fails().get(i)).append('\n');
        }
        return sb.append("지적을 해결할 근거가 없으면 결론을 낮추세요(예: OBSERVE + watchItems). 근거를 지어내지 마세요.\n").toString();
    }

    /** 최근 불량률이 기준보다 의미 있게 높은가: 2배 이상이면서 5%p 이상 차이. */
    static boolean defectRose(double recent, double baseline) {
        return recent >= baseline * 2 && recent - baseline >= 0.05;
    }

    static Set<String> calledTools(List<String> trace) {
        Set<String> out = new LinkedHashSet<>();
        if (trace != null) {
            for (String t : trace) {
                int p = t == null ? -1 : t.indexOf('(');
                if (p > 0 && !t.startsWith(" ")) {
                    out.add(t.substring(0, p));
                }
            }
        }
        return out;
    }

    private static boolean hasDigit(String s) {
        return s != null && s.chars().anyMatch(Character::isDigit);
    }

    private static String upper(String s) {
        return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    }

    /** 운영용 DB 조회. */
    static final class JdbcFacts implements Facts {
        private final JdbcTemplate jdbc;

        JdbcFacts(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Set<String> approvedCases(List<String> ncrNos, String asOf) {
            String in = String.join(",", ncrNos.stream().map(x -> "?").toList());
            List<Object> args = new ArrayList<>(ncrNos);
            args.add(asOf);
            args.add(asOf);
            return new LinkedHashSet<>(jdbc.queryForList(
                    "SELECT ncr_no FROM dbo.ncr WHERE ncr_no IN (" + in + ") AND status IN ('APPROVED', 'CLOSED') "
                            + "AND (CAST(? AS DATETIMEOFFSET(3)) IS NULL "
                            + "OR COALESCE(approved_at, created_at) <= CAST(? AS DATETIMEOFFSET(3)))",
                    String.class, args.toArray()));
        }

        @Override
        public double[] defectRates(String equipmentId, String asOf, int recent, int baseline) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT SUM(CASE WHEN rn <= ? AND inspection_result = 'NG' THEN 1 ELSE 0 END) AS recent_ng,
                           SUM(CASE WHEN rn <= ? THEN 1 ELSE 0 END) AS recent_n,
                           SUM(CASE WHEN rn > ? AND inspection_result = 'NG' THEN 1 ELSE 0 END) AS base_ng,
                           SUM(CASE WHEN rn > ? THEN 1 ELSE 0 END) AS base_n
                    FROM (SELECT TOP (?) inspection_result,
                                 ROW_NUMBER() OVER (ORDER BY shot_ts DESC, shot_id DESC) AS rn
                          FROM dbo.process_shot
                          WHERE equipment_id = ? AND shot_ts <= CAST(? AS DATETIMEOFFSET(3))
                            AND inspection_result IS NOT NULL   -- 검사 결과 없는 shot 은 OK 로 세지 않음 (ADR-017)
                          ORDER BY shot_ts DESC, shot_id DESC) AS s
                    """, recent, recent, recent, recent, recent + baseline, equipmentId, asOf);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> m = rows.get(0);
            int rn = num(m.get("recent_n"));
            int bn = num(m.get("base_n"));
            if (rn < recent / 2 || bn < recent) {
                return null;   // 비교할 만큼 데이터가 없다
            }
            return new double[] { (double) num(m.get("recent_ng")) / rn, (double) num(m.get("base_ng")) / bn };
        }

        private static int num(Object o) {
            return o == null ? 0 : ((Number) o).intValue();
        }
    }
}
