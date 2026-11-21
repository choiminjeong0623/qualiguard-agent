package com.qualiguard.qms.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qualiguard.qms.agent.QualityTools;

/**
 * 에이전트 도구를 MCP 서버로 노출한다 (ADR-013). 다른 AI 클라이언트(Claude Desktop 등)도 같은 저장 프로시저로 QMS 데이터를 조회한다.
 *
 * 기준 시점(asOf) 원칙은 그대로다: 시각을 직접 받지 않고, 기준 경보(anomalyId)를 받으면 그 경보 시각까지의 데이터만 조회한다.
 * 기준 경보를 주지 않으면 지금까지의 데이터(운영 조회)다. 내부 구현은 에이전트의 QualityTools 를 그대로 재사용한다.
 */
public class McpQualityTools {

    private final JdbcTemplate jdbc;
    private final Function<String, String> vectorizer;

    public McpQualityTools(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public McpQualityTools(JdbcTemplate jdbc, Function<String, String> vectorizer) {
        this.jdbc = jdbc;
        this.vectorizer = vectorizer;
    }

    /** 기준 경보의 발생 시각. 경보가 없으면 null(지금까지), 없는 번호면 예외. */
    String asOf(String anomalyId) {
        if (anomalyId == null || anomalyId.isBlank()) {
            return null;
        }
        List<String> ts = jdbc.queryForList(
                "SELECT CONVERT(VARCHAR(33), detected_at, 127) FROM dbo.anomaly_event WHERE anomaly_id = ?",
                String.class, anomalyId.strip());
        if (ts.isEmpty()) {
            throw new IllegalArgumentException("기준 경보를 찾을 수 없습니다: " + anomalyId);
        }
        return ts.get(0);
    }

    private QualityTools tools(String anomalyId) {
        return new QualityTools(jdbc, new ArrayList<>(), asOf(anomalyId), System.nanoTime(), vectorizer);
    }

    @Tool(description = "설비의 이상 경보 목록을 최근 순으로 조회한다. 결과의 anomaly_id 를 다른 도구의 기준 경보로 쓰면 "
            + "그 경보 당시까지의 데이터만 본다.")
    public List<Map<String, Object>> listAnomalies(
            @ToolParam(description = "설비 ID, 예: IMM-01 (없으면 전체)", required = false) String equipmentId,
            @ToolParam(description = "상태 NEW/ANALYZING/NCR_DRAFTED/DISMISSED (없으면 전체)", required = false) String status,
            @ToolParam(description = "최대 건수 (기본 20)", required = false) Integer top) {
        int n = top == null || top < 1 ? 20 : Math.min(top, 200);
        String eq = equipmentId == null || equipmentId.isBlank() ? null : equipmentId;
        String st = status == null || status.isBlank() ? null : status;
        return jdbc.queryForList("""
                SELECT TOP (?) anomaly_id, equipment_id, shot_no, rule_code, sensor_key, sensor_name, status, message,
                       CONVERT(VARCHAR(33), detected_at, 127) AS detected_at
                FROM dbo.anomaly_event
                WHERE (? IS NULL OR equipment_id = ?) AND (? IS NULL OR status = ?)
                ORDER BY detected_at DESC, anomaly_id
                """, n, eq, eq, st, st);
    }

    @Tool(description = "설비의 최근 N shot 센서별 평균·표준편차·검사 규격·공정 능력 지수(Cpk)·규격 이탈 수. "
            + "anomalyId 를 주면 그 경보 시각까지의 데이터만 본다.")
    public List<Map<String, Object>> getSpcSummary(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "최근 몇 shot (권장 50)", required = false) Integer lastShots,
            @ToolParam(description = "기준 경보 ID (없으면 지금까지)", required = false) String anomalyId) {
        return tools(anomalyId).getSpcSummary(equipmentId, lastShots);
    }

    @Tool(description = "센서 추이를 구간별 평균·최소·최대·표준편차·불량 수와 검사 규격으로 조회. 센서 키: moldTemp, barrelTemp, "
            + "injectionPressure, holdingPressure, cushion, cycleTime, motorCurrent. anomalyId 를 주면 그 경보 시각까지만.")
    public List<Map<String, Object>> getSensorTrend(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "센서 키") String sensorKey,
            @ToolParam(description = "최근 몇 shot (넓은 추이 300, 최근 변화 60)", required = false) Integer lastShots,
            @ToolParam(description = "몇 shot 씩 묶을지 (넓은 추이 20, 최근 변화 5)", required = false) Integer bucketSize,
            @ToolParam(description = "기준 경보 ID (없으면 지금까지)", required = false) String anomalyId) {
        return tools(anomalyId).getSensorTrend(equipmentId, sensorKey, lastShots, bucketSize);
    }

    @Tool(description = "최근 N shot 을 구간별로 나눠 불량률과 불량 유형별 건수. anomalyId 를 주면 그 경보 시각까지만.")
    public List<Map<String, Object>> getDefectSummary(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "최근 몇 shot (권장 300)", required = false) Integer lastShots,
            @ToolParam(description = "구간 크기 (권장 50)", required = false) Integer windowSize,
            @ToolParam(description = "기준 경보 ID (없으면 지금까지)", required = false) String anomalyId) {
        return tools(anomalyId).getDefectSummary(equipmentId, lastShots, windowSize);
    }

    @Tool(description = "승인된 과거 부적합 사례 중 관련 센서·불량 유형·설비가 비슷한 사례 (root_cause 확정 원인, evidence 당시 근거, "
            + "source 사람 작성/AI 초안 승인). anomalyId 를 주면 그 경보 시각까지 승인된 사례만.")
    public List<Map<String, Object>> findSimilarNcr(
            @ToolParam(description = "설비 ID") String equipmentId,
            @ToolParam(description = "이상이 보인 센서 키, 쉼표 구분") String sensorKeys,
            @ToolParam(description = "가장 많은 불량 유형", required = false) String defectType,
            @ToolParam(description = "지금 관찰한 현상 요약 (의미 검색, 선택)", required = false) String situation,
            @ToolParam(description = "기준 경보 ID (없으면 지금까지)", required = false) String anomalyId) {
        return tools(anomalyId).findSimilarNcr(equipmentId, sensorKeys, defectType, situation);
    }

    @Tool(description = "설비 정비 이력(정비일, 유형, 부품, 내용, 경과 일수) 최근 순.")
    public List<Map<String, Object>> getMaintenanceHistory(@ToolParam(description = "설비 ID") String equipmentId) {
        return tools(null).getMaintenanceHistory(equipmentId);
    }
}
