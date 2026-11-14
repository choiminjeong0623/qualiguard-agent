package com.qualiguard.qms.agent;

import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 경보 → 사건 묶기 → 에이전트 분석 → 결과 저장.
 *
 * 1) 사건 묶기: 시작 경보(seed)와 같은 설비에서 일정 shot·시간 안에 난 미처리 경보를 하나의 사건으로 묶는다.
 *    경보마다 LLM 을 부르지 않기 위해서다 (비용 절감, 같은 원인의 중복 보고서 방지).
 * 2) 분석: 에이전트가 도구(저장 프로시저)로 근거를 모아 AnalysisResult 를 돌려준다.
 * 3) 저장:
 *    - NCR_DRAFT    → 부적합 초안 생성. 같은 설비·같은 원인의 최근 초안이 있으면 새로 만들지 않고 그 초안에 경보를 연결
 *    - SENSOR_CHECK → 경보 종결(DISMISSED), 측정 계통 점검 권고는 분석 기록에 남김
 *    - OBSERVE      → 경보 종결(DISMISSED)
 *    모든 분석은 판단 근거·도구 호출 순서·소요 시간과 함께 agent_analysis 에 기록한다.
 */
@Service
public class IncidentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalyzer.class);
    private static final Set<String> DECISIONS = Set.of("NCR_DRAFT", "SENSOR_CHECK", "OBSERVE");

    public record Outcome(Integer analysisId, String equipmentId, String decision, String causeCode, Integer confidence,
            String ncrNo, boolean mergedIntoExistingNcr, List<String> anomalyIds, long latencyMs,
            List<String> toolTrace, AnalysisResult result, String error) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final AgentProperties props;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final String model;
    private volatile ChatClient chatClient;

    public IncidentAnalyzer(JdbcTemplate jdbc, ObjectMapper objectMapper, TransactionTemplate tx,
            AgentProperties props, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            @Value("${spring.ai.google.genai.chat.model:gemini-3.8-flash}") String model) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.props = props;
        this.chatClientBuilder = chatClientBuilder;
        this.model = model;
    }

    /** 가장 오래된 미처리 경보부터 사건 1건을 분석한다. 같은 경보로 2번 실패하면 건너뛴다. */
    public Optional<Outcome> analyzeNext() {
        List<String> ids = jdbc.queryForList("""
                SELECT TOP 1 a.anomaly_id
                FROM dbo.anomaly_event AS a
                WHERE a.status = 'NEW'
                  AND (SELECT COUNT(*) FROM dbo.agent_analysis AS g
                        WHERE g.seed_anomaly_id = a.anomaly_id AND g.decision = 'FAILED') < 2
                ORDER BY a.detected_at, a.anomaly_id
                """, String.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(analyze(ids.get(0)));
    }

    /** 지정한 경보를 시작점으로 사건을 분석한다 (평가·재분석용: 이미 처리된 경보도 시작점이 될 수 있다). */
    public synchronized Outcome analyze(String seedAnomalyId) {
        Map<String, Object> seed;
        try {
            seed = jdbc.queryForMap("""
                    SELECT anomaly_id, equipment_id, shot_no, evidence_json,
                           CONVERT(VARCHAR(33), detected_at, 127) AS detected_at
                    FROM dbo.anomaly_event WHERE anomaly_id = ?
                    """, seedAnomalyId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("경보를 찾을 수 없습니다: " + seedAnomalyId);
        }
        String equipmentId = (String) seed.get("equipment_id");
        long seedShot = ((Number) seed.get("shot_no")).longValue();
        String seedTs = (String) seed.get("detected_at");

        // ── 1) 사건 묶기 ─────────────────────────────────────────────
        List<Map<String, Object>> anomalies = jdbc.queryForList("""
                SELECT anomaly_id, rule_code, rule_name, sensor_key, sensor_name, shot_no, observed,
                       lower_limit, upper_limit, message, CONVERT(VARCHAR(33), detected_at, 127) AS detected_at
                FROM dbo.anomaly_event
                WHERE equipment_id = ?
                  AND (anomaly_id = ? OR status = 'NEW')
                  AND shot_no BETWEEN ? AND ?
                  AND detected_at BETWEEN CAST(? AS DATETIMEOFFSET(3))
                                      AND DATEADD(MINUTE, ?, CAST(? AS DATETIMEOFFSET(3)))
                ORDER BY detected_at, anomaly_id
                """, equipmentId, seedAnomalyId, seedShot, seedShot + props.incidentShotWindow(),
                seedTs, props.incidentMinutes(), seedTs);
        List<String> anomalyIds = anomalies.stream().map(a -> (String) a.get("anomaly_id")).toList();
        // 조회 기준 시점 = 사건의 마지막 경보 시각 (그 이후 데이터는 보지 않는다)
        String asOf = anomalies.isEmpty() ? seedTs : (String) anomalies.get(anomalies.size() - 1).get("detected_at");
        setStatus(anomalyIds, "ANALYZING");

        Map<String, Object> seedEvidence = readMap((String) seed.get("evidence_json"));
        String userPrompt = buildUserPrompt(equipmentId, anomalies, seedEvidence);

        // ── 2) 에이전트 분석 ─────────────────────────────────────────
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        AnalysisResult result;
        try {
            // 응답 전체 텍스트를 받아 직접 해석한다.
            // (Gemini 3 계열은 사고 요약이 마크다운으로 함께 올 수 있어 entity() 자동 변환이 실패할 수 있음)
            ChatResponse response = chatClient().prompt()
                    .system(AgentPrompts.SYSTEM)
                    .user(userPrompt)
                    .tools(new QualityTools(jdbc, trace, asOf))
                    .call()
                    .chatResponse();
            String text = allText(response);
            AnalysisResult parsed = parseResult(text);
            if (parsed == null) {
                // 형식만 다시 요청 (도구 없이 1회 — 조사를 반복하지 않으므로 비용이 적다)
                trace.add("repairJsonFormat()");
                String repaired = allText(chatClient().prompt()
                        .system(AgentPrompts.REPAIR)
                        .user(text)
                        .call()
                        .chatResponse());
                parsed = parseResult(repaired);
                if (parsed == null) {
                    throw new IllegalStateException("분석 결과를 JSON 으로 읽지 못함. 응답 앞부분: " + preview(text));
                }
            }
            result = parsed;   // result 는 한 번만 대입 (아래 트랜잭션 람다에서 쓰기 위해)
            if (result == null || result.decision() == null
                    || !DECISIONS.contains(result.decision().toUpperCase(Locale.ROOT))) {
                throw new IllegalStateException("에이전트 응답 형식 오류: " + result);
            }
        } catch (RuntimeException e) {
            long ms = elapsedMs(t0);
            setStatus(anomalyIds, "NEW");   // 다시 분석할 수 있게 되돌린다
            String error = causeChain(e);
            Integer id = insertAnalysis(equipmentId, seedAnomalyId, anomalyIds, "FAILED", null, null, null,
                    trace, ms, null, error);
            log.error("[에이전트] 분석 실패 — {} 경보 {}건: {}", equipmentId, anomalyIds.size(), error, e);
            return new Outcome(id, equipmentId, "FAILED", null, null, null, false, anomalyIds, ms, trace, null,
                    error);
        }
        long ms = elapsedMs(t0);

        // ── 3) 결과 저장 (하나의 트랜잭션) ────────────────────────────
        String decision = result.decision().toUpperCase(Locale.ROOT);
        String causeCode = result.causeCode() == null ? "UNKNOWN" : result.causeCode().toUpperCase(Locale.ROOT);
        int confidence = result.confidence() == null ? 0 : Math.max(0, Math.min(100, result.confidence()));
        String resultJson = objectMapper.writeValueAsString(result);

        Outcome outcome = tx.execute(status -> {
            String ncrNo = null;
            boolean merged = false;
            Integer ncrId = null;
            if (decision.equals("NCR_DRAFT")) {
                List<Map<String, Object>> open = jdbc.queryForList("""
                        SELECT TOP 1 ncr_id, ncr_no FROM dbo.ncr
                        WHERE equipment_id = ? AND status = 'DRAFT' AND created_by = 'AGENT' AND cause_code = ?
                          AND created_at >= DATEADD(MINUTE, -?, SYSDATETIMEOFFSET())
                        ORDER BY created_at DESC
                        """, equipmentId, causeCode, props.mergeMinutes());
                if (!open.isEmpty()) {
                    ncrId = ((Number) open.get(0).get("ncr_id")).intValue();
                    ncrNo = (String) open.get(0).get("ncr_no");
                    merged = true;
                } else {
                    int seq = jdbc.queryForObject("SELECT NEXT VALUE FOR dbo.seq_ncr_draft", Integer.class);
                    ncrNo = String.format("AI-%d-%04d", Year.now().getValue(), seq);
                    ncrId = jdbc.queryForObject("""
                            INSERT INTO dbo.ncr (ncr_no, equipment_id, anomaly_id, defect_type, related_sensors, title,
                                                 phenomenon, suspected_cause, evidence, recommended_action, status,
                                                 created_by, occurred_at, cause_code, confidence)
                            OUTPUT INSERTED.ncr_id
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 'AGENT', CAST(? AS DATETIMEOFFSET(3)), ?, ?)
                            """, Integer.class,
                            ncrNo, equipmentId, seedAnomalyId, topDefectType(seedEvidence),
                            relatedSensors(anomalies), truncate(result.title(), 200),
                            truncate(result.phenomenon(), 1000), truncate(result.suspectedCause(), 1000),
                            evidenceText(result), truncate(result.recommendedAction(), 1000),
                            seedTs, causeCode, confidence);
                }
                for (String id : anomalyIds) {
                    jdbc.update("""
                            INSERT INTO dbo.ncr_anomaly (ncr_id, anomaly_id)
                            SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM dbo.ncr_anomaly WHERE ncr_id = ? AND anomaly_id = ?)
                            """, ncrId, id, ncrId, id);
                }
                setStatus(anomalyIds, "NCR_DRAFTED");
            } else {
                setStatus(anomalyIds, "DISMISSED");
            }
            Integer analysisId = insertAnalysis(equipmentId, seedAnomalyId, anomalyIds, decision, causeCode,
                    confidence, resultJson, trace, ms, ncrId, null);
            return new Outcome(analysisId, equipmentId, decision, causeCode, confidence, ncrNo, merged,
                    anomalyIds, ms, trace, result, null);
        });

        log.info("[에이전트] {} 경보 {}건 → {} / {} (확신도 {}) {}{} — 도구 {}회, {}ms",
                equipmentId, anomalyIds.size(), decision, causeCode, confidence,
                outcome.ncrNo() == null ? "" : outcome.ncrNo(),
                outcome.mergedIntoExistingNcr() ? "(기존 초안에 연결)" : "", trace.size(), ms);
        return outcome;
    }

    // ── 보조 ─────────────────────────────────────────────────────────

    private ChatClient chatClient() {
        ChatClient c = chatClient;
        if (c == null) {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                throw new IllegalStateException("LLM 이 설정되지 않았습니다. .env 의 GEMINI_API_KEY 를 확인하세요.");
            }
            c = builder.build();
            chatClient = c;
        }
        return c;
    }

    private String buildUserPrompt(String equipmentId, List<Map<String, Object>> anomalies,
            Map<String, Object> seedEvidence) {
        List<Map<String, Object>> alarmList = new ArrayList<>();
        for (Map<String, Object> a : anomalies) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shotNo", a.get("shot_no"));
            m.put("rule", a.get("rule_name"));
            m.put("sensorKey", a.get("sensor_key"));
            m.put("sensorName", a.get("sensor_name"));
            m.put("message", a.get("message"));
            alarmList.add(m);
        }
        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("equipmentId", equipmentId);
        incident.put("alarms", alarmList);
        incident.put("firstAlarmEvidence", seedEvidence);
        return "다음 경보 사건을 조사하고 분석 결과를 작성해 주세요.\n"
                + "firstAlarmEvidence.sensorZScores 는 각 센서의 최근 5 shot 평균이 기준 평균에서 몇 σ 떨어졌는지입니다.\n\n"
                + objectMapper.writeValueAsString(incident);
    }

    private void setStatus(List<String> anomalyIds, String status) {
        if (anomalyIds.isEmpty()) {
            return;
        }
        String placeholders = anomalyIds.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.addAll(anomalyIds);
        jdbc.update("UPDATE dbo.anomaly_event SET status = ? WHERE anomaly_id IN (" + placeholders + ")",
                args.toArray());
    }

    private Integer insertAnalysis(String equipmentId, String seedId, List<String> anomalyIds, String decision,
            String causeCode, Integer confidence, String resultJson, List<String> trace, long ms, Integer ncrId,
            String error) {
        return jdbc.queryForObject("""
                INSERT INTO dbo.agent_analysis (equipment_id, seed_anomaly_id, anomaly_ids, decision, cause_code,
                    confidence, result_json, tool_trace, model, latency_ms, ncr_id, error_message)
                OUTPUT INSERTED.analysis_id
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Integer.class,
                equipmentId, seedId, String.join(",", anomalyIds), decision, causeCode, confidence, resultJson,
                String.join("\n", trace), model, (int) ms, ncrId, error == null ? null : truncate(error, 1000));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static String topDefectType(Map<String, Object> evidence) {
        Object o = evidence.get("recentDefectTypes");
        if (!(o instanceof Map<?, ?> m) || m.isEmpty()) {
            return null;
        }
        return ((Map<String, Object>) m).entrySet().stream()
                .max((x, y) -> Integer.compare(((Number) x.getValue()).intValue(), ((Number) y.getValue()).intValue()))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static String relatedSensors(List<Map<String, Object>> anomalies) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> a : anomalies) {
            String k = (String) a.get("sensor_key");
            if (k != null && !k.equals("inspection")) {
                keys.add(k);
            }
        }
        return keys.isEmpty() ? null : String.join(",", keys);
    }

    private static String evidenceText(AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.evidence() != null) {
            r.evidence().forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        if (r.similarCases() != null && !r.similarCases().isEmpty()) {
            sb.append("참고 사례: ").append(String.join(", ", r.similarCases())).append('\n');
        }
        if (r.reasoningSummary() != null) {
            sb.append("판단 요약: ").append(r.reasoningSummary());
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** 응답의 모든 생성 결과 텍스트를 이어 붙인다 (사고 요약과 최종 답이 나뉘어 올 수 있음). */
    private static String allText(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Generation g : response.getResults()) {
            if (g != null && g.getOutput() != null && g.getOutput().getText() != null) {
                sb.append(g.getOutput().getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 텍스트에서 AnalysisResult JSON 을 찾아 읽는다.
     * 앞에 사고 요약·설명이 붙거나 코드 블록(```json)으로 감싸져 있어도, '{' 위치를 차례로 시도해
     * decision 이 들어 있는 JSON 객체를 찾는다. 찾지 못하면 null.
     */
    private AnalysisResult parseResult(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.replace("```json", "").replace("```", "");
        int end = t.lastIndexOf('}');
        if (end < 0) {
            return null;
        }
        for (int start = t.indexOf('{'); start >= 0 && start < end; start = t.indexOf('{', start + 1)) {
            try {
                AnalysisResult r = objectMapper.readValue(t.substring(start, end + 1), AnalysisResult.class);
                if (r != null && r.decision() != null) {
                    return r;
                }
            } catch (RuntimeException ignore) {
                // 다음 '{' 위치에서 다시 시도
            }
        }
        return null;
    }

    private static String preview(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    /** 예외를 감싼 원인까지 이어 붙인다 ("Failed to generate content" 같은 포장 메시지 뒤의 진짜 이유를 보기 위함). */
    private static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        Throwable t = e.getCause();
        int depth = 0;
        while (t != null && t != t.getCause() && depth++ < 5) {
            sb.append(" ← ").append(t);
            t = t.getCause();
        }
        return sb.toString();
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
