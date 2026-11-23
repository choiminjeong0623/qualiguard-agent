package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qualiguard.qms.agent.IncidentAnalyzer.Job;

import tools.jackson.databind.json.JsonMapper;

/** 로드맵 A1: "도구 미리 조회 + LLM 1회" 비교 모드. */
class PrefetchModeTest {

    private static final String ANSWER = """
            {"decision":"OBSERVE","causeCode":"UNKNOWN","confidence":40,"title":"t","phenomenon":"p",
             "suspectedCause":"s","evidence":["a 1"],"similarCases":[],"recommendedAction":"r","watchItems":["w"]}
            """;

    private static Job job() {
        List<Map<String, Object>> alarms = List.of(
                Map.of("rule_code", "XBAR", "sensor_key", "barrelTemp", "message", "실린더 온도 평균 하락"),
                Map.of("rule_code", "S_CHART", "sensor_key", "barrelTemp", "message", "실린더 온도 산포 증가"),
                Map.of("rule_code", "XBAR", "sensor_key", "motorCurrent", "message", "모터 전류 상승"));
        return new Job("ALARM", "IMM-01", "a1", "t", alarms, List.of("a1"), true, Map.of(),
                "2026-11-23T10:00:00+09:00", 500L, "t", null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefetch_는_도구_결과를_프롬프트에_넣고_도구_없이_한_번_부른다() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenAnswer(inv -> {
            sentPrompt.set(inv.getArgument(0));
            return spec;
        });
        when(spec.call()).thenReturn(call);
        when(call.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER)))));
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);

        IncidentAnalyzer analyzer = new IncidentAnalyzer(mock(JdbcTemplate.class), JsonMapper.builder().build(), null,
                null, provider, "test-model", null, new LlmCallPolicy(LlmCallPolicy.Settings.defaults()), "prefetch");
        List<String> trace = new ArrayList<>();
        AnalysisResult r = analyzer.analyzeOnce(job(), "사건 a1", System.nanoTime(), trace, new ToolObservations());

        assertThat(r.decision()).isEqualTo("OBSERVE");
        verify(spec, never()).tools(any());
        assertThat(sentPrompt.get()).startsWith("사건 a1").contains(IncidentAnalyzer.PREFETCH_NOTE)
                .contains("getSpcSummary(lastShots=50)")
                .contains("getSensorTrend(barrelTemp, lastShots=300, bucketSize=20)")
                .contains("getSensorTrend(motorCurrent, lastShots=60, bucketSize=5)")
                .contains("findSimilarNcr(sensorKeys=barrelTemp,motorCurrent)")
                .contains("getMaintenanceHistory");
        // 도구 기록: SPC 1 + 추이 2센서×2 + 불량 1 + 유사 사례 1 + 정비 1 = 8회, 그 뒤 토큰 줄
        assertThat(trace.stream().filter(t -> !t.startsWith("  ↳"))).hasSize(8);
    }

    @Test
    void 경보_센서는_중복_없이_최대_3개() {
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (String k : List.of("a", "b", "a", "c", "d")) {
            alarms.add(Map.of("sensor_key", k));
        }
        Job j = new Job("ALARM", "IMM-01", "x", "t", alarms, List.of(), true, Map.of(), "t", 1L, "t", null, null, null);
        assertThat(IncidentAnalyzer.alarmSensors(j)).containsExactly("a", "b", "c");
    }

    @Test
    void 알_수_없는_분석_방식은_tools_로_둔다() {
        assertThat(IncidentAnalyzer.normalizeMode(" PREFETCH ")).isEqualTo("prefetch");
        assertThat(IncidentAnalyzer.normalizeMode("magic")).isEqualTo("tools");
        assertThat(IncidentAnalyzer.normalizeMode(null)).isEqualTo("tools");
    }
}
