package com.qualiguard.qms.ncr;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * 부적합(NCR) 초안 검토·수정·승인/반려 (F-08).
 *
 * 상태 규칙(DRAFT 에서만 수정·승인·반려, 승인에는 확정 원인 필요)은 저장 프로시저가 한 트랜잭션에서 확인한다.
 * 이 클래스는 입력을 다듬고, 바뀐 항목을 이력으로 남기고, 프로시저 오류 번호를 HTTP 의미로 바꾼다.
 */
@Service
public class NcrReviewService {

    /** 수정할 수 있는 항목: 화면 이름 → DB 컬럼 */
    static final Map<String, String> EDITABLE = new LinkedHashMap<>();
    static {
        EDITABLE.put("title", "title");
        EDITABLE.put("phenomenon", "phenomenon");
        EDITABLE.put("suspectedCause", "suspected_cause");
        EDITABLE.put("rootCause", "root_cause");
        EDITABLE.put("evidence", "evidence");
        EDITABLE.put("recommendedAction", "recommended_action");
        EDITABLE.put("causeCode", "cause_code");
        EDITABLE.put("defectType", "defect_type");
        EDITABLE.put("relatedSensors", "related_sensors");
    }

    static final Set<String> CAUSE_CODES = Set.of("HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR",
            "SENSOR_FAULT", "MATERIAL", "NOISE", "UNKNOWN");
    static final Set<String> STATUSES = Set.of("DRAFT", "APPROVED", "REJECTED", "CLOSED");

    /** 검토 요청이 받아들여지지 않을 때. status 는 HTTP 상태(400/404/409). */
    public static class ReviewException extends RuntimeException {
        private final int status;

        public ReviewException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NcrReviewService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(String status, String equipmentId, int top) {
        String st = blankToNull(status);
        if (st != null && !STATUSES.contains(st)) {
            throw new ReviewException(400, "알 수 없는 상태입니다: " + st);
        }
        return jdbc.queryForList("EXEC dbo.usp_ListNcr ?, ?, ?", st, blankToNull(equipmentId),
                Math.max(1, Math.min(top, 500)));
    }

    /** 상세: 부적합 본문 + 연결된 경보 + 에이전트 분석 기록 + 검토 이력. */
    public Map<String, Object> detail(String ncrNo) {
        Map<String, Object> ncr;
        try {
            ncr = jdbc.queryForMap("""
                    SELECT ncr_id, ncr_no, equipment_id, anomaly_id, status, title, phenomenon, suspected_cause,
                           root_cause, evidence, recommended_action, action_taken, cause_code, confidence,
                           defect_type, related_sensors, created_by, approved_by, reviewed_by, review_comment,
                           CONVERT(VARCHAR(33), occurred_at, 127) AS occurred_at,
                           CONVERT(VARCHAR(33), created_at, 127)  AS created_at,
                           CONVERT(VARCHAR(33), reviewed_at, 127) AS reviewed_at,
                           CONVERT(VARCHAR(33), approved_at, 127) AS approved_at
                    FROM dbo.ncr WHERE ncr_no = ?
                    """, ncrNo);
        } catch (EmptyResultDataAccessException e) {
            throw new ReviewException(404, "부적합을 찾을 수 없습니다: " + ncrNo);
        }
        Object ncrId = ncr.remove("ncr_id");
        Map<String, Object> out = new LinkedHashMap<>(ncr);
        out.put("anomalies", jdbc.queryForList("""
                SELECT a.anomaly_id, a.shot_no, a.rule_code, a.sensor_key, a.sensor_name, a.message, a.status,
                       CONVERT(VARCHAR(33), a.detected_at, 127) AS detected_at
                FROM dbo.ncr_anomaly AS x JOIN dbo.anomaly_event AS a ON a.anomaly_id = x.anomaly_id
                WHERE x.ncr_id = ? ORDER BY a.detected_at, a.anomaly_id
                """, ncrId));
        out.put("analyses", jdbc.queryForList("""
                SELECT analysis_id, trigger_type, as_of_shot, decision, cause_code, confidence, latency_ms,
                       result_json, tool_trace, CONVERT(VARCHAR(33), created_at, 127) AS created_at
                FROM dbo.agent_analysis WHERE ncr_id = ? ORDER BY analysis_id
                """, ncrId));
        out.put("history", jdbc.queryForList("""
                SELECT action, actor, from_status, to_status, changes_json, comment,
                       CONVERT(VARCHAR(33), created_at, 127) AS created_at
                FROM dbo.ncr_history WHERE ncr_id = ? ORDER BY history_id
                """, ncrId));
        return out;
    }

