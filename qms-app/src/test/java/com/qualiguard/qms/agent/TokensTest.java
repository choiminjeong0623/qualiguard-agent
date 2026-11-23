package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 재분석·형식 수정까지 한 사건의 LLM 호출 토큰을 도구 기록에서 더한다. */
class TokensTest {

    @Test
    void 도구_기록의_토큰_줄을_모두_더한다() {
        var first = new IncidentAnalyzer.Tokens(1000, 200, 300).traceLine();
        var repair = new IncidentAnalyzer.Tokens(50, 20, null).traceLine();
        var t = IncidentAnalyzer.Tokens.fromTrace(List.of("getSpcSummary(IMM-01) @1ms", first, repair, "  ↳ 오류: x"));
        assertThat(t).isEqualTo(new IncidentAnalyzer.Tokens(1050, 220, 300));
    }

    @Test
    void 첫_줄에_값이_없어도_멈추지_않는다() {
        // 생각 토큰이 없는 호출(-)이 먼저 오면 합계가 아직 null 이다 (예전엔 여기서 NPE)
        var noThought = new IncidentAnalyzer.Tokens(10, 5, null).traceLine();
        var withThought = new IncidentAnalyzer.Tokens(20, 5, 7).traceLine();
        assertThat(IncidentAnalyzer.Tokens.fromTrace(List.of(noThought)))
                .isEqualTo(new IncidentAnalyzer.Tokens(10, 5, null));
        assertThat(IncidentAnalyzer.Tokens.fromTrace(List.of(noThought, withThought)))
                .isEqualTo(new IncidentAnalyzer.Tokens(30, 10, 7));
    }

    @Test
    void 토큰_줄이_없으면_모두_null() {
        assertThat(IncidentAnalyzer.Tokens.fromTrace(List.of("getSpcSummary(IMM-01) @1ms")))
                .isEqualTo(new IncidentAnalyzer.Tokens(null, null, null));
    }

    @Test
    void 토큰_줄은_도구_호출로_세지_않는다() {
        var line = new IncidentAnalyzer.Tokens(1, 2, 3).traceLine();
        assertThat(VerifierAgent.calledTools(List.of(line, "getSpcSummary(IMM-01) @1ms")))
                .containsExactly("getSpcSummary");
    }
}
