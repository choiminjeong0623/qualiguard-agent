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
        assertThat(AgentPrompts.VERSION).matches("analysis-v1@[0-9a-f]{8}");
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
