package com.qualiguard.qms.agent;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qualiguard.qms.agent.hold.JudgementHoldPolicy;
import com.qualiguard.qms.knowledge.NcrEmbeddingService;

import tools.jackson.databind.ObjectMapper;

/**
 * 경보 → 사건 묶기 → 에이전트 분석 → 결과 저장. 각 단계를 agent/pipeline 의 역할별 에이전트(분류·분석·검증·작성)가
 * 오케스트레이터의 Kafka 이벤트를 받아 부른다 (ADR-016). 이 클래스는 단계별 기능만 제공하고 순서는 정하지 않는다.
 *
 * 1) 사건 묶기: 시작 경보(seed)와 같은 설비에서 일정 shot·시간 안에 난 미처리 경보를 하나의 사건으로 묶는다.
 *    경보마다 LLM 을 부르지 않기 위해서다 (비용 절감, 같은 원인의 중복 보고서 방지).
 *    시간 순 재생(replay)에서는 묶는 범위와 데이터 조회 기준 시점을 모두 "시작 경보 시각"으로 제한한다.
 *    지난 데이터를 다시 돌려도 그 순간 실제로 알 수 있었던 경보·데이터만 쓰게 하기 위함이다 (장애 분석 04).
 *    같은 설비의 이전 판단(같은 run_tag, merge-minutes 이내 최근 3건)을 previousJudgements 로 함께 넘긴다.
 * 2) 분석: 에이전트가 도구(저장 프로시저)로 근거를 모아 AnalysisResult 를 돌려준다.
 * 3) 저장:
 *    - NCR_DRAFT    → 부적합 초안 생성. 같은 설비·같은 원인의 최근 초안이 있으면 새로 만들지 않고 그 초안에 경보를 연결
 *    - SENSOR_CHECK → 경보 종결(DISMISSED), 측정 계통 점검 권고는 분석 기록에 남김
 *    - OBSERVE      → 경보 종결(DISMISSED)
 *    모든 분석은 판단 근거·도구 호출 순서·소요 시간과 함께 agent_analysis 에 기록한다.
 * 4) 판단 보류: 재확인 상한까지 관찰이거나, 검증 지적이 남은 채 경보를 닫는 경우 사람 검토 대기로 표시 (JudgementHoldPolicy)
 */
