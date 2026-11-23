package com.qualiguard.qms.agent.pipeline;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qualiguard.qms.agent.AnalysisResult;
import com.qualiguard.qms.agent.EvidenceNumbers;
import com.qualiguard.qms.agent.ToolObservations;

import tools.jackson.databind.ObjectMapper;

/**
 * 보고서 작성 에이전트 (ADR-016, docs/03 F-11~F-13): 부적합 초안의 문장(제목·현상·추정 원인·권고 조치)을 쓴다.
 *
 * 모드 (qg.agent.writer.mode)
 *  - analysis-text : 원인 분석 에이전트가 쓴 문장을 그대로 쓴다 (기본, 기존 동작)
 *  - local         : 로컬 소형 LLM(Ollama)이 기밀 자료(원시값·정비 기록)를 보고 문장을 쓴다. 클라우드 호출 0건.
 *                    실패하거나 시간을 넘기면 클라우드로 넘기지 않고 근거 표 초안(template)으로 대신한다 (F-13).
 *                    넘겨준 자료에 없는 수치를 쓰면(수치 변조, 로드맵 A3) 역시 근거 표 초안으로 대신한다. 실험 기능.
 *  - template      : LLM 없이 근거 표 + "사람 작성 필요" 표시만 있는 초안
 * 판단(decision·원인 코드·확신도)은 바꾸지 않는다. 문장만 쓴다.
 */
