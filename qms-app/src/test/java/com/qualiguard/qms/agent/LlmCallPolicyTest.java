package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.qualiguard.qms.knowledge.NcrEmbeddingService;

import tools.jackson.databind.json.JsonMapper;

/** LLM 호출 보호 (로드맵 O4): 시간 제한, 일시 장애만 재시도(간격 2배), 끝내 실패하면 예외 + 기록. */
class LlmCallPolicyTest {

    private final List<Duration> slept = new ArrayList<>();

    private LlmCallPolicy policy(Duration timeout, int retries) {
        return new LlmCallPolicy(new LlmCallPolicy.Settings(timeout, retries, Duration.ofSeconds(2)), slept::add);
    }

    @Test
    void 성공하면_그대로_돌려준다() {
        assertThat(policy(Duration.ofSeconds(5), 1).call("t", () -> "ok", null)).isEqualTo("ok");
        assertThat(slept).isEmpty();
    }

    @Test
    void 서버_오류는_재시도해서_성공하면_결과를_쓴다() {
        AtomicInteger n = new AtomicInteger();
        List<String> trace = new ArrayList<>();
        String r = policy(Duration.ofSeconds(5), 1).call("analysis", () -> {
            if (n.incrementAndGet() == 1) {
                throw new ServerException(503, "UNAVAILABLE", "overloaded");
            }
            return "ok";
        }, trace);
        assertThat(r).isEqualTo("ok");
        assertThat(n).hasValue(2);
        assertThat(slept).containsExactly(Duration.ofSeconds(2));
        assertThat(trace).singleElement().asString().contains("시도 1/2", "재시도");
    }

    @Test
    void 시간을_넘기면_끊고_재시도한다_끝내_실패하면_예외() {
        AtomicInteger n = new AtomicInteger();
        List<String> trace = new ArrayList<>();
        assertThatThrownBy(() -> policy(Duration.ofMillis(100), 2).call("analysis", () -> {
            n.incrementAndGet();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }, trace)).isInstanceOf(LlmCallPolicy.LlmUnavailableException.class).hasMessageContaining("시간 초과");
        assertThat(n).hasValue(3);                                              // 처음 1회 + 재시도 2회
        assertThat(slept).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));   // 지수 백오프
        assertThat(trace).hasSize(3);
    }

    @Test
    void 키_오류는_다시_해도_같으므로_바로_실패한다() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> policy(Duration.ofSeconds(5), 3).call("analysis", () -> {
            n.incrementAndGet();
            throw new ClientException(401, "UNAUTHENTICATED", "API key not valid");
        }, null)).isInstanceOf(LlmCallPolicy.LlmUnavailableException.class);
        assertThat(n).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void 요청_한도_초과는_재시도_대상() {
        assertThat(LlmCallPolicy.retryable(new ClientException(429, "RESOURCE_EXHAUSTED", "quota"))).isTrue();
        assertThat(LlmCallPolicy.retryable(new RuntimeException(new java.net.SocketTimeoutException()))).isTrue();
        assertThat(LlmCallPolicy.retryable(new IllegalStateException("JSON 형식 오류"))).isFalse();
    }

    @Test
    void 장애_주입_분석_에이전트가_LLM_장애를_예외로_알린다() {
        // 가짜 ChatClient: 늘 503 을 던진다 → 재시도 1회 후 LlmUnavailableException
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        AtomicInteger calls = new AtomicInteger();
        when(client.prompt().system(anyString()).user(anyString()).tools(any()).call().chatResponse())
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    throw new ServerException(503, "UNAVAILABLE", "injected");
                });
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        IncidentAnalyzer analyzer = new IncidentAnalyzer(mock(JdbcTemplate.class), JsonMapper.builder().build(), null,
                null, provider, "test-model", mock(NcrEmbeddingService.class), policy(Duration.ofSeconds(5), 1));

        List<String> trace = new ArrayList<>();
        assertThatThrownBy(() -> analyzer.analyzeOnce("prompt", null, System.nanoTime(), trace))
                .isInstanceOf(LlmCallPolicy.LlmUnavailableException.class).hasMessageContaining("503");
        assertThat(calls).hasValue(2);
        assertThat(trace).anyMatch(l -> l.startsWith("llmCall(analysis, 시도 2/2) 실패"));
    }
}
