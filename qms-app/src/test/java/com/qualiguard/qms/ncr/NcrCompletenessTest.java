package com.qualiguard.qms.ncr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NcrCompletenessTest {

    private static Map<String, Object> full() {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "IMM-01 금형 온도 상승");
        m.put("phenomenon", "금형 온도가 30 shot 동안 계속 올랐습니다");
        m.put("suspected_cause", "냉각수 유량 저하로 금형 냉각이 부족함");
        m.put("cause_code", "COOLING_ISSUE");
        m.put("evidence", "냉각수 유량 12.1 → 8.4 L/min, 금형 온도 +6.2℃");
        m.put("recommended_action", "냉각수 필터 점검과 유량계 교정");
        m.put("related_sensors", "[\"mold_temp\",\"coolant_flow\"]");
        m.put("defect_type", "SHORT_SHOT");
        m.put("affected_lots", "L261123-IMM-01-03,L261123-IMM-01-04");
        m.put("action_owner", "성형1팀 김반장");
        m.put("action_due_date", "2026-11-30");
        return m;
    }

    @Test
    void 모든_항목을_채우면_통과() {
        NcrCompleteness.Result r = NcrCompleteness.check(full(), 2);
        assertThat(r.passed()).isEqualTo(r.total()).isEqualTo(NcrCompleteness.ITEMS.size());
        assertThat(r.missing()).isEmpty();
        assertThat(r.rate()).isEqualTo(1.0);
    }

    @Test
    void 빠진_항목과_이유를_알려준다() {
        Map<String, Object> m = full();
        m.put("cause_code", "UNKNOWN");
        m.put("evidence", "온도가 올랐음");
        m.put("related_sensors", "[]");
        m.put("phenomenon", "짧음");
        m.put("affected_lots", null);
        NcrCompleteness.Result r = NcrCompleteness.check(m, 0);
        assertThat(r.missing()).containsExactly("현상", "원인 코드", "근거", "관련 센서", "영향 범위");
        assertThat(r.checks()).filteredOn(c -> c.key().equals("evidence"))
                .singleElement().satisfies(c -> assertThat(c.reason()).contains("숫자"));
        assertThat(r.passed()).isEqualTo(6);
    }

    @Test
    void 담당자와_기한은_사람이_채우기_전까지_빠짐() {
        Map<String, Object> m = full();
        m.remove("action_owner");
        m.put("action_due_date", " ");
        NcrCompleteness.Result r = NcrCompleteness.check(m, 1);
        assertThat(r.total()).isEqualTo(11);
        assertThat(r.missing()).containsExactly("담당자", "조치 기한");
    }

    @Test
    void 경보는_있어도_로트가_없으면_영향_범위_빠짐() {
        Map<String, Object> m = full();
        m.put("affected_lots", "");
        NcrCompleteness.Result r = NcrCompleteness.check(m, 3);
        assertThat(r.missing()).containsExactly("영향 범위");
        assertThat(r.checks()).filteredOn(c -> c.key().equals("scope"))
                .singleElement().satisfies(c -> assertThat(c.reason()).contains("경보 3건"));
    }

    @Test
    void 확정_원인만_있어도_원인_항목은_통과() {
        Map<String, Object> m = full();
        m.put("suspected_cause", null);
        m.put("root_cause", "체크링 마모로 역류가 생겨 계량이 흔들림");
        assertThat(NcrCompleteness.check(m, 1).missing()).isEmpty();
    }

    @Test
    void 집계는_항목별_충족률과_완전_비율() {
        Map<String, Object> bad = full();
        bad.put("defect_type", " ");
        Map<String, Object> summary = NcrCompleteness.summarize(List.of(
                NcrCompleteness.check(full(), 1), NcrCompleteness.check(bad, 1)));
        assertThat(summary).containsEntry("count", 2).containsEntry("completeCount", 1L)
                .containsEntry("completeRate", 0.5);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> items = (Map<String, Map<String, Object>>) summary.get("items");
        assertThat(items.get("defectType")).containsEntry("passRate", 0.5);
        assertThat(items.get("title")).containsEntry("passRate", 1.0);
    }

    @Test
    void 빈_목록() {
        assertThat(NcrCompleteness.summarize(List.of())).containsEntry("count", 0).containsEntry("completeRate", null);
    }
}