    /** 초안 수정. 실제로 바뀐 항목만 반영하고, 바뀐 내용(전·후)을 이력에 남긴다. */
    public Map<String, Object> update(String ncrNo, String actor, Map<String, Object> body) {
        String who = requireActor(actor);
        Map<String, Object> current = detail(ncrNo);
        Map<String, String> next = new LinkedHashMap<>();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : EDITABLE.entrySet()) {
            if (body == null || !body.containsKey(f.getKey())) {
                continue;
            }
            String value = body.get(f.getKey()) == null ? "" : String.valueOf(body.get(f.getKey())).strip();
            if (f.getKey().equals("causeCode")) {
                value = value.toUpperCase();
                if (!CAUSE_CODES.contains(value)) {
                    throw new ReviewException(400, "알 수 없는 원인 코드입니다: " + value);
                }
            }
            String before = current.get(f.getValue()) == null ? "" : String.valueOf(current.get(f.getValue()));
            if (!Objects.equals(before, value)) {
                next.put(f.getKey(), value);
                changes.put(f.getKey(), Map.of("before", before, "after", value));
            }
        }
        if (changes.isEmpty()) {
            return current;
        }
        exec(() -> jdbc.update("EXEC dbo.usp_UpdateNcrDraft ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?",
                ncrNo, who, next.get("title"), next.get("phenomenon"), next.get("suspectedCause"),
                next.get("rootCause"), next.get("evidence"), next.get("recommendedAction"), next.get("causeCode"),
                emptyToNull(next.get("defectType")), emptyToNull(next.get("relatedSensors")),
                objectMapper.writeValueAsString(changes)));
        return detail(ncrNo);
    }

    public Map<String, Object> approve(String ncrNo, String actor, String comment) {
        String who = requireActor(actor);
        exec(() -> jdbc.update("EXEC dbo.usp_ReviewNcr ?, 'APPROVE', ?, ?", ncrNo, who, blankToNull(comment)));
        return detail(ncrNo);
    }

    public Map<String, Object> reject(String ncrNo, String actor, String reason) {
        String who = requireActor(actor);
        if (blankToNull(reason) == null) {
            throw new ReviewException(400, "반려 사유를 입력하세요.");
        }
        exec(() -> jdbc.update("EXEC dbo.usp_ReviewNcr ?, 'REJECT', ?, ?", ncrNo, who, reason.strip()));
        return detail(ncrNo);
    }

    // ── 보조 ─────────────────────────────────────────────────────────

    private static String requireActor(String actor) {
        String a = blankToNull(actor);
        if (a == null) {
            throw new ReviewException(400, "검토자 이름을 입력하세요.");
        }
        return a.length() > 50 ? a.substring(0, 50) : a;
    }

    /** 프로시저의 THROW 번호(50400/50404/50409)를 HTTP 상태로 바꾼다. */
    private static void exec(Runnable call) {
        try {
            call.run();
        } catch (DataAccessException e) {
            Throwable t = e;
            while (t != null) {
                if (t instanceof SQLException sql && sql.getErrorCode() >= 50400 && sql.getErrorCode() <= 50499) {
                    throw new ReviewException(sql.getErrorCode() - 50000, sql.getMessage());
                }
                t = t.getCause();
            }
            throw e;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
