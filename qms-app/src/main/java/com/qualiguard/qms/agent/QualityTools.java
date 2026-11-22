package com.qualiguard.qms.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 에이전트가 호출하는 조회 도구. 모두 SQL Server 저장 프로시저를 그대로 호출한다.
 *
 * 분석 1건마다 새로 만들어 쓰며, 호출된 도구와 인자를 trace 에 순서대로 기록한다.
 * (어떤 근거를 보고 판단했는지 사람이 나중에 확인할 수 있게 하기 위함)
 *
 * asOf: 조회 기준 시점 (운영은 사건의 마지막 경보 시각, 시간 순 재생은 시작 경보 시각). 공정 데이터 조회는 이 시점까지로 제한한다.
 * 지난 경보를 나중에 분석해도 "경보 당시에 볼 수 있었던 데이터"로만 판단하게 하기 위함이며,
 * 모델이 바꿀 수 없도록 도구 인자로 노출하지 않는다.
 */
public class QualityTools {

    private final JdbcTemplate jdbc;
    private final List<String> trace;
    private final String asOf;
    private final long startNanos;
    /** 현재 현상 설명 → 벡터 문자열 (의미 검색, ADR-014). null 이면 키워드 검색만. */
    private final Function<String, String> vectorizer;
    /** 도구가 돌려준 수치를 검증 에이전트가 대조할 수 있게 모아 둔다. null 이면 모으지 않는다 (MCP 등). */
    private final ToolObservations observations;

    public QualityTools(JdbcTemplate jdbc, List<String> trace, String asOf) {
        this(jdbc, trace, asOf, System.nanoTime());
    }

    public QualityTools(JdbcTemplate jdbc, List<String> trace, String asOf, long startNanos) {
        this(jdbc, trace, asOf, startNanos, null);
    }

    /** startNanos: 분석 시작 시각. 도구마다 "분석 시작 후 몇 ms 에 불렸는지"를 trace 에 남겨 LLM 왕복 시간을 볼 수 있게 한다. */
    public QualityTools(JdbcTemplate jdbc, List<String> trace, String asOf, long startNanos,
            Function<String, String> vectorizer) {
        this(jdbc, trace, asOf, startNanos, vectorizer, null);
    }

    public QualityTools(JdbcTemplate jdbc, List<String> trace, String asOf, long startNanos,
            Function<String, String> vectorizer, ToolObservations observations) {
        this.jdbc = jdbc;
        this.trace = trace;
        this.asOf = asOf;
        this.startNanos = startNanos;
        this.vectorizer = vectorizer;
        this.observations = observations;
    }

    @Tool(description = "설비 센서의 최근 추이를 구간별 평균·최소·최대·표준편차·불량 수와 검사 규격(lsl/target/usl)으로 조회한다. "
            + "센서 키: moldTemp(금형 온도), barrelTemp(실린더 온도), injectionPressure(사출 압력), holdingPressure(보압), "
            + "cushion(쿠션량), cycleTime(사이클 타임), motorCurrent(모터 전류)")
    public List<Map<String, Object>> getSensorTrend(
            @ToolParam(description = "설비 ID, 예: IMM-01") String equipmentId,
            @ToolParam(description = "센서 키, 예: barrelTemp") String sensorKey,
            @ToolParam(description = "최근 몇 shot 을 볼지 (넓은 추이 300, 최근 변화 60)") Integer lastShots,
            @ToolParam(description = "몇 shot 씩 묶을지 (넓은 추이 20, 최근 변화 5)") Integer bucketSize) {
        return call("getSensorTrend", "EXEC dbo.usp_GetSensorTrend ?, ?, ?, ?, ?",
                equipmentId, sensorKey, orDefault(lastShots, 300), orDefault(bucketSize, 20), asOf);
    }

