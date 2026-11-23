package com.qualiguard.qms.agent.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import com.qualiguard.qms.agent.AgentProperties;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.VerifierAgent;
import com.qualiguard.qms.agent.ml.CauseForest;
import com.qualiguard.qms.agent.ml.MlTriage;

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
    IncidentStore incidentStore(JdbcTemplate jdbc, ObjectProvider<MeterRegistry> registry, ObjectMapper objectMapper) {
        // 단계별 시간·결과 지표 (로드맵 O2). 지표 저장소가 없으면(일부 시험) 기록하지 않는다
        // objectMapper: 단계마다 사건 기록(checkpoint_json)을 남겨 재시작 뒤 이어 간다 (로드맵 A2)
        return new IncidentStore(jdbc, new AgentMetrics(registry.getIfAvailable()), objectMapper);
    }

    /**
     * 재시작 복구 (로드맵 A2): 앱이 켜지면 끝나지 않은 사건을 DB 기록에서 되살려 멈춘 단계부터 이어 간다.
     * 이어 가는 일(LLM 호출 포함)이 시작을 막지 않도록 별도 스레드에서 한다.
     * qg.agent.pipeline.recover=false 면 하지 않는다 (그 사건들은 진행 중으로 남는다).
     */
    @Bean
    ApplicationRunner incidentRecovery(IncidentStore store, IncidentOrchestrator orchestrator,
            @Value("${qg.agent.pipeline.recover:true}") boolean enabled,
            @Value("${qg.agent.pipeline.recover-max-age-minutes:1440}") long maxAgeMinutes) {
        return args -> {
            if (!enabled) {
                return;
            }
            Thread t = new Thread(() -> {
                List<Incident> restored = store.restore();
                if (!restored.isEmpty()) {
                    int resumed = orchestrator.resume(restored, maxAgeMinutes);
                    LoggerFactory.getLogger(PipelineConfig.class)
                            .info("[재시작 복구] 끝나지 않은 사건 {}건 중 {}건을 이어 감", restored.size(), resumed);
                }
            }, "qg-incident-recovery");
            t.setDaemon(true);
            t.start();
        };
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
    AnalysisAgent analysisAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus, AgentEventRouter router,
            MlTriage mlTriage) {
        return new AnalysisAgent(analyzer, store, bus, router, mlTriage);
    }

    /**
     * ML 먼저, 애매하면 LLM (실험 기능). qg.agent.ml-triage.mode
     *  - off (기본): 쓰지 않음
     *  - shadow    : ML 판단을 분석 기록에 남기기만 하고 LLM 은 그대로
     *  - skip      : 학습한 고장 원인을 threshold 이상으로 고르면 LLM 을 부르지 않음
     *                (낯선 경보는 LLM 으로 novelty-check, 건너뛸 사건 중 audit-rate 비율은 LLM 이 다시 봄)
     * 모델은 scripts/ml_cascade.sh 가 만든 JSON (기본: 앱에 들어 있는 시뮬레이터 학습 모델).
     */
    @Bean
    MlTriage mlTriage(JdbcTemplate jdbc, ObjectMapper objectMapper, ResourceLoader resources,
            @Value("${qg.agent.ml-triage.mode:off}") String mode,
            @Value("${qg.agent.ml-triage.threshold:0.9}") double threshold,
            @Value("${qg.agent.ml-triage.model:classpath:ml/cause-forest.json}") String model,
            @Value("${qg.agent.ml-triage.novelty-check:true}") boolean noveltyCheck,
            @Value("${qg.agent.ml-triage.audit-rate:0.1}") double auditRate) throws IOException {
        String v = mode.strip().toUpperCase(Locale.ROOT);
        // YAML 에 따옴표 없이 off 라고 쓰면 false 로 읽힌다
        MlTriage.Mode m = v.isEmpty() || "FALSE".equals(v) ? MlTriage.Mode.OFF : MlTriage.Mode.valueOf(v);
        if (m == MlTriage.Mode.OFF) {
            return MlTriage.off();
        }
        try (InputStream in = resources.getResource(model).getInputStream()) {
            CauseForest forest = CauseForest.load(in, objectMapper);
            LoggerFactory.getLogger(PipelineConfig.class).info(
                    "[ML 1차 분류] {} 모드, 기준 확률 {}, 낯선 경보 검사 {}, 표본 점검 {}, 모델 {} (나무 {}개, {})",
                    m, threshold, noveltyCheck, auditRate, model, forest.treeCount(), forest.createdAt());
            return new MlTriage(jdbc, forest, m, threshold, noveltyCheck, auditRate);
        }
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
            ObjectMapper objectMapper, AgentProperties props, AgentEventRouter router,
            @Value("${qg.agent.action.enabled:false}") boolean actionStage) {
        return new IncidentOrchestrator(analyzer, store, bus, objectMapper, props.verifyMaxRetries(), router,
                actionStage);
    }

    @Bean
    AgentPipeline agentPipeline(TriageAgent triage, IncidentStore store,
            @Value("${qg.agent.pipeline.wait-seconds:600}") long waitSeconds) {
        return new AgentPipeline(triage, store, Duration.ofSeconds(waitSeconds));
    }
}
