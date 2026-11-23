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
        double[] x = new double[CauseFeatures.NAMES.size()];
        x[CauseFeatures.SENSORS.indexOf("barrelTemp") * CauseFeatures.PER_SENSOR + 1] = -4.2;   // z20
        x[CauseFeatures.SENSORS.indexOf("cushion") * CauseFeatures.PER_SENSOR + 1] = 1.5;
        return new MlTriage.Prediction(cause, p, Map.of(cause, p), 500, "XBAR", "barrelTemp", x, 3);
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