    @Tool(description = "설비의 최근 N shot 에 대해 센서별 평균·표준편차·검사 규격·공정 능력 지수(Cpk)·규격 이탈 shot 수를 조회한다. "
            + "여러 센서를 한 번에 비교해 어떤 센서가 함께 움직였는지 볼 때 쓴다.")
    public List<Map<String, Object>> getSpcSummary(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "최근 몇 shot (권장 50)") Integer lastShots) {
        return call("getSpcSummary", "EXEC dbo.usp_GetSpcSummary ?, ?, ?", equipmentId, orDefault(lastShots, 50), asOf);
    }

    @Tool(description = "설비의 최근 N shot 을 구간별로 나눠 불량률과 불량 유형별 건수(SHORT_SHOT 미성형, FLASH 버, DIMENSION 치수, WARPAGE 휨)를 조회한다. "
            + "실제로 제품 품질에 영향이 있었는지 확인할 때 쓴다.")
    public List<Map<String, Object>> getDefectSummary(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "최근 몇 shot (권장 300)") Integer lastShots,
            @ToolParam(description = "구간 크기 (권장 50)") Integer windowSize) {
        return call("getDefectSummary", "EXEC dbo.usp_GetDefectSummary ?, ?, ?, ?",
                equipmentId, orDefault(lastShots, 300), orDefault(windowSize, 50), asOf);
    }

    @Tool(description = "승인된 과거 부적합(NCR) 사례 중 관련 센서·불량 유형·설비가 비슷한 사례를 찾는다. "
            + "결과의 root_cause(확정 원인)와 evidence(당시 근거)를 현재 상황과 비교해 원인을 추정한다. "
            + "source 는 사람이 작성한 사례인지, AI 초안을 사람이 승인한 사례인지 나타낸다. "
            + "situation 에 지금 관찰한 현상을 쓰면 표현이 달라도 뜻이 비슷한 사례를 함께 찾는다(semantic_similarity).")
    public List<Map<String, Object>> findSimilarNcr(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "이상이 보인 센서 키를 쉼표로 구분, 예: barrelTemp,injectionPressure") String sensorKeys,
            @ToolParam(description = "가장 많은 불량 유형 (없으면 빈 문자열)", required = false) String defectType,
            @ToolParam(description = "지금 관찰한 현상 요약 한두 문장 — 어느 센서가 어떻게 변했고 불량이 있는지 (선택)",
                    required = false) String situation) {
        String dt = defectType == null || defectType.isBlank() ? null : defectType;
        String vector = vectorizer == null ? null : vectorizer.apply(situation);
        String traceLine = "findSimilarNcr" + argsText(equipmentId, sensorKeys, dt, 5, asOf)
                + (situation == null || situation.isBlank() ? "" : " situation=\"" + shorten(situation, 120) + "\"")
                + (vector == null ? "" : " +의미검색");
        // 기준 시점까지 승인된 사례만: 지난 경보를 다시 분석할 때, 그 뒤에 승인된 사례(미래 지식)를 보지 않게
        return callTraced(traceLine, "EXEC dbo.usp_FindSimilarNcr ?, ?, ?, ?, ?, ?",
                equipmentId, sensorKeys, dt, 5, asOf, vector);
    }

    @Tool(description = "설비의 정비 이력(정비일, 유형, 부품, 내용, 경과 일수)을 최근 순으로 조회한다. "
            + "부품 교체 주기, 미실시 권고 사항 등 원인 추정의 단서를 찾을 때 쓴다.")
    public List<Map<String, Object>> getMaintenanceHistory(
            @ToolParam(description = "설비 ID") String equipmentId) {
        return call("getMaintenanceHistory", "EXEC dbo.usp_GetMaintenanceHistory ?, ?", equipmentId, 10);
    }

    // ── 공통 ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> call(String tool, String sql, Object... args) {
        return callTraced(tool + argsText(args), sql, args);
    }

    private List<Map<String, Object>> callTraced(String traceLine, String sql, Object... args) {
        trace.add(traceLine + " @" + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> clean = new LinkedHashMap<>();
                row.forEach((k, v) -> clean.put(k, simplify(v)));
                out.add(clean);
            }
            if (observations != null) {
                observations.addArgs(args);   // 조회 범위(300 shot 등)
                observations.addRows(out);    // 모델이 받은 값과 똑같은 값 (simplify 뒤)
            }
            return out;
        } catch (RuntimeException e) {
            // 잘못된 인자(없는 센서 키 등)는 오류 내용을 모델에게 돌려줘 스스로 고치게 한다
            trace.add("  ↳ 오류: " + rootMessage(e));
            return List.of(Map.of("error", rootMessage(e)));
        }
    }

    /** DB 고유 타입(날짜·오프셋 등)을 모델이 읽기 쉬운 기본 타입으로 바꾼다. */
    private static Object simplify(Object v) {
        if (v == null || v instanceof String || v instanceof Integer || v instanceof Long || v instanceof Boolean) {
            return v;
        }
        if (v instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return n;
        }
        return v.toString();
    }

    private static String argsText(Object... args) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(args[i]);
        }
        return sb.append(')').toString();
    }

    private static String shorten(String s, int max) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static int orDefault(Integer v, int dflt) {
        return v == null || v < 1 ? dflt : v;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
