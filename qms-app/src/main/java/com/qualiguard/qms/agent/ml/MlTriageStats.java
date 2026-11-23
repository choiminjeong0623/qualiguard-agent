package com.qualiguard.qms.agent.ml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ML 1차 분류 일치율 (shadow 로 먼저 재고 skip 을 켤지 정하는 근거).
 *
 *   GET /api/agent/ml-triage/stats?days=7
 *
 * - routes       : 사건이 어디로 갔는가 (SHADOW / SKIPPED / AUDIT / LLM_NOISE / LLM_LOW / LLM_UNFAMILIAR)
 * - wouldSkip    : skip 규칙이었다면 LLM 을 건너뛰었을 사건 비율 (= 예상 LLM 호출 감소)
 * - vsLlm        : LLM 도 분석한 사건(shadow·표본 점검 등)에서 ML 원인과 LLM 원인이 같은 비율.
 *                  wouldSkipAgreement 가 핵심: 건너뛰었을 사건에서 LLM 과 같았던 비율
 * - vsHuman      : 사람이 승인한 초안의 확정 원인과 ML·LLM 이 같은 비율
 */
@RestController
@RequestMapping("/api/agent/ml-triage")
@Component
public class MlTriageStats {

    /** 불일치 예시 개수 */
    static final int SAMPLES = 10;

    private final JdbcTemplate jdbc;

    public MlTriageStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "7") int days) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT j.incident_id, j.equipment_id, j.mode, j.route, j.ml_cause, CAST(j.ml_probability AS FLOAT) AS p,
                       j.would_skip, j.llm_cause,
                       CASE WHEN n.status IN ('APPROVED', 'CLOSED') THEN n.cause_code END AS human_cause
                FROM dbo.agent_ml_judgment AS j
                OUTER APPLY (SELECT TOP 1 g.ncr_id FROM dbo.agent_analysis AS g
                             WHERE g.incident_id = j.incident_id ORDER BY g.analysis_id DESC) AS a
                LEFT JOIN dbo.ncr AS n ON n.ncr_id = a.ncr_id
                WHERE j.created_at >= DATEADD(DAY, -?, SYSDATETIMEOFFSET())
                ORDER BY j.created_at
                """, Math.max(1, days));
        return summarize(rows, days);
    }

    static Map<String, Object> summarize(List<Map<String, Object>> rows, int days) {
        Map<String, Integer> routes = new TreeMap<>();
        int wouldSkip = 0;
        int llmN = 0;
        int llmAgree = 0;
        int wsN = 0;
        int wsAgree = 0;
        int humanN = 0;
        int humanMl = 0;
        int humanLlm = 0;
        int humanLlmN = 0;
        List<Map<String, Object>> disagreements = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            routes.merge(String.valueOf(r.get("route")), 1, Integer::sum);
            boolean ws = truthy(r.get("would_skip"));
            wouldSkip += ws ? 1 : 0;
            String ml = (String) r.get("ml_cause");
            String llm = (String) r.get("llm_cause");
            if (llm != null) {
                llmN++;
                boolean same = Objects.equals(ml, llm);
                llmAgree += same ? 1 : 0;
                if (ws) {
                    wsN++;
                    wsAgree += same ? 1 : 0;
                    if (!same && disagreements.size() < SAMPLES) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("incidentId", r.get("incident_id"));
                        d.put("equipmentId", r.get("equipment_id"));
                        d.put("mlCause", ml);
                        d.put("mlProbability", r.get("p"));
                        d.put("llmCause", llm);
                        d.put("humanCause", r.get("human_cause"));
                        disagreements.add(d);
                    }
                }
            }
            String human = (String) r.get("human_cause");
            if (human != null) {
                humanN++;
                humanMl += human.equals(ml) ? 1 : 0;
                if (llm != null) {
                    humanLlmN++;
                    humanLlm += human.equals(llm) ? 1 : 0;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("incidents", rows.size());
        out.put("routes", routes);
        out.put("wouldSkip", rate(wouldSkip, rows.size()));
        Map<String, Object> vsLlm = new LinkedHashMap<>();
        vsLlm.put("compared", llmN);
        vsLlm.put("agreement", rate(llmAgree, llmN));
        vsLlm.put("wouldSkipCompared", wsN);
        vsLlm.put("wouldSkipAgreement", rate(wsAgree, wsN));
        vsLlm.put("wouldSkipDisagreements", disagreements);
        out.put("vsLlm", vsLlm);
        Map<String, Object> vsHuman = new LinkedHashMap<>();
        vsHuman.put("approved", humanN);
        vsHuman.put("mlAgreement", rate(humanMl, humanN));
        vsHuman.put("llmCompared", humanLlmN);
        vsHuman.put("llmAgreement", rate(humanLlm, humanLlmN));
        out.put("vsHuman", vsHuman);
        out.put("note", "wouldSkipAgreement 가 충분히 높고(예: 비교 30건 이상에서 95% 이상) 불일치 예시에 위험한 원인이 없을 때 skip 을 켜세요.");
        return out;
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0;
    }

    /** 비율 (0~1, 소수 셋째 자리). 분모가 0 이면 null. */
    static Double rate(int a, int b) {
        return b == 0 ? null : Math.round(1000.0 * a / b) / 1000.0;
    }
}
