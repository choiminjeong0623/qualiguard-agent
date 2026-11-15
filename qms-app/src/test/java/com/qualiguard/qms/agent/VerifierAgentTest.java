package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 검증 에이전트 규칙: 일반 원칙만으로 되돌리는지, 멀쩡한 결과는 통과시키는지. */
class VerifierAgentTest {

    static class FakeFacts implements VerifierAgent.Facts {
        Set<String> approved = Set.of("NCR-2025-0012");
        double[] rates = { 0.02, 0.02 };
        String lastAsOf;

        @Override
        public Set<String> approvedCases(List<String> ncrNos, String asOf) {
            lastAsOf = asOf;
            return approved;
        }

        @Override
        public double[] defectRates(String equipmentId, String asOf, int recent, int baseline) {
            return rates;
        }
    }

    static final List<String> FULL_TRACE = List.of("getSpcSummary(IMM-01, 50, t) @100ms",
            "getSensorTrend(IMM-01, barrelTemp, 300, 20, t) @900ms", "getDefectSummary(IMM-01, 300, 50, t) @1500ms",
            "findSimilarNcr(IMM-01, barrelTemp, null, 5, t) @2000ms");

    static AnalysisResult result(String decision, String cause, int conf, List<String> evidence, List<String> cases,
            List<String> watch) {
        return new AnalysisResult(decision, cause, conf, "제목", "현상", "추정", evidence, cases, "점검", "요약", watch);
    }

    static VerifierAgent.Input input(List<String> rules, AnalysisResult r, List<String> trace) {
        return new VerifierAgent.Input("IMM-01", "2026-11-14T10:00:00.000Z", rules, r, trace);
    }

    private final FakeFacts facts = new FakeFacts();
    private final VerifierAgent verifier = new VerifierAgent(facts);

    @Test
    void wellGroundedDraftPasses() {
        var r = result("NCR_DRAFT", "HEATER_DEGRADATION", 82,
                List.of("실린더 온도 최근 20 shot 평균 224.1℃", "불량률 6% (기준 1%)", "모터 전류 +1.8σ"),
                List.of("NCR-2025-0012"), List.of());
        var v = verifier.verify(input(List.of("XBAR"), r, FULL_TRACE));
        assertTrue(v.passed(), v.toString());
        assertEquals("2026-11-14T10:00:00.000Z", facts.lastAsOf, "사례 확인도 기준 시점으로 제한");
    }

    @Test
    void conclusionWithoutLookingAtDefectsIsSentBack() {
        var r = result("NCR_DRAFT", "COOLING_ISSUE", 70, List.of("금형 온도 62.3℃", "사이클 타임 31.2초"), List.of(),
                List.of());
        var v = verifier.verify(input(List.of("XBAR"), r,
                List.of("getSensorTrend(IMM-02, moldTemp, 300, 20, t) @500ms")));
        assertEquals("WEAK", v.status());
        assertTrue(v.fails().stream().anyMatch(f -> f.contains("getDefectSummary")), v.toString());
    }

    @Test
    void evidenceWithoutNumbersIsSentBack() {
        var r = result("NCR_DRAFT", "HEATER_DEGRADATION", 75, List.of("온도가 낮아짐", "불량이 늘어남"), List.of(),
                List.of());
        var v = verifier.verify(input(List.of("XBAR"), r, FULL_TRACE));
        assertEquals("WEAK", v.status());
    }

    @Test
    void draftWithoutCauseIsSentBack() {
        var r = result("NCR_DRAFT", "UNKNOWN", 40, List.of("a 1", "b 2"), List.of(), List.of());
        assertEquals("WEAK", verifier.verify(input(List.of("XBAR"), r, FULL_TRACE)).status());
    }

    @Test
    void sustainedAlarmCalledNoiseIsSentBack() {
        var r = result("OBSERVE", "NOISE", 60, List.of(), List.of(), List.of("다음 20 shot 평균"));
        assertEquals("WEAK", verifier.verify(input(List.of("S_CHART"), r, List.of())).status());
        // 단발 경보(INDIVIDUAL)만 있으면 NOISE 관찰은 정상
        assertTrue(verifier.verify(input(List.of("INDIVIDUAL"), r, List.of())).passed());
    }

    @Test
    void observeNeedsWatchItems() {
        var r = result("OBSERVE", "UNKNOWN", 40, List.of(), List.of(), List.of());
        assertEquals("WEAK", verifier.verify(input(List.of("INDIVIDUAL"), r, List.of())).status());
    }

    @Test
    void citedCaseMustBeApprovedBeforeAsOf() {
        var r = result("NCR_DRAFT", "HEATER_DEGRADATION", 80, List.of("a 1", "b 2", "c 3"),
                List.of("NCR-2025-0012", "NCR-2099-0001"), List.of());
        var v = verifier.verify(input(List.of("XBAR"), r, FULL_TRACE));
        assertEquals("WEAK", v.status());
        assertTrue(v.fails().get(0).contains("NCR-2099-0001"), v.toString());
    }

    @Test
    void sensorCheckContradictedByDefectRise() {
        var r = result("SENSOR_CHECK", "SENSOR_FAULT", 80, List.of("압력 단발 튐 3회", "불량률 2%"), List.of(), List.of());
        assertTrue(verifier.verify(input(List.of("INDIVIDUAL"), r, FULL_TRACE)).passed());
        facts.rates = new double[] { 0.12, 0.02 };   // 최근 50 shot 불량률이 크게 오름
        assertEquals("WEAK", verifier.verify(input(List.of("INDIVIDUAL"), r, FULL_TRACE)).status());
        facts.rates = new double[] { 0.02, 0.02 };
        assertEquals("WEAK", verifier.verify(input(List.of("P_CHART"), r, FULL_TRACE)).status(),
                "불량률 경보가 있으면 제품 영향 없음 판단과 모순");
    }

    @Test
    void defectRiseNeedsBothRatioAndGap() {
        assertTrue(VerifierAgent.defectRose(0.10, 0.02));
        assertTrue(!VerifierAgent.defectRose(0.04, 0.01), "2배 이상이지만 3%p 차이는 우연일 수 있음");
        assertTrue(!VerifierAgent.defectRose(0.30, 0.20), "5%p 이상이지만 2배 미만");
    }

    @Test
    void feedbackListsEveryFail() {
        var v = new VerifierAgent.Verification("WEAK", List.of("A", "B"), List.of());
        String f = VerifierAgent.feedback(v);
        assertTrue(f.contains("1. A") && f.contains("2. B") && f.contains("지어내지"));
    }

    @Test
    void toolNamesComeFromTraceOnly() {
        assertEquals(Set.of("getSpcSummary", "getSensorTrend", "getDefectSummary", "findSimilarNcr"),
                VerifierAgent.calledTools(FULL_TRACE));
        assertEquals(Set.of(), VerifierAgent.calledTools(List.of("  ↳ 오류: x(1)")));
    }
}
