package com.qualiguard.qms.ncr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 부적합 보고서 필수 항목 충족 검사 (로드맵 P2).
 *
 * <p>항목 정의는 초안이다. 품질 담당자가 실제로 승인 전에 확인하는 기준에 맞춰 {@link #ITEMS} 를 고치면 된다.
 * 공정 데이터에 로트 번호가 없어서, "영향 범위"는 연결된 경보가 한 건 이상인지로 대신 본다.
 */
public final class NcrCompleteness {

    /** 검사 항목 하나: 키, 화면 이름, 통과 조건 설명. */
    public record Item(String key, String label, String rule) {
    }

    /** 항목별 결과. */
    public record Check(String key, String label, boolean ok, String reason) {
    }

    /** 한 건의 결과: 충족 항목 수 / 전체, 빠진 항목 이름. */
    public record Result(List<Check> checks, int passed, int total, List<String> missing) {
        public double rate() {
            return total == 0 ? 1.0 : (double) passed / total;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("total", total);
            m.put("rate", Math.round(rate() * 1000) / 1000.0);
            m.put("missing", missing);
            m.put("checks", checks);
            return m;
        }
    }

    public static final List<Item> ITEMS = List.of(
            new Item("title", "제목", "비어 있지 않음"),
            new Item("phenomenon", "현상", "10자 이상"),
            new Item("cause", "원인", "추정 원인 또는 확정 원인이 10자 이상"),
            new Item("causeCode", "원인 코드", "UNKNOWN 이 아닌 코드"),
            new Item("evidence", "근거", "숫자(측정값)를 하나 이상 포함"),
            new Item("recommendedAction", "권장 조치", "10자 이상"),
            new Item("relatedSensors", "관련 센서", "하나 이상"),
            new Item("defectType", "불량 유형", "비어 있지 않음"),
            new Item("scope", "영향 범위", "연결된 경보 1건 이상 (로트 정보 대신)"));

    private static final Pattern NUMBER = Pattern.compile("\\d");
    static final int MIN_TEXT = 10;

    private NcrCompleteness() {
    }

    /**
     * @param ncr          ncr 테이블 컬럼 이름(snake_case)을 키로 한 값
     * @param anomalyCount 연결된 경보 수
     */
    public static Result check(Map<String, ?> ncr, int anomalyCount) {
        List<Check> checks = new ArrayList<>();
        for (Item item : ITEMS) {
            String reason = switch (item.key()) {
                case "title" -> blank(s(ncr, "title")) ? "제목이 없습니다" : null;
                case "phenomenon" -> shortText(s(ncr, "phenomenon"), "현상");
                case "cause" -> {
                    String root = s(ncr, "root_cause");
                    String suspected = s(ncr, "suspected_cause");
                    yield length(root) >= MIN_TEXT || length(suspected) >= MIN_TEXT ? null
                            : "원인 설명이 없거나 " + MIN_TEXT + "자 미만입니다";
                }
                case "causeCode" -> {
                    String c = s(ncr, "cause_code");
                    yield blank(c) ? "원인 코드가 없습니다" : "UNKNOWN".equals(c.strip()) ? "원인 코드가 UNKNOWN 입니다" : null;
                }
                case "evidence" -> {
                    String e = s(ncr, "evidence");
                    yield blank(e) ? "근거가 없습니다" : NUMBER.matcher(e).find() ? null : "근거에 측정값(숫자)이 없습니다";
                }
                case "recommendedAction" -> shortText(s(ncr, "recommended_action"), "권장 조치");
                case "relatedSensors" -> blank(s(ncr, "related_sensors")) || "[]".equals(s(ncr, "related_sensors").strip())
                        ? "관련 센서가 없습니다" : null;
                case "defectType" -> blank(s(ncr, "defect_type")) ? "불량 유형이 없습니다" : null;
                case "scope" -> anomalyCount > 0 ? null : "연결된 경보가 없습니다";
                default -> throw new IllegalStateException(item.key());
            };
            checks.add(new Check(item.key(), item.label(), reason == null, reason));
        }
        List<String> missing = checks.stream().filter(c -> !c.ok()).map(Check::label).toList();
        return new Result(checks, checks.size() - missing.size(), checks.size(), missing);
    }

    /**
     * 여러 건 집계: 항목별 충족률, 모두 충족한 비율, 평균 충족률.
     */
    public static Map<String, Object> summarize(List<Result> results) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = results.size();
        out.put("count", n);
        long complete = results.stream().filter(r -> r.passed() == r.total()).count();
        out.put("completeCount", complete);
        out.put("completeRate", n == 0 ? null : round((double) complete / n));
        out.put("avgRate", n == 0 ? null : round(results.stream().mapToDouble(Result::rate).average().orElse(0)));
        Map<String, Object> perItem = new LinkedHashMap<>();
        for (int i = 0; i < ITEMS.size(); i++) {
            final int idx = i;
            long ok = results.stream().filter(r -> r.checks().get(idx).ok()).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", ITEMS.get(i).label());
            m.put("rule", ITEMS.get(i).rule());
            m.put("passRate", n == 0 ? null : round((double) ok / n));
            perItem.put(ITEMS.get(i).key(), m);
        }
        out.put("items", perItem);
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static String shortText(String v, String name) {
        if (blank(v)) {
            return name + "이(가) 없습니다";
        }
        return v.strip().length() < MIN_TEXT ? name + "이(가) " + MIN_TEXT + "자 미만입니다" : null;
    }

    private static String s(Map<String, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int length(String s) {
        return s == null ? 0 : s.strip().length();
    }
}
