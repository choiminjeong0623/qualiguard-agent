package com.qualiguard.qms.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ AgentProperties.class, LlmCallPolicy.Settings.class })
public class AgentConfig {

    /** LLM 호출 시간 제한·재시도 (로드맵 O4, qg.agent.llm). */
    @Bean
    LlmCallPolicy llmCallPolicy(LlmCallPolicy.Settings settings) {
        return new LlmCallPolicy(settings);
    }
}
