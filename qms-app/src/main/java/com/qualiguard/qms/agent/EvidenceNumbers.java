package com.qualiguard.qms.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근거 문장 속 수치를 뽑아, 도구가 실제로 돌려준 값({@link ToolObservations})과 대조한다.
 *
 * 예) 근거 "실린더 온도 최근 20 shot 평균 224.1℃ (규격 하한 220℃)"
 *     → 224.1, 220 을 뽑는다 (20 shot 은 조회 범위라 대조하지 않는다)
 *     → 도구 결과에 224.13, 220 이 있으면 둘 다 "확인됨"
 *
 * 일치로 보는 경우 (모두 특정 고장과 무관한 일반 규칙):
 *  1. 같은 값: 모델이 반올림해 적는 것을 감안해, 적힌 자릿수의 반올림 오차까지 허용 (224.1 이면 ±0.05)
 *  2. 비율 → 퍼센트: 도구의 0.062 를 "6.2%" 로 적은 경우
 *  3. 변화량: 부호(+/−)가 붙었거나 %p 인 수는 두 관측값의 차이와 맞춰 본다 ("+5.3℃ 상승")
 * 대조하지 않는 수: 조회 범위(shot·샷), 모델이 계산한 배수·σ (배·σ), 설비·사례 번호, 날짜·시각.
 */
public final class EvidenceNumbers {

    /** 대조하지 않는 단위: 조회 범위이거나 모델이 직접 계산한 값이라 도구 결과에 그대로 있지 않다. */
    static final Set<String> SKIP_UNITS = Set.of("shot", "샷", "σ", "배");
    /** 소수 계산(double) 오차를 흡수하는 아주 작은 여유 */
    static final double EPS = 1e-9;

    /**
     * 근거에서 뽑은 수치 하나.
     *
     * @param value  수의 크기 (부호 제외)
     * @param text   원문 그대로의 표기, 예: "+5.3℃" 의 "+5.3"
     * @param unit   바로 뒤의 단위 중 판정에 쓰는 것 (%, %p, shot 등). 없으면 빈 문자열
     * @param signed 부호가 붙었는가 (변화량으로 본다)
     */
    public record Claim(BigDecimal value, String text, String unit, boolean signed) {
        boolean percent() {
            return unit.startsWith("%");
        }

        boolean change() {
            return signed || "%p".equals(unit);
        }
    }

    /** 근거 문장 한 개의 대조 결과. */
    public record Sentence(String text, List<Claim> verified, List<Claim> unverified) {
    }

    /** 근거 전체의 대조 결과. */
    public record Report(List<Sentence> sentences) {
        public int claimCount() {
            return sentences.stream().mapToInt(s -> s.verified().size() + s.unverified().size()).sum();
        }

        public int unverifiedCount() {
            return sentences.stream().mapToInt(s -> s.unverified().size()).sum();
        }

        /** 확인된 수치가 하나라도 있는 근거 문장 수. */
        public long groundedSentences() {
            return sentences.stream().filter(s -> !s.verified().isEmpty()).count();
        }

        /** 지적 문장에 넣을 목록: "224.9 (실린더 온도 평균 224.9℃)" */
        public List<String> unverifiedSamples(int max) {
            List<String> out = new ArrayList<>();
            for (Sentence s : sentences) {
                for (Claim c : s.unverified()) {
                    if (out.size() < max) {
                        out.add(c.text() + c.unit() + " (\"" + shorten(s.text(), 40) + "\")");
                    }
                }
            }
            return out;
        }
    }

    // 날짜·시각·식별자(IMM-01, NCR-2025-0012, AI-2026-0035)는 수치가 아니므로 먼저 지운다
    private static final Pattern NOT_A_VALUE = Pattern.compile(
            "\\d{4}-\\d{1,2}-\\d{1,2}(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?"
                    + "|\\d{1,2}:\\d{2}(?::\\d{2})?"
                    + "|[A-Za-z][A-Za-z_]*[-_]?\\d[\\w-]*");

