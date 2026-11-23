package com.qualiguard.qms.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ AgentProperties.class, LlmCallPolicy.Settings.class,
        TokenBudget.Settings.class })
public class AgentConfig {

    /** LLM 호출 시간 제한·재시도 (로드맵 O4, qg.agent.llm). */
    @Bean
    LlmCallPolicy llmCallPolicy(LlmCallPolicy.Settings settings) {
        return new LlmCallPolicy(settings);
    }

    /** 하루 토큰 예산 (로드맵 O5, qg.agent.budget). 넘으면 자동 분석만 멈춘다. */
    @Bean
    TokenBudget tokenBudget(JdbcTemplate jdbc, TokenBudget.Settings settings) {
        return new TokenBudget(jdbc, settings);
    }
}
