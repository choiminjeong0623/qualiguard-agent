package com.qualiguard.qms.agent;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 수동 실행·조회 API (시험과 평가용).
 *
 *   GET  /api/agent/anomalies?equipmentId=IMM-01&status=NEW   경보 목록
 *   POST /api/agent/analyze-next                              가장 오래된 미처리 사건 1건 분석
 *   POST /api/agent/analyze/{anomalyId}                       지정 경보를 시작점으로 분석
 *   GET  /api/agent/analyses?top=20                           분석 기록
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final IncidentAnalyzer analyzer;
    private final JdbcTemplate jdbc;

    public AgentController(IncidentAnalyzer analyzer, JdbcTemplate jdbc) {
        this.analyzer = analyzer;
        this.jdbc = jdbc;
    }

    @GetMapping("/anomalies")
    public List<Map<String, Object>> anomalies(@RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) anomaly_id, equipment_id, shot_no, rule_code, sensor_key, sensor_name,
                       CAST(observed AS FLOAT) AS observed, status, message,
                       CONVERT(VARCHAR(33), detected_at, 127) AS detected_at
                FROM dbo.anomaly_event
                WHERE (? IS NULL OR equipment_id = ?) AND (? IS NULL OR status = ?)
                ORDER BY detected_at, anomaly_id
                """, top, equipmentId, equipmentId, status, status);
    }

    @PostMapping("/analyze-next")
    public ResponseEntity<?> analyzeNext() {
        return analyzer.analyzeNext()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("message", "분석할 미처리 경보가 없습니다.")));
    }

    @PostMapping("/analyze/{anomalyId}")
    public ResponseEntity<?> analyze(@PathVariable String anomalyId) {
        try {
            return ResponseEntity.ok(analyzer.analyze(anomalyId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> analyses(@RequestParam(defaultValue = "20") int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) g.analysis_id, g.equipment_id, g.decision, g.cause_code, g.confidence,
                       g.latency_ms, n.ncr_no, g.tool_trace, g.error_message,
                       CONVERT(VARCHAR(33), g.created_at, 127) AS created_at
                FROM dbo.agent_analysis AS g
                LEFT JOIN dbo.ncr AS n ON n.ncr_id = g.ncr_id
                ORDER BY g.analysis_id DESC
                """, top);
    }
}
