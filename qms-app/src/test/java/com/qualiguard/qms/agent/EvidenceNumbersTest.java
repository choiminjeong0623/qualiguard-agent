package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 근거 문장 속 수치 추출과 도구 결과 대조. */
class EvidenceNumbersTest {

    static List<String> texts(String sentence) {
        return EvidenceNumbers.extract(sentence).stream().map(c -> c.text() + c.unit()).toList();
    }

    @Test
    void 수치와_단위를_뽑고_번호와_날짜는_뺀다() {
        assertEquals(List.of("20shot", "224.1", "220"), texts("IMM-01 실린더 온도 최근 20 shot 평균 224.1℃ (규격 하한 220℃)"));
        assertEquals(List.of("3"), texts("NCR-2025-0012 와 AI-2026-0035 처럼 2026-11-14T10:00:00Z 이후 불량 3건"));
        assertEquals(List.of("+5.3", "−0.8", "1,250"), texts("온도 +5.3℃, 압력 −0.8MPa, 누적 1,250개"));
        assertEquals(List.of("6.2%", "4.1%p", "1.8σ", "2배"), texts("불량률 6.2% 로 4.1%p 증가, 전류 1.8σ, 2배"));
        assertEquals(List.of("220", "230"), texts("규격 220-230℃"), "범위의 - 는 부호가 아니다");
    }

    @Test
    void 반올림한_값은_같은_값으로_본다() {
        var obs = ToolObservations.of(List.of(224.13, 31.04));
        var r = EvidenceNumbers.check(List.of("온도 224.1℃", "온도 224℃", "사이클 31초", "온도 223.9℃"), obs);
        assertEquals(1, r.unverifiedCount(), r.toString());
        assertEquals("223.9", r.sentences().get(3).unverified().get(0).text());
    }

    @Test
    void 비율은_퍼센트로_적어도_맞다() {
        var obs = ToolObservations.of(List.of(0.062));
        assertEquals(0, EvidenceNumbers.check(List.of("불량률 6.2%"), obs).unverifiedCount());
        assertEquals(1, EvidenceNumbers.check(List.of("불량 6.2건"), obs).unverifiedCount(), "퍼센트가 아니면 ×100 하지 않음");
    }

    @Test
    void 부호가_붙은_변화량은_두_값의_차이와_맞춘다() {
        var obs = ToolObservations.of(List.of(229.4, 224.1, 0.012, 0.062));
        var r = EvidenceNumbers.check(List.of("온도 −5.3℃ 하락", "불량률 5%p 증가", "온도 5.3℃"), obs);
        assertTrue(r.sentences().get(0).unverified().isEmpty(), "229.4 - 224.1 = 5.3");
        assertTrue(r.sentences().get(1).unverified().isEmpty(), "(0.062 - 0.012) × 100 = 5");
        assertEquals(1, r.sentences().get(2).unverified().size(), "부호 없는 수는 차이로 맞추지 않는다");
    }

    @Test
    void 조회_범위와_계산_값은_대조하지_않는다() {
        var r = EvidenceNumbers.check(List.of("최근 60 shot 중", "전류 +1.8σ", "2배 증가"), new ToolObservations());
        assertEquals(0, r.claimCount());
    }

    @Test
    void 도구_결과의_글자_칸에서도_수치를_모은다() {
        var obs = new ToolObservations();
        obs.addRows(List.of(Map.of("avg", "224.13", "evidence", "당시 실린더 온도 218℃ 로 하락", "ncr_no", "NCR-2025-0012")));
        obs.addArgs("IMM-01", 300, 20, "2026-11-14T10:00:00Z");
        assertTrue(obs.values().contains(new BigDecimal("224.13")));
        assertTrue(obs.values().contains(new BigDecimal("218")));
        assertTrue(obs.values().contains(new BigDecimal("300")));
        assertFalse(obs.values().contains(new BigDecimal("2025")), "사례 번호는 수치가 아님");
        assertTrue(obs.values().contains(new BigDecimal("300")), "300 이 3E+2 로 바뀌어 equals 가 어긋나지 않게");
    }

    @Test
    void 허용_오차는_자릿수와_크기로_정한다() {
        assertEquals(0.05, EvidenceNumbers.tolerance(new BigDecimal("224.1")), 1e-6);
        assertEquals(0.5, EvidenceNumbers.tolerance(new BigDecimal("224")), 1e-6);
        assertTrue(EvidenceNumbers.hasDifference(new double[] { 1, 4, 9 }, 5, 0.01));
        assertFalse(EvidenceNumbers.hasDifference(new double[] { 1, 4, 9 }, 6, 0.01));
    }
}
