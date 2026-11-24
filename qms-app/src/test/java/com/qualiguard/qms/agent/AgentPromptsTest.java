package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 프롬프트 버전 관리 (로드맵 O1): 파일에서 읽고, 본문이 바뀌면 버전도 바뀐다. */
class AgentPromptsTest {

    @Test
    void 프롬프트_파일을_읽는다() {
        assertThat(AgentPrompts.SYSTEM).startsWith("당신은 사출성형 라인의 품질 엔지니어를 돕는 원인 분석 에이전트입니다.");
        assertThat(AgentPrompts.SYSTEM).contains("[출력 형식]", "[검토자 메모]");
        assertThat(AgentPrompts.REPAIR).contains("JSON 객체 하나로만");
    }

    @Test
    void 버전은_파일_이름과_해시_8자리() {
        assertThat(AgentPrompts.VERSION).matches("analysis-v2@[0-9a-f]{8}");
        assertThat(AgentPrompts.of("analysis-v1").version()).matches("analysis-v1@[0-9a-f]{8}");
    }

    @Test
    void v1_도_그대로_남아_비교할_수_있다() {
        AgentPrompts.Set v1 = AgentPrompts.of("analysis-v1");
        assertThat(v1.system()).doesNotContain("(v2)");
        assertThat(v1.version()).isNotEqualTo(AgentPrompts.VERSION);
        assertThat(v1.repair()).isEqualTo(AgentPrompts.REPAIR);
    }

    /** E6 원칙: 개발셋 오답으로 고치되, 특정 고장 이름을 힌트로 넣지 않는다 (v1 에 있던 원인 코드 목록·SOP 예시는 그대로). */
    @Test
    void v2_는_세_원칙을_담고_새_고장_힌트는_넣지_않는다() {
        String v2 = AgentPrompts.SYSTEM;
        assertThat(v2).contains("[원인을 고르는 순서] (v2)", "[원인을 확정하기 전 반대 근거 확인] (v2)", "초안으로 넘어가는 기준 (v2)");
        String v1 = AgentPrompts.of("analysis-v1").system();
        for (String fault : new String[] {"히터", "냉각", "체크 링", "드리프트", "HEATER", "COOLING", "CHECK_RING"}) {
            assertThat(count(v2, fault)).as(fault).isEqualTo(count(v1, fault));
        }
    }

    private static int count(String text, String word) {
        return text.split(java.util.regex.Pattern.quote(word), -1).length - 1;
    }

    @Test
    void 한_글자만_바뀌어도_해시가_달라진다() {
        String h1 = AgentPrompts.shortHash(AgentPrompts.SYSTEM);
        assertThat(AgentPrompts.shortHash(AgentPrompts.SYSTEM)).isEqualTo(h1);   // 같은 본문이면 같은 해시
        assertThat(AgentPrompts.shortHash(AgentPrompts.SYSTEM + " ")).isNotEqualTo(h1);
    }

    @Test
    void 없는_파일이면_시작할_때_바로_실패한다() {
        assertThatThrownBy(() -> AgentPrompts.load("no-such-prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-prompt");
    }
}
