package com.qualiguard.qms.agent.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.agent.IncidentAnalyzer.Job;

class MlTriageTest {

    static MlTriage.Prediction prediction(String cause, double p) {
        return prediction(cause, p, 1.0);
    }

    /** novelty: 낯선 정도 (기준 2.0 보다 크면 낯선 경보) */
    static MlTriage.Prediction prediction(String cause, double p, double novelty) {
        double[] x = new double[CauseFeatures.NAMES.size()];
        x[CauseFeatures.SENSORS.indexOf("barrelTemp") * CauseFeatures.PER_SENSOR + 1] = -4.2;   // z20
        x[CauseFeatures.SENSORS.indexOf("cushion") * CauseFeatures.PER_SENSOR + 1] = 1.5;
        return new MlTriage.Prediction(cause, p, Map.of(cause, p), 500, "XBAR", "barrelTemp", x, 3, novelty, 2.0);
    }

    static MlTriage triage(MlTriage.Mode mode) {
        // 모델이 필요한 판단 규칙만 본다 (모델 계산은 CauseFeaturesParityTest)
        return new MlTriage(null, dummyForest(), mode, 0.9);
    }

    static CauseForest dummyForest() {
        String json = "{\"kind\":\"random-forest\",\"features\":[\"a\"],\"classes\":[\"NOISE\"],"
                + "\"trees\":[{\"left\":[-1],\"right\":[-1],\"feature\":[-2],\"threshold\":[0],\"value\":[[1.0]]}]}";
        try {
            return CauseForest.load(new java.io.ByteArrayInputStream(json.getBytes()),
                    tools.jackson.databind.json.JsonMapper.builder().build());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 학습한_고장을_기준_이상으로_골랐을_때만_LLM_을_건너뛴다() {
        MlTriage skip = triage(MlTriage.Mode.SKIP);
        assertThat(skip.skipLlm(prediction("HEATER_DEGRADATION", 0.95))).isTrue();
        assertThat(skip.skipLlm(prediction("SENSOR_FAULT", 0.90))).isTrue();
        assertThat(skip.skipLlm(prediction("HEATER_DEGRADATION", 0.89))).isFalse();
        // 단발성은 확신해도 LLM 으로 (학습하지 않은 고장이 여기로 들어온다)
        assertThat(skip.skipLlm(prediction("NOISE", 0.99))).isFalse();
        assertThat(skip.skipLlm(null)).isFalse();
    }

    @Test
    void 낯선_경보는_확률이_높아도_LLM_으로_넘긴다() {
        MlTriage skip = triage(MlTriage.Mode.SKIP);
        MlTriage.Prediction strange = prediction("CHECK_RING_WEAR", 0.97, 3.5);
        assertThat(strange.unfamiliar()).isTrue();
        assertThat(skip.skipLlm(strange)).isFalse();
        assertThat(skip.route(strange, "i-1")).isEqualTo(MlTriage.Route.LLM_UNFAMILIAR);
        // 검사를 끄면 건너뛴다
        MlTriage noCheck = new MlTriage(null, dummyForest(), MlTriage.Mode.SKIP, 0.9, false, 0.0);
        assertThat(noCheck.skipLlm(strange)).isTrue();
        // 모델에 낯선 경보 정보가 없으면(NaN) 검사하지 않는다
        assertThat(prediction("CHECK_RING_WEAR", 0.97, Double.NaN).unfamiliar()).isFalse();
    }

    @Test
    void 경로는_단발성_낮은_확률_낯선_경보_표본_점검_순서로_정한다() {
        MlTriage skip = new MlTriage(null, dummyForest(), MlTriage.Mode.SKIP, 0.9, true, 0.0);
        assertThat(skip.route(prediction("NOISE", 0.99), "i")).isEqualTo(MlTriage.Route.LLM_NOISE);
        assertThat(skip.route(prediction("HEATER_DEGRADATION", 0.8), "i")).isEqualTo(MlTriage.Route.LLM_LOW);
        assertThat(skip.route(prediction("HEATER_DEGRADATION", 0.95), "i")).isEqualTo(MlTriage.Route.SKIPPED);
        assertThat(MlTriage.Route.SKIPPED.usesLlm()).isFalse();
        assertThat(MlTriage.Route.AUDIT.usesLlm()).isTrue();
        assertThat(triage(MlTriage.Mode.SHADOW).route(prediction("NOISE", 0.99), "i")).isEqualTo(MlTriage.Route.SHADOW);
        // shadow 에서도 skip 규칙이었다면 건너뛰었을지 기록한다
        assertThat(triage(MlTriage.Mode.SHADOW).wouldSkip(prediction("HEATER_DEGRADATION", 0.95))).isTrue();
    }

    @Test
    void 표본_점검은_대략_설정한_비율이고_같은_사건은_늘_같다() {
        MlTriage audit = new MlTriage(null, dummyForest(), MlTriage.Mode.SKIP, 0.9, true, 0.1);
        int n = 0;
        for (int k = 0; k < 5000; k++) {
            n += audit.audited(java.util.UUID.nameUUIDFromBytes(("inc-" + k).getBytes()).toString()) ? 1 : 0;
        }
        assertThat(n).isBetween(400, 600);
        String id = "11111111-2222-3333-4444-555555555555";
        assertThat(audit.audited(id)).isEqualTo(audit.audited(id));
        assertThat(audit.route(prediction("HEATER_DEGRADATION", 0.95), id))
                .isEqualTo(audit.audited(id) ? MlTriage.Route.AUDIT : MlTriage.Route.SKIPPED);
        assertThat(new MlTriage(null, dummyForest(), MlTriage.Mode.SKIP, 0.9, true, 0.0).audited(id)).isFalse();
        assertThatThrownBy(() -> new MlTriage(null, dummyForest(), MlTriage.Mode.SKIP, 0.9, true, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 일치율은_건너뛰었을_사건에서_LLM_과_같은_비율() {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(row("a", "SHADOW", "HEATER_DEGRADATION", true, "HEATER_DEGRADATION", "HEATER_DEGRADATION"));
        rows.add(row("b", "SHADOW", "CHECK_RING_WEAR", true, "HEATER_DEGRADATION", null));
        rows.add(row("c", "SHADOW", "NOISE", false, "COOLING_ISSUE", "COOLING_ISSUE"));
        rows.add(row("d", "SKIPPED", "COOLING_ISSUE", true, null, "COOLING_ISSUE"));
        Map<String, Object> s = MlTriageStats.summarize(rows, 7);
        assertThat(s.get("incidents")).isEqualTo(4);
        assertThat(s.get("wouldSkip")).isEqualTo(0.75);
        @SuppressWarnings("unchecked")
        Map<String, Object> vsLlm = (Map<String, Object>) s.get("vsLlm");
        assertThat(vsLlm.get("compared")).isEqualTo(3);
        assertThat(vsLlm.get("agreement")).isEqualTo(0.333);
        assertThat(vsLlm.get("wouldSkipCompared")).isEqualTo(2);
        assertThat(vsLlm.get("wouldSkipAgreement")).isEqualTo(0.5);
        assertThat((java.util.List<?>) vsLlm.get("wouldSkipDisagreements")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> vsHuman = (Map<String, Object>) s.get("vsHuman");
        assertThat(vsHuman.get("approved")).isEqualTo(3);
        assertThat(vsHuman.get("mlAgreement")).isEqualTo(0.667);   // a, d 맞고 c(NOISE) 틀림
        assertThat(vsHuman.get("llmAgreement")).isEqualTo(1.0);    // a, c
        assertThat(MlTriageStats.summarize(java.util.List.of(), 7).get("wouldSkip")).isNull();
    }

    static Map<String, Object> row(String id, String route, String ml, boolean ws, String llm, String human) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("incident_id", id);
        r.put("equipment_id", "IMM-01");
        r.put("route", route);
        r.put("ml_cause", ml);
        r.put("p", 0.95);
        r.put("would_skip", ws);
        r.put("llm_cause", llm);
        r.put("human_cause", human);
        return r;
    }

    @Test
    void shadow_와_off_는_LLM_을_건너뛰지_않는다() {
        assertThat(triage(MlTriage.Mode.SHADOW).skipLlm(prediction("COOLING_ISSUE", 0.99))).isFalse();
        assertThat(MlTriage.off().skipLlm(prediction("COOLING_ISSUE", 0.99))).isFalse();
        assertThat(MlTriage.off().enabled()).isFalse();
        assertThatThrownBy(() -> new MlTriage(null, null, MlTriage.Mode.SKIP, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 경보_사건이_아니면_분류하지_않는다() {
        MlTriage skip = triage(MlTriage.Mode.SKIP);
        Job recheck = new Job("RECHECK", "IMM-01", null, null, List.of(), List.of(), false, Map.of(),
                "2026-11-23T00:00:00Z", 600L, null, 1, null, null);
        assertThat(skip.predict(recheck)).isEmpty();
        Job early = new Job("ALARM", "IMM-01", "a", null,
                List.of(Map.of("shot_no", 80L, "rule_code", "XBAR", "sensor_key", "moldTemp")), List.of("a"), true,
                Map.of(), "2026-11-23T00:00:00Z", 80L, null, null, null, null);
        assertThat(skip.predict(early)).as("기준 구간(100 shot) 안의 경보").isEmpty();
    }

    @Test
    void 근거_센서는_최근_변화가_큰_순서() {
        assertThat(MlTriage.topSensors(prediction("HEATER_DEGRADATION", 0.95), 2))
                .containsExactly("barrelTemp", "cushion");
    }

    @Test
    void 도구_값은_그대로_적는다() {
        assertThat(MlTriage.num(new BigDecimal("224.130"))).isEqualTo("224.13");
        assertThat(MlTriage.num(new BigDecimal("6.0"))).isEqualTo("6");
        assertThat(MlTriage.num(12)).isEqualTo("12");
    }
}
