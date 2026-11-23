package com.qualiguard.qms.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qualiguard.qms.agent.pipeline.AgentPipeline;

/**
 * 에이전트 수동 실행·조회 API (시험과 평가용).
 *
 *   GET  /api/agent/anomalies?equipmentId=IMM-01&status=NEW   경보 목록
 * 분석은 역할 분리형 파이프라인(분류 → 원인 분석 → 검증 → 작성, ADR-016)을 거치며, API 는 끝날 때까지 기다려 결과를 돌려준다.
 *
 *   POST /api/agent/analyze-next                              가장 오래된 미처리 사건 1건 분석
 *   POST /api/agent/analyze/{anomalyId}                       지정 경보를 시작점으로 분석
 *        ?replay=true&tag=...                                 시간 순 재생: 시작 경보 시각까지의 경보·데이터만 사용,
 *                                                             tag 는 평가 실행 구분(이전 판단도 같은 tag 에서만 찾음)
 *   POST /api/agent/recheck/{analysisId}?asOfShot=...         관찰(OBSERVE) 판단 뒤 새 경보 없이 재확인
 *                                                             (asOfShot 생략 시 관찰 기준 shot + recheck-shots)
 *   GET  /api/agent/config                                    재확인 설정과 평가 기록용 실행 설정(모델·작성 방식 등)
 *   GET  /api/agent/analyses?top=20                           분석 기록
 *   GET  /api/agent/budget                                    오늘 토큰 사용량·하루 예산 (로드맵 O5)
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final IncidentAnalyzer analyzer;
    private final AgentPipeline pipeline;
    private final JdbcTemplate jdbc;
    private final AgentProperties props;
    private final Environment env;
    private final TokenBudget budget;

    public AgentController(IncidentAnalyzer analyzer, AgentPipeline pipeline, JdbcTemplate jdbc, AgentProperties props,
            Environment env, TokenBudget budget) {
        this.analyzer = analyzer;
        this.pipeline = pipeline;
        this.jdbc = jdbc;
        this.props = props;
        this.env = env;
        this.budget = budget;
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
        var seed = analyzer.nextSeed();
        if (seed.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "분석할 미처리 경보가 없습니다."));
        }
        return analyze(seed.get(), false, null);
    }

    @PostMapping("/analyze/{anomalyId}")
    public ResponseEntity<?> analyze(@PathVariable String anomalyId,
            @RequestParam(defaultValue = "false") boolean replay,
            @RequestParam(required = false) String tag) {
        try {
            return ResponseEntity.ok(pipeline.analyzeAndWait(anomalyId, replay, tag));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (AgentPipeline.WaitException e) {
            return ResponseEntity.status(504).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/recheck/{analysisId}")
    public ResponseEntity<?> recheck(@PathVariable int analysisId, @RequestParam(required = false) Long asOfShot) {
        try {
            return ResponseEntity.ok(pipeline.recheckAndWait(analysisId, asOfShot));
        } catch (AgentPipeline.WaitException e) {
            return ResponseEntity.status(504).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        // 평가 스크립트가 결과와 함께 저장한다: 어떤 설정으로 잰 수치인지 나중에 확인할 수 있게 (로드맵 E1)
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recheckShots", props.recheckShots());
        m.put("recheckMax", props.recheckMax());
        m.put("verifyMaxRetries", props.verifyMaxRetries());
        m.put("model", env.getProperty("spring.ai.google.genai.chat.model", "gemini-3.8-flash"));
        m.put("temperature", env.getProperty("spring.ai.google.genai.chat.temperature"));
        m.put("transport", env.getProperty("qg.agent.pipeline.transport", "kafka"));
        m.put("promptVersion", AgentPrompts.VERSION);   // 로드맵 O1: 어떤 프롬프트로 잰 수치인지
        m.put("analysisMode", analyzer.analysisMode());   // 로드맵 A1: 실제 적용된 값
        m.put("writerMode", env.getProperty("qg.agent.writer.mode", "analysis-text"));
        m.put("vectorEnabled", env.getProperty("qg.knowledge.vector-enabled", Boolean.class, false));
        m.put("actionEnabled", env.getProperty("qg.agent.action.enabled", Boolean.class, false));
        m.put("dailyTokenBudget", budget.settings().dailyTokens());   // 로드맵 O5 (0 이면 끔)
        return m;
    }

    /** 오늘 토큰 사용량. 예산을 넘으면 자동 분석만 멈추고 수동 분석(이 컨트롤러의 POST)은 그대로 된다. */
    @GetMapping("/budget")
    public Map<String, Object> budget() {
        TokenBudget.Status s = budget.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", s.day().toString());
        m.put("limit", s.limit());
        m.put("used", s.used());
        m.put("remaining", s.remaining());
        m.put("autoPaused", s.exceeded());
        return m;
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> analyses(@RequestParam(defaultValue = "20") int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) g.analysis_id, g.equipment_id, g.trigger_type, g.as_of_shot, g.parent_analysis_id,
                       g.decision, g.cause_code, g.confidence,
                       g.latency_ms, n.ncr_no, g.tool_trace, g.error_message, g.attempts, g.verify_status,
                       g.incident_id, g.pipeline_json, g.hold_status, g.hold_reason, g.prompt_version,
                       CONVERT(VARCHAR(33), g.created_at, 127) AS created_at
                FROM dbo.agent_analysis AS g
                LEFT JOIN dbo.ncr AS n ON n.ncr_id = g.ncr_id
                ORDER BY g.analysis_id DESC
                """, top);
    }
}
