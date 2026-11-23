package com.qualiguard.qms.agent.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자바 특징·확률 계산이 파이썬(scikit-learn) 과 같은가.
 * 시험 데이터(parity.json)는 scripts/ml_cascade_eval.py --fixture 가 seed 42 시뮬레이터 출력에서 만든다:
 * 원인별 경보 몇 개, 그 설비의 shot 데이터, 파이썬이 낸 특징과 확률.
 */
class CauseFeaturesParityTest {

    static JsonNode fixture;
    static CauseForest forest;
    static Map<String, CauseFeatures.Series> series = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        try (InputStream in = CauseFeaturesParityTest.class.getResourceAsStream("/ml/parity.json")) {
            fixture = mapper.readTree(in);
        }
        try (InputStream in = CauseFeaturesParityTest.class.getResourceAsStream("/ml/cause-forest.json")) {
            forest = CauseForest.load(in, mapper);
        }
        fixture.path("shots").properties().forEach(e -> {
            CauseFeatures.Series s = new CauseFeatures.Series();
            for (JsonNode row : e.getValue()) {
                Double[] v = new Double[CauseFeatures.SENSORS.size()];
                for (int j = 0; j < v.length; j++) {
                    JsonNode n = row.get(1).get(j);
                    v[j] = n == null || n.isNull() ? null : n.asDouble();
                }
                s.put(row.get(0).asLong(), v, row.get(2).isNull() ? null : row.get(2).asString(),
                        row.get(3).isNull() ? null : row.get(3).asString());
            }
            series.put(e.getKey(), s);
        });
    }

    @Test
    void 모델의_특징_이름과_순서가_자바와_같다() {
        assertThat(forest.features()).isEqualTo(CauseFeatures.NAMES);
        assertThat(forest.classes()).containsExactlyInAnyOrder("HEATER_DEGRADATION", "COOLING_ISSUE",
                "CHECK_RING_WEAR", "SENSOR_FAULT", "NOISE");
        assertThat(fixture.path("alarms").size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 특징_66개가_파이썬과_같다() {
        for (JsonNode a : fixture.path("alarms")) {
            double[] x = CauseFeatures.compute(series.get(a.get("eq").asString()), a.get("shot").asLong(),
                    a.get("rule").asString(), a.get("sensor").asString());
            for (int k = 0; k < x.length; k++) {
                double expected = a.get("features").get(k).asDouble();
                assertThat(x[k]).as("%s shot %d %s", a.get("eq").asString(), a.get("shot").asLong(),
                        CauseFeatures.NAMES.get(k)).isCloseTo(expected, within(1e-6 + Math.abs(expected) * 1e-9));
            }
        }
    }

    @Test
    void 확률이_scikit_learn_과_같고_원인도_같다() {
        List<String> classes = forest.classes();
        int checked = 0;
        for (JsonNode a : fixture.path("alarms")) {
            double[] x = CauseFeatures.compute(series.get(a.get("eq").asString()), a.get("shot").asLong(),
                    a.get("rule").asString(), a.get("sensor").asString());
            double[] p = forest.proba(x);
            int best = 0;
            for (int c = 0; c < p.length; c++) {
                assertThat(p[c]).isCloseTo(a.get("proba").get(c).asDouble(), within(1e-4));
                if (p[c] > p[best]) {
                    best = c;
                }
            }
            // 시험 데이터는 같은 시나리오(seed 42)라 원인도 맞아야 한다 (ml_cascade_report: seed 42 경보 정확도 100%)
            assertThat(classes.get(best)).isEqualTo(a.get("label").asString());
            checked++;
        }
        assertThat(checked).isEqualTo(fixture.path("alarms").size());
    }
}
