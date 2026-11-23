package com.qualiguard.qms.agent.hold;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * 판단 보류 요약 (ADR-020 후속): 보류된 분석과 그 앞 분석들(경보 → 재확인 …)을 검토자용 3줄 요약과
 * 현장 확인 항목으로 정리한다.
 *
 * 역할 나누기
 *  - 보류할지 말지는 규칙(JudgementHoldPolicy)이 정한다. 여기서는 바꾸지 않는다.
 *  - LLM 은 이미 저장된 분석 기록만 읽고 문장을 정리한다. 도구를 쓰지 않고, 판단·원인 코드를 새로 내지 않는다.
 *  - LLM 이 없거나 실패하면 기록에서 바로 뽑은 요약(RULE)으로 대신한다. 처리(RESOLVED)는 사람이 한다.
 */
@Service
public class HoldBriefService {

    private static final Logger log = LoggerFactory.getLogger(HoldBriefService.class);
    /** 앞 분석을 거슬러 올라갈 최대 개수 (경보 1 + 재확인 상한 3 + 여유) */
    static final int MAX_CHAIN = 6;
    static final int MAX_LINES = 3;

    static final String SYSTEM = """
            당신은 사출성형 품질 담당자를 돕는 요약 담당입니다.
            에이전트가 결론을 내지 못해 '판단 보류'가 된 사건의 분석 기록을 읽고, 사람 검토자가 1분 안에 상황을 파악하도록 정리합니다.

            [규칙]
            - 주어진 기록에 있는 사실과 수치만 씁니다. 기록에 없는 원인·수치를 지어내지 마세요.
            - 새 판단(부적합 여부, 원인 확정)을 내리지 마세요. 판단은 사람이 합니다.
            - summary: 최대 3줄. 1) 무엇이 경보였나 2) 재확인 동안 무엇이 바뀌었고 무엇이 그대로였나 3) 왜 결론이 안 났나
            - checks: 최대 3개. 사람이 현장에서 직접 확인하면 결론에 도움이 될 항목 (측정, 육안 점검, 정비 기록 확인 등)
            - 모든 문장은 한국어, 한 줄에 한 문장.
            - 출력은 JSON 하나만: {"summary": ["...", "..."], "checks": ["...", "..."]}
            """;

    /** 검토자에게 보여 줄 요약. source = LLM 또는 RULE(LLM 없이 기록에서 뽑음). */
    public record Brief(List<String> summary, List<String> checks, String source, String model) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final String model;
    private volatile ChatClient chatClient;