    // 수 하나: 앞에 글자·숫자·점이 붙지 않은 곳에서 시작, 부호(선택) + 숫자(천 단위 쉼표, 소수 허용) + 단위(선택)
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])(?<sign>[+\\-−±])?(?<num>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
                    + "\\s*(?<unit>%p|%|σ|배|shot|샷)?",
            Pattern.CASE_INSENSITIVE);

    private EvidenceNumbers() {
    }

    /** 문장에서 대조할 수치들을 뽑는다 (건너뛸 단위 포함 — 판정 때 거른다). */
    public static List<Claim> extract(String sentence) {
        List<Claim> out = new ArrayList<>();
        if (sentence == null || sentence.isBlank()) {
            return out;
        }
        String masked = NOT_A_VALUE.matcher(sentence).replaceAll(" ");
        Matcher m = NUMBER.matcher(masked);
        while (m.find()) {
            String sign = m.group("sign");
            String num = m.group("num");
            String unit = m.group("unit") == null ? "" : m.group("unit").toLowerCase(Locale.ROOT);
            out.add(new Claim(new BigDecimal(num.replace(",", "")), (sign == null ? "" : sign) + num, unit,
                    sign != null));
        }
        return out;
    }

    /** 근거 문장들을 관측값과 대조한다. */
    public static Report check(List<String> evidence, ToolObservations observed) {
        double[] obs = observed.values().stream().mapToDouble(BigDecimal::doubleValue).sorted().toArray();
        List<Sentence> sentences = new ArrayList<>();
        if (evidence != null) {
            for (String e : evidence) {
                List<Claim> ok = new ArrayList<>();
                List<Claim> bad = new ArrayList<>();
                for (Claim c : extract(e)) {
                    if (SKIP_UNITS.contains(c.unit())) {
                        continue;
                    }
                    (matches(c, obs) ? ok : bad).add(c);
                }
                sentences.add(new Sentence(e, List.copyOf(ok), List.copyOf(bad)));
            }
        }
        return new Report(List.copyOf(sentences));
    }

    /** obs 는 오름차순 정렬된 관측값. */
    static boolean matches(Claim c, double[] obs) {
        double target = c.value().doubleValue();
        double tol = tolerance(c.value());
        double[] scales = c.percent() ? new double[] { 1, 100 } : new double[] { 1 };
        for (double k : scales) {
            // 1·2. 같은 값 (퍼센트면 비율×100 도) — 부호는 문장마다 표현이 달라 크기로 비교한다
            for (double o : obs) {
                if (Math.abs(Math.abs(o * k) - target) <= tol) {
                    return true;
                }
            }
            // 3. 변화량: 두 관측값 a < b 의 차이 (b - a) × k ≈ target
            if (c.change() && hasDifference(obs, target / k, tol / k)) {
                return true;
            }
        }
        return false;
    }

    /** 정렬된 값들 중 b - a 가 diff ± tol 인 쌍이 있는가. 값마다 이분 탐색이라 수천 개여도 빠르다. */
    static boolean hasDifference(double[] sorted, double diff, double tol) {
        for (double a : sorted) {
            int i = Arrays.binarySearch(sorted, a + diff - tol);
            int from = i >= 0 ? i : -i - 1;   // 없으면 "들어갈 자리"를 음수로 돌려준다
            if (from < sorted.length && sorted[from] <= a + diff + tol) {
                return true;
            }
        }
        return false;
    }

    /** 적힌 자릿수의 반올림 오차: 224.1 → 0.05, 224 → 0.5, 6.25 → 0.005. */
    static double tolerance(BigDecimal claimed) {
        int decimals = Math.max(claimed.scale(), 0);   // scale(): 소수점 아래 자릿수
        return 0.5 * Math.pow(10, -decimals) + EPS;
    }

    static BigDecimal toDecimal(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        double d = n.doubleValue();
        return Double.isFinite(d) ? new BigDecimal(n.toString()) : null;
    }

    static BigDecimal parseOrNull(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