@Service
public class IncidentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalyzer.class);
    private static final Set<String> DECISIONS = Set.of("NCR_DRAFT", "SENSOR_CHECK", "OBSERVE");

    /**
     * 분석 결과.
     *
     * @param triggerType      ALARM(경보로 시작) / RECHECK(관찰 판단 뒤 새 경보 없이 재확인)
     * @param asOfShot         데이터 조회 기준 shot
     * @param parentAnalysisId 재확인의 바탕이 된 관찰 분석 (경보 분석은 null)
     */
    public record Outcome(Integer analysisId, String equipmentId, String triggerType, Long asOfShot,
            Integer parentAnalysisId, String decision, String causeCode, Integer confidence, String ncrNo,
            boolean mergedIntoExistingNcr, List<String> anomalyIds, long latencyMs, List<String> toolTrace,
            AnalysisResult result, String error, int attempts, VerifierAgent.Verification verification,
            String incidentId, Map<String, Object> triage, Map<String, Object> report, List<Map<String, Object>> timeline,
            Tokens tokens) {

        /** 파이프라인(오케스트레이터)이 사건 번호·분류·작성 결과·단계 기록을 붙인다. */
        public Outcome withPipeline(String incidentId, Map<String, Object> triage, Map<String, Object> report,
                List<Map<String, Object>> timeline) {
            return new Outcome(analysisId, equipmentId, triggerType, asOfShot, parentAnalysisId, decision, causeCode,
                    confidence, ncrNo, mergedIntoExistingNcr, anomalyIds, latencyMs, toolTrace, result, error, attempts,
                    verification, incidentId, triage, report, timeline, tokens);
        }
    }

    /** 응답이 알려 준 토큰 수 (입력·출력·생각). 모르면 null. 재분석·형식 수정까지 한 사건의 LLM 호출을 모두 더한다. */
    public record Tokens(Integer prompt, Integer output, Integer thought) {

        static final String TRACE_PREFIX = "  ↳ 토큰: ";
        private static final Pattern TRACE = Pattern.compile("입력 (\\d+|-), 출력 (\\d+|-), 생각 (\\d+|-)");

        static Tokens of(ChatResponse r) {
            if (r == null || r.getMetadata() == null || r.getMetadata().getUsage() == null) {
                return new Tokens(null, null, null);
            }
            var u = r.getMetadata().getUsage();
            Integer thought = u instanceof GoogleGenAiUsage g ? g.getThoughtsTokenCount() : null;
            return new Tokens(u.getPromptTokens(), u.getCompletionTokens(), thought);
        }

        /** 도구 기록에 한 줄로 남긴다 ("  ↳" 로 시작하는 줄은 도구 호출로 세지 않는다). */
        String traceLine() {
            return TRACE_PREFIX + "입력 " + str(prompt) + ", 출력 " + str(output) + ", 생각 " + str(thought);
        }

        /** 도구 기록의 토큰 줄을 모두 더한다. 줄이 없으면 전부 null. */
        static Tokens fromTrace(List<String> trace) {
            Integer p = null, o = null, t = null;
            if (trace != null) {
                for (String line : List.copyOf(trace)) {
                    if (line == null || !line.startsWith(TRACE_PREFIX)) {
                        continue;
                    }
                    Matcher m = TRACE.matcher(line);
                    if (m.find()) {
                        p = add(p, m.group(1));
                        o = add(o, m.group(2));
                        t = add(t, m.group(3));
                    }
                }
            }
            return new Tokens(p, o, t);
        }

        private static Integer add(Integer sum, String v) {
            // 삼항 연산자로 쓰면 한쪽이 int 라 sum 이 null 일 때 자동 언박싱 NPE 가 난다 → if 로 나눈다
            if ("-".equals(v)) {
                return sum;
            }
            return (sum == null ? 0 : sum) + Integer.parseInt(v);
        }

        private static String str(Integer v) {
            return v == null ? "-" : v.toString();
        }
    }

    /** 분석 1건의 입력(사건). 경보 분석과 재확인이 같은 파이프라인(분류 → 분석 → 검증 → 작성)을 탄다. */
    public record Job(String trigger, String equipmentId, String seedAnomalyId, String seedTs,
            List<Map<String, Object>> alarms, List<String> anomalyIds, boolean ownsAlarmStatus,
            Map<String, Object> seedEvidence, String asOf, Long asOfShot, String previousBefore,
            Integer parentId, String tag, Map<String, Object> recheckInfo) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final AgentProperties props;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final String model;
    private final NcrEmbeddingService embeddings;
    private volatile ChatClient chatClient;

    public IncidentAnalyzer(JdbcTemplate jdbc, ObjectMapper objectMapper, TransactionTemplate tx,
            AgentProperties props, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            @Value("${spring.ai.google.genai.chat.model:gemini-3.8-flash}") String model,
            NcrEmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.props = props;
        this.chatClientBuilder = chatClientBuilder;
        this.model = model;
        this.embeddings = embeddings;
    }

    /** 가장 오래된 미처리 경보 (같은 경보로 2번 실패했으면 건너뛴다). */
    public Optional<String> nextSeed() {
        List<String> ids = jdbc.queryForList("""
                SELECT TOP 1 a.anomaly_id
                FROM dbo.anomaly_event AS a
                WHERE a.status = 'NEW'
                  AND (SELECT COUNT(*) FROM dbo.agent_analysis AS g
                        WHERE g.seed_anomaly_id = a.anomaly_id AND g.decision = 'FAILED') < 2
                ORDER BY a.detected_at, a.anomaly_id
                """, String.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 지정한 경보를 시작점으로 사건을 분석한다 (평가·재분석용: 이미 처리된 경보도 시작점이 될 수 있다).
     *
     * @param replay true 면 시간 순 재생: 시작 경보 시각까지 발생한 경보·데이터만 쓴다.
     *               false(운영)면 시작 경보 이후 묶음 범위 안의 미처리 경보까지 함께 묶는다.
     * @param runTag 평가 실행 구분 표시 (운영은 null). 이전 판단은 같은 runTag 의 분석에서만 찾는다.
     */
    public synchronized Job prepareAlarm(String seedAnomalyId, boolean replay, String runTag) {
        String tag = runTag == null || runTag.isBlank() ? null : truncate(runTag.strip(), 60);
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

        // ── 사건 묶기 ────────────────────────────────────────────────
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
                """, equipmentId, seedAnomalyId, seedShot,
                replay ? seedShot : seedShot + props.incidentShotWindow(),
                seedTs, replay ? 0 : props.incidentMinutes(), seedTs);
        List<String> anomalyIds = anomalies.stream().map(a -> (String) a.get("anomaly_id")).toList();
        // 조회 기준 시점: 운영은 사건의 마지막 경보 시각, 재생은 시작 경보 시각 (그 이후 데이터는 보지 않는다)
        Map<String, Object> last = anomalies.isEmpty() ? null : anomalies.get(anomalies.size() - 1);
        String asOf = replay || last == null ? seedTs : (String) last.get("detected_at");
        long asOfShot = replay || last == null ? seedShot : ((Number) last.get("shot_no")).longValue();
        setStatus(anomalyIds, "ANALYZING");

        return new Job("ALARM", equipmentId, seedAnomalyId, seedTs, anomalies, anomalyIds, true,
                readMap((String) seed.get("evidence_json")), asOf, asOfShot, seedTs, null, tag, null);
    }

    /**
     * 관찰(OBSERVE) 판단 뒤 새 경보 없이 일정 shot 이 지났을 때 다시 분석한다.
     * 모든 관찰 판단에 똑같이 적용하는 규칙이며, 그 시점(asOfShot)까지의 데이터만 쓴다.
     *
     * @param parentAnalysisId 관찰 판단을 낸 분석
     * @param asOfShot         재확인 기준 shot (null 이면 관찰 기준 shot + recheck-shots)
     * @throws IllegalArgumentException 분석이 없거나 관찰 판단이 아닐 때
     * @throws IllegalStateException    재확인 횟수 상한에 닿았거나 그 shot 의 데이터가 아직 없을 때
     */
    public synchronized Job prepareRecheck(int parentAnalysisId, Long asOfShot) {
        return prepareFollowUp(parentAnalysisId, asOfShot, null);
    }

    /**
     * 조치 효과가 없을 때 다시 분석한다: 앞 분석이 고른 원인(excludedCause)으로 조치했지만 이상이 남았으니,
     * 지금 시점의 데이터로 그 원인을 뺀 다른 가설을 찾게 한다. 재확인과 같은 횟수 상한(recheck-max)을 쓴다.
     */
    public synchronized Job prepareRetry(int parentAnalysisId, String excludedCause) {
        return prepareFollowUp(parentAnalysisId, null, excludedCause);
    }

    private Job prepareFollowUp(int parentAnalysisId, Long asOfShot, String excludedCause) {
        Map<String, Object> parent;
        try {
            parent = jdbc.queryForMap("""
                    SELECT g.analysis_id, g.equipment_id, g.seed_anomaly_id, g.anomaly_ids, g.decision, g.run_tag,
                           COALESCE(g.as_of_shot, s.shot_no) AS base_shot,
                           CONVERT(VARCHAR(33), COALESCE(g.as_of, s.detected_at), 127) AS base_ts,
                           CONVERT(VARCHAR(33), s.detected_at, 127) AS seed_ts
                    FROM dbo.agent_analysis AS g
                    JOIN dbo.anomaly_event AS s ON s.anomaly_id = g.seed_anomaly_id
                    WHERE g.analysis_id = ?
                    """, parentAnalysisId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("분석 기록을 찾을 수 없습니다: " + parentAnalysisId);
        }
        if (excludedCause == null && !"OBSERVE".equals(parent.get("decision"))) {
            throw new IllegalArgumentException("관찰(OBSERVE) 판단만 재확인할 수 있습니다: " + parent.get("decision"));
        }
        int depth = recheckDepth(parentAnalysisId);
        if (depth >= props.recheckMax()) {
            throw new IllegalStateException("새 경보 없는 재확인이 이미 " + depth + "회입니다 (상한 " + props.recheckMax() + ")");
        }
        String equipmentId = (String) parent.get("equipment_id");
        long baseShot = ((Number) parent.get("base_shot")).longValue();
        long target = asOfShot != null ? asOfShot
                : excludedCause != null ? latestShot(equipmentId) : baseShot + props.recheckShots();
        if (target <= baseShot) {
            throw new IllegalArgumentException("재확인 shot 은 관찰 기준 shot(" + baseShot + ")보다 뒤여야 합니다");
        }
        List<String> shotTs = jdbc.queryForList("""
                SELECT TOP 1 CONVERT(VARCHAR(33), shot_ts, 127) FROM dbo.process_shot
                WHERE equipment_id = ? AND shot_no = ? AND shot_ts > CAST(? AS DATETIMEOFFSET(3))
                ORDER BY shot_ts
                """, String.class, equipmentId, target, parent.get("base_ts"));
        if (shotTs.isEmpty()) {
            throw new IllegalStateException(equipmentId + " shot " + target + " 데이터가 아직 없습니다");
        }
        String asOf = shotTs.get(0);

        String ids = (String) parent.get("anomaly_ids");
        List<String> anomalyIds = ids == null || ids.isBlank() ? List.of() : List.of(ids.split(","));
        // 관찰 판단을 낸 경보들 (재확인에서 새로 생긴 경보는 없음)
        List<Map<String, Object>> observedAlarms = anomalyIds.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT anomaly_id, rule_code, rule_name, sensor_key, sensor_name, shot_no, message "
                        + "FROM dbo.anomaly_event WHERE anomaly_id IN ("
                        + anomalyIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ") "
                        + "ORDER BY detected_at, anomaly_id",
                anomalyIds.toArray());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("observedAtShot", baseShot);
        info.put("currentShot", target);
        info.put("shotsSinceObservation", target - baseShot);
        info.put("recheckCount", depth + 1);
        if (excludedCause != null) {
            info.put("excludedCause", excludedCause);
        }
        return new Job("RECHECK", equipmentId, (String) parent.get("seed_anomaly_id"),
                (String) parent.get("seed_ts"), observedAlarms, anomalyIds, false, null, asOf, target, asOf,
                parentAnalysisId, (String) parent.get("run_tag"), info);
    }

    private long latestShot(String equipmentId) {
        Long max = jdbc.queryForObject("SELECT MAX(shot_no) FROM dbo.process_shot WHERE equipment_id = ?", Long.class,
                equipmentId);
        return max == null ? 0 : max;
    }

    /**
     * 운영용: 새 경보 없이 recheck-shots 가 지난 관찰 판단을 하나 골라 재확인한다.
     * 그 설비에 더 최근 분석이 있으면(새 경보가 났으면) 그 분석이 이어받으므로 재확인하지 않는다.
     */
    public Optional<Integer> nextRecheckDue() {
        if (props.recheckShots() <= 0 || props.recheckMax() <= 0) {
            return Optional.empty();
        }
        List<Integer> due = jdbc.queryForList("""
                SELECT g.analysis_id
                FROM dbo.agent_analysis AS g
                JOIN dbo.anomaly_event AS s ON s.anomaly_id = g.seed_anomaly_id
                WHERE g.decision = 'OBSERVE' AND g.run_tag IS NULL
                  AND NOT EXISTS (SELECT 1 FROM dbo.agent_analysis AS h
                                  WHERE h.equipment_id = g.equipment_id AND h.run_tag IS NULL
                                    AND h.analysis_id > g.analysis_id AND h.decision <> 'FAILED')
                  AND (SELECT COUNT(*) FROM dbo.agent_analysis AS f
                        WHERE f.parent_analysis_id = g.analysis_id AND f.decision = 'FAILED') < 2
                  AND EXISTS (SELECT 1 FROM dbo.process_shot AS p
                              WHERE p.equipment_id = g.equipment_id
                                AND p.shot_no = COALESCE(g.as_of_shot, s.shot_no) + ?
                                AND p.shot_ts > COALESCE(g.as_of, s.detected_at))
                ORDER BY g.analysis_id
                """, Integer.class, props.recheckShots());
        for (Integer id : due) {
            if (recheckDepth(id) < props.recheckMax()) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** 이 분석까지 새 경보 없이 이어진 재확인 횟수. */
    private int recheckDepth(int analysisId) {
        int depth = 0;
        Integer cur = analysisId;
        while (cur != null && depth < 100) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trigger_type, parent_analysis_id FROM dbo.agent_analysis WHERE analysis_id = ?", cur);
            if (rows.isEmpty() || !"RECHECK".equals(rows.get(0).get("trigger_type"))) {
                break;
            }
            depth++;
            cur = (Integer) rows.get(0).get("parent_analysis_id");
        }
        return depth;
    }

    /** 분석 에이전트에게 줄 입력: 사건 + 같은 설비의 이전 판단 + 사람 검토자의 판단 보류 처리 메모. */
    public String userPrompt(Job job) {
        return buildUserPrompt(job, previousJudgements(job.equipmentId(), job.previousBefore(), job.tag()),
                reviewerNotes(job.equipmentId(), job.previousBefore(), job.tag()));
    }

    /** 검증 에이전트가 되돌렸을 때의 재분석 입력: 원래 입력 + 앞선 결과 + 지적. */
    public String reworkPrompt(String userPrompt, AnalysisResult previous, VerifierAgent.Verification v) {
        return userPrompt + "\n\n[앞선 분석 결과]\n" + objectMapper.writeValueAsString(previous)
                + "\n\n[검증 에이전트 지적]\n" + VerifierAgent.feedback(v);
    }

    /** 검증 에이전트 입력. */
    public VerifierAgent.Input verifyInput(Job job, AnalysisResult result, List<String> attemptTrace) {
        return verifyInput(job, result, attemptTrace, null);
    }

    /** 검증 에이전트 입력 (도구가 돌려준 수치 포함 — 근거 수치 대조용). */
    public VerifierAgent.Input verifyInput(Job job, AnalysisResult result, List<String> attemptTrace,
            ToolObservations observed) {
        return new VerifierAgent.Input(job.equipmentId(), job.asOf(), alarmRules(job), result, attemptTrace,
                observed);
    }

    /** 사건이 끝내 실패했을 때: 경보를 다시 분석할 수 있게 되돌리고 실패를 기록한다. */
    public Outcome fail(String incidentId, Job job, List<String> trace, long ms, int attempts, String error,
            String pipelineJson) {
        if (job.ownsAlarmStatus()) {
            setStatus(job.anomalyIds(), "NEW");
        }
        Integer id = insertAnalysis(job, "FAILED", null, null, null, trace, ms, null, error, attempts, null,
                incidentId, pipelineJson);
        log.error("[에이전트] 분석 실패 — {} {} 경보 {}건: {}", job.equipmentId(), job.trigger(), job.anomalyIds().size(), error);
        return new Outcome(id, job.equipmentId(), job.trigger(), job.asOfShot(), job.parentId(), "FAILED", null, null,
                null, false, job.anomalyIds(), ms, trace, null, error, attempts, null, null, null, null, null,
                Tokens.fromTrace(trace));
    }

    /**
     * 사건 마무리: 분석·검증·작성 결과를 하나의 트랜잭션으로 저장한다.
     * NCR_DRAFT 면 부적합 초안(사람 승인 대기)을 만들거나 기존 초안에 연결하고, 아니면 경보를 종결한다.
     *
     * @param written 보고서 작성 에이전트가 쓴 초안 문장 (null 이면 분석 결과 문장을 그대로 쓴다)
     */
    public Outcome complete(String incidentId, Job job, AnalysisResult result, List<String> trace, long ms,
            int attemptCount, VerifierAgent.Verification verified, AnalysisResult written, String pipelineJson) {
        String equipmentId = job.equipmentId();
        List<String> anomalyIds = job.anomalyIds();
        String tag = job.tag();
        AnalysisResult text = written == null ? result : written;
        // ── 결과 저장 (하나의 트랜잭션) ──────────────────────────────
        String decision = result.decision().toUpperCase(Locale.ROOT);
        String causeCode = result.causeCode() == null ? "UNKNOWN" : result.causeCode().toUpperCase(Locale.ROOT);
        int confidence = result.confidence() == null ? 0 : Math.max(0, Math.min(100, result.confidence()));
        String resultJson = objectMapper.writeValueAsString(result);

        Outcome outcome = tx.execute(status -> {
            String ncrNo = null;
            boolean merged = false;
            Integer ncrId = null;
            if (decision.equals("NCR_DRAFT")) {
                // 운영: 최근 merge-minutes 안의 같은 설비·같은 원인 초안에 연결
                // 재생: 같은 run_tag 실행에서 만든 초안에만 연결 (다른 평가 실행의 초안과 섞이지 않게)
                List<Map<String, Object>> open = tag == null
                        ? jdbc.queryForList("""
                                SELECT TOP 1 ncr_id, ncr_no FROM dbo.ncr
                                WHERE equipment_id = ? AND status = 'DRAFT' AND created_by = 'AGENT' AND cause_code = ?
                                  AND created_at >= DATEADD(MINUTE, -?, SYSDATETIMEOFFSET())
                                ORDER BY created_at DESC
                                """, equipmentId, causeCode, props.mergeMinutes())
                        : jdbc.queryForList("""
                                SELECT TOP 1 n.ncr_id, n.ncr_no FROM dbo.ncr AS n
                                WHERE n.equipment_id = ? AND n.status = 'DRAFT' AND n.created_by = 'AGENT'
                                  AND n.cause_code = ?
                                  AND EXISTS (SELECT 1 FROM dbo.agent_analysis AS g
                                              WHERE g.ncr_id = n.ncr_id AND g.run_tag = ?)
                                ORDER BY n.created_at DESC
                                """, equipmentId, causeCode, tag);
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
                            ncrNo, equipmentId, job.seedAnomalyId(), defectType(job),
                            relatedSensors(job.alarms()), truncate(text.title(), 200),
                            truncate(text.phenomenon(), 1000), truncate(text.suspectedCause(), 1000),
                            evidenceText(result) + verifyNote(verified), truncate(text.recommendedAction(), 1000),
                            job.seedTs(), causeCode, confidence);
                }
                for (String id : anomalyIds) {
                    jdbc.update("""
                            INSERT INTO dbo.ncr_anomaly (ncr_id, anomaly_id)
                            SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM dbo.ncr_anomaly WHERE ncr_id = ? AND anomaly_id = ?)
                            """, ncrId, id, ncrId, id);
                }
                setStatus(anomalyIds, "NCR_DRAFTED");
            } else if (job.ownsAlarmStatus()) {
                setStatus(anomalyIds, "DISMISSED");
            }
            Integer analysisId = insertAnalysis(job, decision, causeCode, confidence, resultJson, trace, ms, ncrId,
                    null, attemptCount, verified, incidentId, pipelineJson);
            markHoldIfNeeded(analysisId, job, decision, verified);
            return new Outcome(analysisId, equipmentId, job.trigger(), job.asOfShot(), job.parentId(), decision,
                    causeCode, confidence, ncrNo, merged, anomalyIds, ms, trace, result, null, attemptCount, verified,
                    null, null, null, null, Tokens.fromTrace(trace));
        });

        log.info("[에이전트] {} {} (shot {}) 경보 {}건 → {} / {} (확신도 {}) {}{} — 분석 {}회, 검증 {}, 도구 {}회, {}ms",
                equipmentId, job.trigger(), job.asOfShot(), anomalyIds.size(), decision, causeCode, confidence,
                outcome.ncrNo() == null ? "" : outcome.ncrNo(),
                outcome.mergedIntoExistingNcr() ? "(기존 초안에 연결)" : "", attemptCount,
                verified == null ? "-" : verified.status(), trace.size(), ms);
        return outcome;
    }

    /** 분석 에이전트 1회: 도구로 조사 → JSON 결과. 형식이 깨지면 형식만 1회 고쳐 달라고 한다. */
    public AnalysisResult analyzeOnce(String userPrompt, String asOf, long t0, List<String> trace) {
        return analyzeOnce(userPrompt, asOf, t0, trace, null);
    }

    /** observed: 도구가 돌려준 수치를 모을 바구니 (검증 에이전트의 수치 대조용, null 이면 모으지 않음). */
    public AnalysisResult analyzeOnce(String userPrompt, String asOf, long t0, List<String> trace,
            ToolObservations observed) {
        // 응답 전체 텍스트를 받아 직접 해석한다.
        // (Gemini 3 계열은 사고 요약이 마크다운으로 함께 올 수 있어 entity() 자동 변환이 실패할 수 있음)
        ChatResponse response = chatClient().prompt()
                .system(AgentPrompts.SYSTEM)
                .user(userPrompt)
                .tools(new QualityTools(jdbc, trace, asOf, t0, embeddings::queryVector, observed))
                .call()
                .chatResponse();
        trace.add(Tokens.of(response).traceLine());
        String text = allText(response);
        AnalysisResult parsed = parseResult(text);
        if (parsed == null) {
            // 형식만 다시 요청 (도구 없이 1회 — 조사를 반복하지 않으므로 비용이 적다)
            trace.add("repairJsonFormat()");
            ChatResponse repairResponse = chatClient().prompt()
                    .system(AgentPrompts.REPAIR)
                    .user(text)
                    .call()
                    .chatResponse();
            trace.add(Tokens.of(repairResponse).traceLine());
            String repaired = allText(repairResponse);
            parsed = parseResult(repaired);
            if (parsed == null) {
                throw new IllegalStateException("분석 결과를 JSON 으로 읽지 못함. 응답 앞부분: " + preview(text));
            }
        }
        if (parsed.decision() == null || !DECISIONS.contains(parsed.decision().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("에이전트 응답 형식 오류: " + parsed);
        }
        return parsed;
    }

    private static List<String> alarmRules(Job job) {
        return job.alarms().stream().map(a -> (String) a.get("rule_code")).filter(r -> r != null).toList();
    }

    /** 초안의 불량 유형: 경보 근거가 있으면 그것을, 재확인이면 기준 시점까지 최근 50 shot 의 최다 불량 유형. */
    private String defectType(Job job) {
        if (job.seedEvidence() != null) {
            return topDefectType(job.seedEvidence());
        }
        List<String> rows = jdbc.queryForList("""
                SELECT TOP 1 defect_type FROM (
                    SELECT TOP 50 defect_type FROM dbo.process_shot
                    WHERE equipment_id = ? AND shot_ts <= CAST(? AS DATETIMEOFFSET(3))
                    ORDER BY shot_ts DESC) AS r
                WHERE defect_type IS NOT NULL
                GROUP BY defect_type ORDER BY COUNT(*) DESC
                """, String.class, job.equipmentId(), job.asOf());
        return rows.isEmpty() ? null : rows.get(0);
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

    /**
     * 같은 설비에서 기준 시각(before)보다 앞선 시점을 기준으로 한 분석 중,
     * merge-minutes 이내이고 run_tag 가 같은(운영은 NULL) 최근 3건의 판단을 읽는다.
     * 예전 기록처럼 as_of 가 없으면 시작 경보 시각을 기준 시점으로 본다.
     */
    private List<Map<String, Object>> previousJudgements(String equipmentId, String before, String tag) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT TOP 3 COALESCE(g.as_of_shot, s.shot_no) AS shot_no, g.trigger_type, g.decision, g.cause_code,
                       g.confidence, g.result_json
                FROM dbo.agent_analysis AS g
                JOIN dbo.anomaly_event AS s ON s.anomaly_id = g.seed_anomaly_id
                WHERE g.equipment_id = ?
                  AND g.decision <> 'FAILED'
                  AND COALESCE(g.as_of, s.detected_at) < CAST(? AS DATETIMEOFFSET(3))
                  AND COALESCE(g.as_of, s.detected_at) >= DATEADD(MINUTE, -?, CAST(? AS DATETIMEOFFSET(3)))
                  AND ((? IS NULL AND g.run_tag IS NULL) OR g.run_tag = ?)
                ORDER BY COALESCE(g.as_of, s.detected_at) DESC, g.analysis_id DESC
                """, equipmentId, before, props.mergeMinutes(), before, tag, tag);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> result = readMap((String) r.get("result_json"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shotNo", r.get("shot_no"));
            m.put("trigger", r.get("trigger_type") == null ? "ALARM" : r.get("trigger_type"));
            m.put("decision", r.get("decision"));
            m.put("causeCode", r.get("cause_code"));
            m.put("confidence", r.get("confidence") == null ? null : ((Number) r.get("confidence")).intValue());
            m.put("reasoningSummary", result.get("reasoningSummary"));
            m.put("watchItems", result.get("watchItems"));
            out.add(m);
        }
        return out;
    }

    /**
     * 같은 설비에서 사람 검토자가 판단 보류를 처리하며 남긴 메모 (ADR-020 후속). 최근 3건.
     * 기준 시각(before) 전에 처리된 것만 쓴다(미래 정보 차단). 평가 실행(tag 있음)에는 넣지 않는다:
     * 사람이 남긴 메모가 채점 결과를 바꾸지 않도록.
     */
    private List<Map<String, Object>> reviewerNotes(String equipmentId, String before, String tag) {
        if (tag != null || before == null) {
            return List.of();
        }
        return toReviewerNotes(jdbc.queryForList("""
                SELECT TOP 3 g.as_of_shot, g.decision, g.cause_code, g.hold_reason, g.hold_note,
                       CONVERT(VARCHAR(33), g.hold_resolved_at, 127) AS resolved_at
                FROM dbo.agent_analysis AS g
                WHERE g.equipment_id = ?
                  AND g.hold_status = 'RESOLVED'
                  AND g.run_tag IS NULL
                  AND g.hold_note IS NOT NULL
                  AND g.hold_resolved_at < CAST(? AS DATETIMEOFFSET(3))
                ORDER BY g.hold_resolved_at DESC
                """, equipmentId, before));
    }

    /** DB 행 → 프롬프트에 넣을 검토자 메모. 처리자 이름은 넣지 않는다. */
    static List<Map<String, Object>> toReviewerNotes(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shotNo", r.get("as_of_shot"));
            m.put("agentDecision", r.get("decision"));
            m.put("agentCause", r.get("cause_code"));
            m.put("holdReason", r.get("hold_reason"));
            m.put("reviewerNote", r.get("hold_note"));
            m.put("resolvedAt", r.get("resolved_at"));
            out.add(m);
        }
        return out;
    }

    private String buildUserPrompt(Job job, List<Map<String, Object>> previous, List<Map<String, Object>> notes) {
        List<Map<String, Object>> alarmList = new ArrayList<>();
        for (Map<String, Object> a : job.alarms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shotNo", a.get("shot_no"));
            m.put("rule", a.get("rule_name"));
            m.put("sensorKey", a.get("sensor_key"));
            m.put("sensorName", a.get("sensor_name"));
            m.put("message", a.get("message"));
            alarmList.add(m);
        }
        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("equipmentId", job.equipmentId());
        StringBuilder sb = new StringBuilder();
        if (job.recheckInfo() == null) {
            incident.put("alarms", alarmList);
            incident.put("firstAlarmEvidence", job.seedEvidence());
            sb.append("다음 경보 사건을 조사하고 분석 결과를 작성해 주세요.\n")
                    .append("firstAlarmEvidence.sensorZScores 는 각 센서의 최근 5 shot 평균이 기준 평균에서 몇 σ 떨어졌는지입니다.\n");
        } else if (job.recheckInfo().containsKey("excludedCause")) {
            incident.put("retry", job.recheckInfo());
            incident.put("observedAlarms", alarmList);
            sb.append("조치 효과 확인 결과 재분석 요청입니다. 앞 분석이 고른 원인(")
                    .append(job.recheckInfo().get("excludedCause"))
                    .append(")에 맞춰 조치했지만 이상이 해소되지 않았습니다. 그 원인은 제외하고, 지금(shot ")
                    .append(job.asOfShot()).append(") 시점의 데이터로 다른 원인 가설을 조사해 분석 결과를 작성해 주세요.\n")
                    .append("다른 원인을 뒷받침할 근거가 부족하면 causeCode 를 UNKNOWN 으로 두세요.\n");
        } else {
            incident.put("recheck", job.recheckInfo());
            incident.put("observedAlarms", alarmList);
            sb.append("재확인 요청입니다. 앞선 관찰(OBSERVE) 판단 이후 새 경보 없이 ")
                    .append(job.recheckInfo().get("shotsSinceObservation"))
                    .append(" shot 이 지났습니다. 지금(shot ").append(job.asOfShot())
                    .append(") 시점의 데이터로 다시 조사하고 분석 결과를 작성해 주세요.\n")
                    .append("observedAlarms 는 관찰 판단을 낸 경보입니다.\n");
        }
        if (!previous.isEmpty()) {
            incident.put("previousJudgements", previous);
            sb.append("previousJudgements 는 같은 설비에서 앞서 내린 판단입니다(최근 순). ")
                    .append("shotNo 는 그 판단의 기준 shot, trigger 는 경보(ALARM)·재확인(RECHECK) 구분입니다.\n");
        }
        if (!notes.isEmpty()) {
            incident.put("reviewerNotes", notes);
            sb.append("reviewerNotes 는 같은 설비에서 에이전트가 결론을 못 내 판단 보류된 건을 사람 검토자가 확인하고 남긴 메모입니다(최근 순).\n");
        }
        return sb.append('\n').append(objectMapper.writeValueAsString(incident)).toString();
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

    /** 판단 보류 규칙(JudgementHoldPolicy)에 걸리면 사람 검토 대기로 표시한다 (로드맵 F2). */
    private void markHoldIfNeeded(Integer analysisId, Job job, String decision, VerifierAgent.Verification verified) {
        Object count = job.recheckInfo() == null ? null : job.recheckInfo().get("recheckCount");
        int recheckCount = count instanceof Number n ? n.intValue() : 0;
        JudgementHoldPolicy.evaluate(job.trigger(), decision, recheckCount, props.recheckMax(),
                verified == null ? null : verified.status()).ifPresent(hold -> {
                    jdbc.update("""
                            UPDATE dbo.agent_analysis
                            SET hold_status = 'PENDING', hold_reason = ?, hold_detail = ?
                            WHERE analysis_id = ?
                            """, hold.reason(), hold.detail(), analysisId);
                    log.warn("[에이전트] {} 분석 {} 판단 보류 → 사람 검토 ({}): {}", job.equipmentId(), analysisId,
                            hold.reason(), hold.detail());
                });
    }

    private Integer insertAnalysis(Job job, String decision, String causeCode, Integer confidence,
            String resultJson, List<String> trace, long ms, Integer ncrId, String error, int attempts,
            VerifierAgent.Verification verification, String incidentId, String pipelineJson) {
        Tokens tokens = Tokens.fromTrace(trace);
        return jdbc.queryForObject("""
                INSERT INTO dbo.agent_analysis (equipment_id, seed_anomaly_id, anomaly_ids, decision, cause_code,
                    confidence, result_json, tool_trace, model, latency_ms, ncr_id, error_message, run_tag,
                    trigger_type, as_of, as_of_shot, parent_analysis_id, attempts, verify_status, verify_json,
                    incident_id, pipeline_json, prompt_tokens, output_tokens, thought_tokens)
                OUTPUT INSERTED.analysis_id
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS DATETIMEOFFSET(3)), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Integer.class,
                job.equipmentId(), job.seedAnomalyId(), String.join(",", job.anomalyIds()), decision, causeCode,
                confidence, resultJson, String.join("\n", trace), model, (int) ms, ncrId,
                error == null ? null : truncate(error, 1000), job.tag(), job.trigger(), job.asOf(), job.asOfShot(),
                job.parentId(), attempts, verification == null ? null : verification.status(),
                verification == null ? null : objectMapper.writeValueAsString(verification), incidentId, pipelineJson,
                tokens.prompt(), tokens.output(), tokens.thought());
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

    /** 재분석 뒤에도 근거가 부족하면 검토자가 볼 수 있게 초안 근거 끝에 남긴다. */
    static String verifyNote(VerifierAgent.Verification v) {
        if (v == null || v.passed()) {
            return "";
        }
        return "\n검증 에이전트: 근거 부족 — " + String.join(" / ", v.fails());
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
