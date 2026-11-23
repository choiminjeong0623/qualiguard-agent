package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/**
 * 분석 LLM 고르기: 기본은 Gemini, 프로파일 closed(폐쇄망 분석 모드)는 Ollama 하나만 만들어지는가.
 * 실제 application.yml / application-closed.yml 을 읽어 확인한다 (Ollama 서버에 연결하지는 않음).
 */
class ChatModelSelectionTest {

    static ApplicationContextRunner runner(boolean closed) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    var sources = ctx.getEnvironment().getPropertySources();
                    try {
                        if (closed) {
                            new YamlPropertySourceLoader()
                                    .load("closed", new ClassPathResource("application-closed.yml"))
                                    .forEach(sources::addLast);
                        }
                        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                                .forEach(sources::addLast);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("spring.ai.google.genai.api-key=test-key")
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, OllamaApiAutoConfiguration.class,
                        OllamaChatAutoConfiguration.class, OllamaEmbeddingAutoConfiguration.class,
                        GoogleGenAiChatAutoConfiguration.class, ChatClientAutoConfiguration.class));
    }

    @Test
    void 기본은_Gemini_하나() {
        runner(false).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ChatModel.class).hasSingleBean(GoogleGenAiChatModel.class);
            assertThat(ctx).doesNotHaveBean(OllamaChatModel.class).doesNotHaveBean(EmbeddingModel.class);
            assertThat(ctx).hasSingleBean(ChatClient.Builder.class);
        });
    }

    @Test
    void 폐쇄망_프로파일은_Ollama_하나_모델_이름은_기록용_라벨과_같다() {
        runner(true).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ChatModel.class).hasSingleBean(OllamaChatModel.class);
            assertThat(ctx).doesNotHaveBean(GoogleGenAiChatModel.class);
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.ai.ollama.chat.model")).isEqualTo("qwen2.5:7b");
            assertThat(env.getProperty("qg.agent.model-label")).isEqualTo("ollama/qwen2.5:7b");
            assertThat(env.getProperty("qg.knowledge.vector-enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
            assertThat(env.getProperty("qg.agent.writer.mode")).isEqualTo("local");
        });
    }
}
