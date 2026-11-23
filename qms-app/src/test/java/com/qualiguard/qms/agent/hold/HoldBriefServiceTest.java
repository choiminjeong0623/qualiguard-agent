package com.qualiguard.qms.agent.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 판단 보류 요약: LLM 응답 읽기와 LLM 없이 기록에서 뽑는 요약. */
class HoldBriefServiceTest {

    @Test
    void LLM_응답의_JSON_을_읽는다_코드블록과_앞뒤_글도_허용() {
        var text = "요약입니다\n```json\n{\"summary\":[\"a\",\"b\"],\"checks\":[\"c\"]}\n```";
        var b = HoldBriefService.parse(text, "m");
        assertThat(b.summary()).containsExactly("a", "b");
        assertThat(b.checks()).containsExactly("c");
        assertThat(b.source()).isEqualTo("LLM");
    }

    @Test
    void 줄은_최대_3개까지만_남긴다() {
        var b = HoldBriefService.parse("{\"summary\":[\"1\",\"2\",\"3\",\"4\"],\"checks\":[\"x\",\" \",\"y\"]}", "m");
        assertThat(b.summary()).containsExactly("1", "2", "3");
        assertThat(b.checks()).containsExactly("x", "y");
    }

    @Test
    void 읽지_못하거나_summary_가_비면_null() {
        assertThat(HoldBriefService.parse("모르겠습니다", "m")).isNull();
        assertThat(HoldBriefService.parse("{\"summary\":[]}", "m")).isNull();
        assertThat(HoldBriefService.parse("{깨진 json", "m")).isNull();
        assertThat(HoldBriefService.parse(null, "m")).isNull();
    }

    @Test
    void LLM_없이도_판단_흐름과_보류_사유로_요약한다() {
        var alarm = step("ALARM", 325, "OBSERVE", "UNKNOWN", "추세가 약함", List.of("온도 추가 하강"), null);
        var recheck = step("RECHECK", 445, "OBSERVE", "UNKNOWN", "여전히 애매함", List.of("모터 전류", "불량 발생"),
                "새 경보 없는 재확인 3회(상한 3) 뒤에도 원인을 정하지 못했습니다.");
        var b = HoldBriefService.ruleBrief(List.of(alarm, recheck));
        assertThat(b.source()).isEqualTo("RULE");
        assertThat(b.summary().get(0)).isEqualTo("판단 흐름: ALARM shot 325 OBSERVE(UNKNOWN) → RECHECK shot 445 OBSERVE(UNKNOWN)");
        assertThat(b.summary().get(1)).isEqualTo("마지막 분석: 여전히 애매함");
        assertThat(b.summary().get(2)).startsWith("보류 사유: 새 경보 없는 재확인 3회");
        assertThat(b.checks()).containsExactly("모터 전류", "불량 발생");
    }

    private static Map<String, Object> step(String trigger, int shot, String decision, String cause, String reasoning,
            List<String> watch, String holdDetail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", trigger);
        m.put("shotNo", shot);
        m.put("decision", decision);
        m.put("causeCode", cause);
        m.put("reasoningSummary", reasoning);
        m.put("watchItems", watch);
        if (holdDetail != null) {
            m.put("holdReason", "RECHECK_LIMIT");
            m.put("holdDetail", holdDetail);
        }
        return m;
    }
}
