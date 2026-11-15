package com.qualiguard.qms.agent.pipeline;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import com.qualiguard.qms.agent.AgentProperties;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.VerifierAgent;

import tools.jackson.databind.ObjectMapper;

/**
 * 역할 분리형 에이전트 파이프라인 배선 (ADR-016).
 *
 * qg.agent.pipeline.transport
 *  - kafka  (기본): 에이전트 사이 전달을 Kafka 토픽으로 (agent.incident.*)
 *  - direct       : 같은 스레드에서 바로 호출 (Kafka 없이 시험할 때)
 */
@Configuration
public class PipelineConfig {

    @Bean
    IncidentStore incidentStore(JdbcTemplate jdbc) {
        return new IncidentStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "qg.agent.pipeline.transport", havingValue = "kafka", matchIfMissing = true)
    EventBus kafkaEventBus(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, AgentEventRouter router) {
        return new KafkaEventBus(kafka, objectMapper, router);
    }

    @Bean
    @ConditionalOnProperty(name = "qg.agent.pipeline.transport", havingValue = "direct")
    EventBus directEventBus(AgentEventRouter router) {
        return new DirectEventBus(router);
    }

    /** 파이프라인 토픽 (브로커 자동 생성이 꺼져 있어 앱이 시작할 때 없으면 만든다. docker-compose kafka-init 과 같음). */
    @Bean
    @ConditionalOnProperty(name = "qg.agent.pipeline.transport", havingValue = "kafka", matchIfMissing = true)
    KafkaAdmin.NewTopics agentTopics() {
        return new KafkaAdmin.NewTopics(AgentTopics.ALL.stream()
                .map(t -> TopicBuilder.name(t).partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    @Bean
    TriageAgent triageAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus) {
        return new TriageAgent(analyzer, store, bus);
    }

    @Bean
    AnalysisAgent analysisAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, AgentEventRouter router) {
        return new AnalysisAgent(analyzer, store, bus, router);
    }

    @Bean
    VerifierStage verifierStage(VerifierAgent verifier, IncidentAnalyzer analyzer, IncidentStore store, EventBus bus,
            AgentEventRouter router) {
        return new VerifierStage(verifier, analyzer, store, bus, router);
    }

    @Bean
    ReportWriterAgent reportWriterAgent(JdbcTemplate jdbc, ObjectMapper objectMapper, IncidentStore store, EventBus bus,
            AgentEventRouter router,
            @Value("${qg.agent.writer.mode:analysis-text}") String mode,
            @Value("${qg.agent.writer.ollama-url:http://localhost:11434}") String ollamaUrl,
            @Value("${qg.agent.writer.model:qwen2.5:1.5b}") String model,
            @Value("${qg.agent.writer.timeout-seconds:120}") int timeoutSeconds) {
        var settings = new ReportWriterAgent.Settings(mode, ollamaUrl, model, timeoutSeconds);
        return new ReportWriterAgent(settings, new ReportWriterAgent.OllamaClient(ollamaUrl, model, objectMapper),
                new ReportWriterAgent.JdbcConfidentialFacts(jdbc), objectMapper, store, bus, router);
    }

    @Bean
    IncidentOrchestrator incidentOrchestrator(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus,
            ObjectMapper objectMapper, AgentProperties props, AgentEventRouter router) {
        return new IncidentOrchestrator(analyzer, store, bus, objectMapper, props.verifyMaxRetries(), router);
    }

    @Bean
    AgentPipeline agentPipeline(TriageAgent triage, IncidentStore store,
            @Value("${qg.agent.pipeline.wait-seconds:600}") long waitSeconds) {
        return new AgentPipeline(triage, store, Duration.ofSeconds(waitSeconds));
    }
}
