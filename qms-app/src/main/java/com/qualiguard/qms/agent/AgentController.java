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
 *        ?replay=true&tag=...                                 시간 순 재생: 시작 경보 시각까지의 경보·데이터만 사용,
 *                                                             tag 는 평가 실행 구분(이전 판단도 같은 tag 에서만 찾음)
 *   POST /api/agent/recheck/{analysisId}?asOfShot=...         관찰(OBSERVE) 판단 뒤 새 경보 없이 재확인
 *                                                             (asOfShot 생략 시 관찰 기준 shot + recheck-shots)
 *   GET  /api/agent/config                                    재확인 설정 (평가 스크립트용)
 *   GET  /api/agent/analyses?top=20                           분석 기록
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final IncidentAnalyzer analyzer;
    private final JdbcTemplate jdbc;
    private final AgentProperties props;

    public AgentController(IncidentAnalyzer analyzer, JdbcTemplate jdbc, AgentProperties props) {
        this.analyzer = analyzer;
        this.jdbc = jdbc;
        this.props = props;
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
    public ResponseEntity<?> analyze(@PathVariable String anomalyId,
            @RequestParam(defaultValue = "false") boolean replay,
            @RequestParam(required = false) String tag) {
        try {
            return ResponseEntity.ok(analyzer.analyze(anomalyId, replay, tag));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/recheck/{analysisId}")
    public ResponseEntity<?> recheck(@PathVariable int analysisId, @RequestParam(required = false) Long asOfShot) {
        try {
            return ResponseEntity.ok(analyzer.recheck(analysisId, asOfShot));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("recheckShots", props.recheckShots(), "recheckMax", props.recheckMax());
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> analyses(@RequestParam(defaultValue = "20") int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) g.analysis_id, g.equipment_id, g.trigger_type, g.as_of_shot, g.parent_analysis_id,
                       g.decision, g.cause_code, g.confidence,
                       g.latency_ms, n.ncr_no, g.tool_trace, g.error_message,
                       CONVERT(VARCHAR(33), g.created_at, 127) AS created_at
                FROM dbo.agent_analysis AS g
                LEFT JOIN dbo.ncr AS n ON n.ncr_id = g.ncr_id
                ORDER BY g.analysis_id DESC
                """, top);
    }
}