public class ReportWriterAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportWriterAgent.class);

    /** 작성 모드와 로컬 모델 설정. */
    public record Settings(String mode, String ollamaUrl, String model, int timeoutSeconds) {
    }

    /** 로컬 모델 호출 (단위 시험에서는 흉내 낸다). 실패하면 예외. */
    public interface LocalModel {
        String generate(String prompt, Duration timeout);
    }

    /** 작성 입력 중 기밀 자료(원시값·정비 기록)를 읽는다. 로컬 모델에만 넘긴다. */
    public interface ConfidentialFacts {
        List<Map<String, Object>> recentShots(String equipmentId, String asOf, int n);

        List<Map<String, Object>> maintenance(String equipmentId, int n);
    }

    private final Settings settings;
    private final LocalModel localModel;
    private final ConfidentialFacts facts;
    private final ObjectMapper objectMapper;
    private final IncidentStore store;
    private final EventBus bus;

    public ReportWriterAgent(Settings settings, LocalModel localModel, ConfidentialFacts facts, ObjectMapper objectMapper,
            IncidentStore store, EventBus bus, AgentEventRouter router) {
        this.settings = settings;
        this.localModel = localModel;
        this.facts = facts;
        this.objectMapper = objectMapper;
        this.store = store;
        this.bus = bus;
        router.register(AgentTopics.REPORT_REQUESTED, this::onRequested);
    }

    void onRequested(AgentEvent e) {
        Incident i = store.get(e.incidentId()).orElse(null);
        if (i == null || i.result == null) {
            return;
        }
        long t0 = System.nanoTime();
        String mode = settings.mode() == null ? "analysis-text" : settings.mode().toLowerCase(Locale.ROOT);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("requestedMode", mode);
        AnalysisResult written = null;
        String used;
        switch (mode) {
            case "local" -> {
                try {
                    LocalDraft d = writeLocal(i);
                    report.put("model", settings.model());
                    // 수치 변조 검사 (로드맵 A3): 로컬 모델 문장의 수치가 넘겨준 자료(근거·원시값·정비)에 있는지
                    report.put("numberClaims", d.numberClaims());
                    report.put("numberUnsupported", d.unsupported().size());
                    if (d.unsupported().isEmpty()) {
                        written = d.result();
                        used = "LOCAL";
                    } else {
                        written = template(i.result);
                        used = "TEMPLATE_NUMBER_GUARD";
                        report.put("fallbackReason", "자료에 없는 수치: " + String.join(", ", d.unsupported()));
                        log.warn("[작성] 로컬 모델 문장에 자료에 없는 수치 {} → 근거 표 초안으로 대신", d.unsupported());
                    }
                } catch (RuntimeException ex) {
                    // 클라우드로 넘기지 않는다 (F-13): 근거 표 초안 + 사람 작성 필요
                    written = template(i.result);
                    used = "TEMPLATE_FALLBACK";
                    report.put("fallbackReason", shorten(ex.toString(), 300));
                    log.warn("[작성] 로컬 모델 실패 → 근거 표 초안으로 대신 (클라우드 호출 없음): {}", ex.toString());
                }
            }
            case "template" -> {
                written = template(i.result);
                used = "TEMPLATE";
            }
            default -> used = "ANALYSIS_TEXT";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        report.put("mode", used);
        report.put("cloudCalls", 0);
        report.put("latencyMs", ms);
        i.written = written;
        i.report = report;
        i.trace.add("writeReport(" + used + ") @" + i.elapsedMs() + "ms");
        bus.publish(AgentTopics.REPORT_COMPLETED, new AgentEvent(i.id, e.attempt(), report));
    }

    /** 로컬 모델이 쓴 초안과 수치 대조 결과 (unsupported: 넘겨준 자료에 없는 수치 예시). */
    record LocalDraft(AnalysisResult result, int numberClaims, List<String> unsupported) {
    }

    private LocalDraft writeLocal(Incident i) {
        if (localModel == null) {
            throw new IllegalStateException("로컬 모델이 설정되지 않았습니다");
        }
        AnalysisResult r = i.result;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("equipmentId", i.job.equipmentId());
        input.put("decision", r.decision());
        input.put("causeCode", r.causeCode());
        input.put("evidence", r.evidence());
        input.put("analysisSummary", r.reasoningSummary());
        // 기밀 자료: 이 프롬프트는 로컬 모델에만 간다
        input.put("recentShots", facts == null ? List.of() : facts.recentShots(i.job.equipmentId(), i.job.asOf(), 5));
        input.put("maintenance", facts == null ? List.of() : facts.maintenance(i.job.equipmentId(), 3));
        String prompt = """
                당신은 사출성형 품질 담당자를 돕는 보고서 작성자입니다. 아래 분석 결과와 공정 자료로 부적합 보고서 초안 문장을 씁니다.
                원인 판단(causeCode)은 바꾸지 말고, 자료에 없는 수치나 사실을 지어내지 마세요. 모든 문장은 한국어입니다.
                아래 JSON 하나만 출력하세요:
                {"title":"한 줄 제목","phenomenon":"관찰된 현상","suspectedCause":"추정 원인과 이유","recommendedAction":"현장 점검·조치"}

                """ + objectMapper.writeValueAsString(input);
        String text = localModel.generate(prompt, Duration.ofSeconds(Math.max(5, settings.timeoutSeconds())));
        Map<String, Object> m = readJson(text);
        String title = str(m.get("title"));
        String phenomenon = str(m.get("phenomenon"));
        String cause = str(m.get("suspectedCause"));
        String action = str(m.get("recommendedAction"));
        if (title == null || phenomenon == null || cause == null) {
            throw new IllegalStateException("로컬 모델 응답에 필수 문장이 없습니다: " + shorten(text, 200));
        }
        AnalysisResult out = new AnalysisResult(r.decision(), r.causeCode(), r.confidence(), title, phenomenon, cause,
                r.evidence(), r.similarCases(), action == null ? r.recommendedAction() : action, r.reasoningSummary(),
                r.watchItems(), r.candidateCauses());
        EvidenceNumbers.Report numbers = EvidenceNumbers.check(Arrays.asList(title, phenomenon, cause, action),
                sourceNumbers(input));
        return new LocalDraft(out, numbers.claimCount(), numbers.unverifiedSamples(5));
    }

    /** 로컬 모델에 넘긴 자료의 모든 수치 (근거 문장, 요약, 원시값·정비 기록 행). */
    @SuppressWarnings("unchecked")
    static ToolObservations sourceNumbers(Map<String, Object> input) {
        ToolObservations obs = new ToolObservations();
        for (Object v : input.values()) {
            if (v instanceof List<?> list) {
                for (Object x : list) {
                    if (x instanceof Map<?, ?> row) {
                        obs.addRows(List.of((Map<String, Object>) row));
                    } else {
                        obs.addValue(x);
                    }
                }
            } else {
                obs.addValue(v);
            }
        }
        return obs;
    }

    /** 근거 표 초안: 분석의 구조화된 값만으로 만들고, 사람이 문장을 쓰도록 표시한다. */
    static AnalysisResult template(AnalysisResult r) {
        List<String> ev = r.evidence() == null ? List.of() : r.evidence();
        StringBuilder table = new StringBuilder("| # | 근거 |\n|---|---|\n");
        for (int k = 0; k < ev.size(); k++) {
            table.append("| ").append(k + 1).append(" | ").append(ev.get(k).replace("|", "/")).append(" |\n");
        }
        String title = "[" + r.causeCode() + "] 부적합 초안 — 문장은 사람 작성 필요";
        return new AnalysisResult(r.decision(), r.causeCode(), r.confidence(), title,
                "(사람 작성 필요) 근거 표:\n" + table,
                "(사람 작성 필요) AI 추정 원인 코드: " + r.causeCode() + ", 확신도 " + r.confidence(),
                r.evidence(), r.similarCases(), "(사람 작성 필요)", r.reasoningSummary(), r.watchItems(),
                r.candidateCauses());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String text) {
        String t = text == null ? "" : text.replace("```json", "").replace("```", "");
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) {
            throw new IllegalStateException("로컬 모델 응답이 JSON 이 아닙니다: " + shorten(text, 200));
        }
        return objectMapper.readValue(t.substring(s, e + 1), Map.class);
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private static String shorten(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Ollama HTTP API (/api/generate). Spring AI Ollama 모듈을 따로 싣지 않아 클라우드 ChatModel 과 섞이지 않는다. */
    public static class OllamaClient implements LocalModel {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        private final String url;
        private final String model;
        private final ObjectMapper objectMapper;

        public OllamaClient(String url, String model, ObjectMapper objectMapper) {
            this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            this.model = model;
            this.objectMapper = objectMapper;
        }

        @Override
        @SuppressWarnings("unchecked")
        public String generate(String prompt, Duration timeout) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            body.put("format", "json");
            body.put("keep_alive", "1m");                          // 메모리 8GB: 쓰고 나면 1분 뒤 내린다 (docs/03 2절)
            body.put("options", Map.of("temperature", 0.2, "num_ctx", 4096));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/generate"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Ollama " + res.statusCode() + ": " + res.body());
                }
                Map<String, Object> m = objectMapper.readValue(res.body(), Map.class);
                Object text = m.get("response");
                if (text == null) {
                    throw new IllegalStateException("Ollama 응답에 response 가 없습니다");
                }
                return text.toString();
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Ollama 호출 실패: " + ex, ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ollama 호출 중단", ex);
            }
        }
    }

    /** 운영용 기밀 자료 조회. */
    public static class JdbcConfidentialFacts implements ConfidentialFacts {
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

        public JdbcConfidentialFacts(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public List<Map<String, Object>> recentShots(String equipmentId, String asOf, int n) {
            return simplify(jdbc.queryForList("""
                    SELECT TOP (?) shot_no, mold_temp, barrel_temp, injection_pressure, holding_pressure, cushion,
                           cycle_time, motor_current, inspection_result, defect_type
                    FROM dbo.process_shot
                    WHERE equipment_id = ? AND shot_ts <= CAST(? AS DATETIMEOFFSET(3))
                    ORDER BY shot_ts DESC, shot_id DESC
                    """, n, equipmentId, asOf));
        }

        @Override
        public List<Map<String, Object>> maintenance(String equipmentId, int n) {
            return simplify(jdbc.queryForList("EXEC dbo.usp_GetMaintenanceHistory ?, ?", equipmentId, n));
        }

        private static List<Map<String, Object>> simplify(List<Map<String, Object>> rows) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                r.forEach((k, v) -> m.put(k, v == null || v instanceof String || v instanceof Number ? v : v.toString()));
                out.add(m);
            }
            return out;
        }
    }
}