    public HoldBriefService(JdbcTemplate jdbc, ObjectMapper objectMapper, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            @Value("${qg.agent.model-label:${spring.ai.google.genai.chat.model:gemini-3.8-flash}}") String model) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.chatClientBuilder = chatClientBuilder;
        this.model = model;
    }

    /** 보류된 분석의 요약을 만들어 hold_brief 에 저장하고 돌려준다. 보류 건이 아니면 IllegalArgumentException. */
    public Map<String, Object> generate(int analysisId) {
        List<Map<String, Object>> chain = chain(analysisId);
        if (chain.isEmpty() || chain.get(chain.size() - 1).get("holdReason") == null) {
            throw new IllegalArgumentException("판단 보류된 분석이 아닙니다: " + analysisId);
        }
        Brief brief = null;
        String input = objectMapper.writeValueAsString(Map.of("analyses", chain));
        try {
            ChatClient c = chatClient();
            if (c != null) {
                String text = c.prompt().system(SYSTEM).user(input).call().content();
                brief = parse(text, model);
            }
        } catch (RuntimeException e) {
            log.warn("[판단 보류] 분석 {} 요약 LLM 실패 — 기록 요약으로 대신: {}", analysisId, e.toString());
        }
        if (brief == null) {
            brief = ruleBrief(chain);
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("summary", brief.summary());
        stored.put("checks", brief.checks());
        stored.put("source", brief.source());
        stored.put("model", brief.model());
        stored.put("createdAt", OffsetDateTime.now().toString());
        jdbc.update("UPDATE dbo.agent_analysis SET hold_brief = ? WHERE analysis_id = ?",
                objectMapper.writeValueAsString(stored), analysisId);
        return stored;
    }

    /** 보류된 분석에서 parent_analysis_id 를 따라 거슬러 올라가, 오래된 순으로 돌려준다 (마지막이 보류 건). */
    List<Map<String, Object>> chain(int analysisId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Integer id = analysisId;
        while (id != null && out.size() < MAX_CHAIN) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT analysis_id, parent_analysis_id, trigger_type, as_of_shot, decision, cause_code, confidence,
                           verify_status, hold_reason, hold_detail, result_json
                    FROM dbo.agent_analysis WHERE analysis_id = ?
                    """, id);
            if (rows.isEmpty()) {
                break;
            }
            Map<String, Object> r = rows.get(0);
            out.add(toStep(r, id == analysisId));
            Object parent = r.get("parent_analysis_id");
            id = parent instanceof Number n ? n.intValue() : null;
        }
        Collections.reverse(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStep(Map<String, Object> r, boolean held) {
        Map<String, Object> result = readMap((String) r.get("result_json"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", r.get("trigger_type") == null ? "ALARM" : r.get("trigger_type"));
        m.put("shotNo", r.get("as_of_shot"));
        m.put("decision", r.get("decision"));
        m.put("causeCode", r.get("cause_code"));
        m.put("confidence", r.get("confidence"));
        m.put("verifyStatus", r.get("verify_status"));
        m.put("reasoningSummary", result.get("reasoningSummary"));
        m.put("evidence", result.get("evidence"));
        m.put("watchItems", result.get("watchItems"));
        if (held) {
            m.put("holdReason", r.get("hold_reason"));
            m.put("holdDetail", r.get("hold_detail"));
        }
        return m;
    }

    /** LLM 응답(JSON, 앞뒤 글·코드 블록 허용) → Brief. 읽지 못하거나 summary 가 비면 null. */
    @SuppressWarnings("unchecked")
    static Brief parse(String text, String model) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            return null;
        }
        Map<String, Object> map;
        try {
            map = new ObjectMapper().readValue(m.group(), Map.class);
        } catch (RuntimeException e) {
            return null;
        }
        List<String> summary = lines(map.get("summary"));
        if (summary.isEmpty()) {
            return null;
        }
        return new Brief(summary, lines(map.get("checks")), "LLM", model);
    }

    /** LLM 없이 기록에서 바로 뽑은 요약: 판단 흐름, 마지막 근거, 보류 사유 + 마지막 watchItems. */
    @SuppressWarnings("unchecked")
    static Brief ruleBrief(List<Map<String, Object>> chain) {
        List<String> summary = new ArrayList<>();
        StringBuilder flow = new StringBuilder("판단 흐름: ");
        for (int i = 0; i < chain.size(); i++) {
            Map<String, Object> s = chain.get(i);
            if (i > 0) {
                flow.append(" → ");
            }
            flow.append(s.get("trigger")).append(s.get("shotNo") == null ? "" : " shot " + s.get("shotNo"))
                    .append(' ').append(s.get("decision")).append('(').append(s.get("causeCode")).append(')');
        }
        summary.add(flow.toString());
        Map<String, Object> last = chain.get(chain.size() - 1);
        if (last.get("reasoningSummary") != null) {
            summary.add("마지막 분석: " + last.get("reasoningSummary"));
        }
        if (last.get("holdDetail") != null) {
            summary.add("보류 사유: " + last.get("holdDetail"));
        }
        return new Brief(cap(summary), lines(last.get("watchItems")), "RULE", null);
    }

    private static List<String> lines(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().strip());
                }
            }
        }
        return cap(out);
    }

    private static List<String> cap(List<String> l) {
        return l.size() > MAX_LINES ? List.copyOf(l.subList(0, MAX_LINES)) : List.copyOf(l);
    }

    private ChatClient chatClient() {
        ChatClient c = chatClient;
        if (c == null) {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                return null;
            }
            c = builder.build();
            chatClient = c;
        }
        return c;
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
}
