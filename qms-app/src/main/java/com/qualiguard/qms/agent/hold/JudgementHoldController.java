package com.qualiguard.qms.agent.hold;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qualiguard.qms.security.AppUser;

/**
 * 판단 보류 목록·처리 API (로드맵 F2, QMS "에이전트 조치" 화면의 판단 보류 카드).
 *
 *   GET  /api/qms/holds?status=PENDING&top=50&replay=false   보류 목록 (replay=true 면 평가 실행 기록도 포함)
 *   POST /api/qms/holds/{analysisId}/resolve  {"note": "..."} 검토자가 확인하고 처리 메모를 남김
 *   POST /api/qms/holds/{analysisId}/brief    분석 흐름 3줄 요약·현장 확인 항목 만들기 (HoldBriefService, 보조 정보)
 * /api/qms/** 라서 조회는 REVIEWER·VIEWER, 처리는 REVIEWER 만 (SecurityConfig, CSRF 확인 포함).
 */
@RestController
@RequestMapping("/api/qms/holds")
public class JudgementHoldController {

    private final JdbcTemplate jdbc;
    private final HoldBriefService briefs;

    public JudgementHoldController(JdbcTemplate jdbc, HoldBriefService briefs) {
        this.jdbc = jdbc;
        this.briefs = briefs;
    }

    @PostMapping("/{analysisId}/brief")
    public ResponseEntity<?> brief(@PathVariable int analysisId) {
        try {
            return ResponseEntity.ok(briefs.generate(analysisId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "50") int top, @RequestParam(defaultValue = "false") boolean replay) {
        String st = "RESOLVED".equalsIgnoreCase(status) ? "RESOLVED" : "PENDING";
        return jdbc.queryForList("""
                SELECT TOP (?) g.analysis_id, g.equipment_id, g.trigger_type, g.as_of_shot, g.parent_analysis_id,
                       g.decision, g.cause_code, g.confidence, g.verify_status, g.run_tag,
                       g.hold_status, g.hold_reason, g.hold_detail, g.hold_resolved_by, g.hold_note, g.hold_brief,
                       JSON_VALUE(g.result_json, '$.reasoningSummary') AS summary,
                       JSON_QUERY(g.result_json, '$.watchItems') AS watch_items,
                       CONVERT(VARCHAR(33), g.created_at, 127) AS created_at,
                       CONVERT(VARCHAR(33), g.hold_resolved_at, 127) AS hold_resolved_at
                FROM dbo.agent_analysis AS g
                WHERE g.hold_status = ? AND (? = 1 OR g.run_tag IS NULL)
                ORDER BY g.analysis_id DESC
                """, Math.min(Math.max(top, 1), 200), st, replay ? 1 : 0);
    }

    @PostMapping("/{analysisId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable int analysisId, @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {
        String note = body == null ? null : body.get("note");
        if (note == null || note.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "처리 메모를 적어 주세요 (무엇을 확인했고 어떻게 처리했는지)"));
        }
        int n = jdbc.update("""
                UPDATE dbo.agent_analysis
                SET hold_status = 'RESOLVED', hold_resolved_by = ?, hold_resolved_at = SYSDATETIMEOFFSET(), hold_note = ?
                WHERE analysis_id = ? AND hold_status = 'PENDING'
                """, actor(auth), note.length() > 1000 ? note.substring(0, 1000) : note, analysisId);
        if (n == 0) {
            return ResponseEntity.status(409).body(Map.of("error", "검토 대기 중인 판단 보류가 아닙니다"));
        }
        return ResponseEntity.ok(Map.of("analysisId", analysisId, "holdStatus", "RESOLVED"));
    }

    private static String actor(Authentication auth) {
        if (auth == null) {
            return "unknown";
        }
        return auth.getPrincipal() instanceof AppUser u ? u.getDisplayName() : auth.getName();
    }
}
