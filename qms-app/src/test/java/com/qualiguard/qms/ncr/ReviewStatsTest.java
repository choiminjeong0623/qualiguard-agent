package com.qualiguard.qms.ncr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** AI 초안 검토 지표 (로드맵 P3): 원문은 검토 이력의 첫 before 로 되살리고, 최종값과 비교한다. */
class ReviewStatsTest {

    private final JsonMapper om = JsonMapper.builder().build();

    private static ReviewStats.Ncr ncr(String status, Map<String, String> current, String... edits) {
        return new ReviewStats.Ncr("AI-" + status, status, new HashMap<>(current), List.of(edits));
    }

    @Test
    void 고치지_않고_승인하면_그대로_승인() {
        var n = ncr("APPROVED", Map.of("rootCause", "히터 열화"));
        assertThat(ReviewStats.changedFields(n, om)).isEmpty();
    }

    @Test
    void 처음_고친_이력의_before_가_AI_원문이다() {
        // AI: "히터 열화" → 사람: "냉각 막힘" → 다시 "냉각수 배관 막힘"
        var n = ncr("APPROVED", Map.of("rootCause", "냉각수 배관 막힘", "causeCode", "COOLING_ISSUE"),
                "{\"rootCause\":{\"before\":\"히터 열화\",\"after\":\"냉각 막힘\"},"
                        + "\"causeCode\":{\"before\":\"HEATER_DEGRADATION\",\"after\":\"COOLING_ISSUE\"}}",
                "{\"rootCause\":{\"before\":\"냉각 막힘\",\"after\":\"냉각수 배관 막힘\"}}");
        assertThat(ReviewStats.changedFields(n, om)).containsExactlyInAnyOrder("rootCause", "causeCode");
    }

    @Test
    void 고쳤다가_되돌리면_수정_안_한_것() {
        var n = ncr("APPROVED", Map.of("title", "1호기 미성형"),
                "{\"title\":{\"before\":\"1호기 미성형\",\"after\":\"1호기 쇼트\"}}",
                "{\"title\":{\"before\":\"1호기 쇼트\",\"after\":\"1호기 미성형\"}}");
        assertThat(ReviewStats.changedFields(n, om)).isEmpty();
    }

    @Test
    void 반려율_그대로_승인율_항목별_수정률() {
        var asIs = ncr("APPROVED", Map.of("rootCause", "히터 열화"));
        var closedAsIs = ncr("CLOSED", Map.of("rootCause", "히터 열화"));
        var edited = ncr("APPROVED", Map.of("rootCause", "냉각 막힘"),
                "{\"rootCause\":{\"before\":\"\",\"after\":\"냉각 막힘\"}}");   // AI 가 확정 원인을 비워 둔 초안
        var rejected = ncr("REJECTED", Map.of());
        Map<String, Object> m = ReviewStats.compute(List.of(asIs, closedAsIs, edited, rejected), 2, om);

        assertThat(m).containsEntry("decided", 4).containsEntry("approved", 3).containsEntry("rejected", 1)
                .containsEntry("pending", 2).containsEntry("rejectionRate", 0.25)
                .containsEntry("approvedAsIs", 2).containsEntry("asIsRate", 0.667).containsEntry("editRate", 0.333)
                .containsEntry("avgFieldsChanged", 0.33);
        @SuppressWarnings("unchecked")
        Map<String, Object> rate = (Map<String, Object>) m.get("fieldChangeRate");
        assertThat(rate).containsEntry("rootCause", 0.333).containsEntry("causeCode", 0.0);
    }

    @Test
    void 결정된_것이_없으면_비율은_null() {
        Map<String, Object> m = ReviewStats.compute(List.of(), 0, om);
        assertThat(m.get("rejectionRate")).isNull();
        assertThat(m.get("asIsRate")).isNull();
    }
}
