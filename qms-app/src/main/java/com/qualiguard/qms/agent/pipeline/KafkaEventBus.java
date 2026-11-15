package com.qualiguard.qms.agent.pipeline;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 이벤트를 Kafka 로 주고받는다 (ADR-016). 키 = 사건 ID → 한 사건의 이벤트는 같은 파티션에서 순서대로.
 * 에이전트마다 소비자 그룹이 따로라, 나중에 에이전트를 다른 프로세스로 떼어 내도 토픽 계약은 그대로다.
 */
public class KafkaEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final AgentEventRouter router;

    public KafkaEventBus(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, AgentEventRouter router) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @Override
    public void publish(String topic, AgentEvent event) {
        try {
            kafka.send(topic, event.incidentId(), objectMapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Kafka 전송 실패 (" + topic + "): " + e, e);
        }
    }

    // 에이전트마다 소비자 그룹 하나. concurrency 1: 같은 에이전트는 한 번에 사건 하나 (LLM·메모리 상한)
    // earliest: 그룹이 처음 생길 때 파티션 배정 전에 보낸 이벤트를 놓치지 않게. 재시작 전 사건의 이벤트는 모르는 사건이라 무시된다

    @KafkaListener(id = "qg-agent-orchestrator", groupId = "qg-agent-orchestrator",
            topics = { AgentTopics.TRIAGED, AgentTopics.ANALYSIS_COMPLETED, AgentTopics.VERIFICATION_COMPLETED,
                    AgentTopics.REPORT_COMPLETED },
            properties = { "auto.offset.reset=earliest", "isolation.level=read_uncommitted" })
    public void orchestrator(ConsumerRecord<String, String> r) {
        dispatch(r);
    }

    @KafkaListener(id = "qg-agent-analysis", groupId = "qg-agent-analysis", topics = AgentTopics.ANALYSIS_REQUESTED,
            properties = { "auto.offset.reset=earliest", "isolation.level=read_uncommitted", "max.poll.interval.ms=600000",
                    "max.poll.records=1" })
    public void analysis(ConsumerRecord<String, String> r) {
        dispatch(r);
    }

    @KafkaListener(id = "qg-agent-verifier", groupId = "qg-agent-verifier", topics = AgentTopics.VERIFICATION_REQUESTED,
            properties = { "auto.offset.reset=earliest", "isolation.level=read_uncommitted" })
    public void verifier(ConsumerRecord<String, String> r) {
        dispatch(r);
    }

    @KafkaListener(id = "qg-agent-writer", groupId = "qg-agent-writer", topics = AgentTopics.REPORT_REQUESTED,
            properties = { "auto.offset.reset=earliest", "isolation.level=read_uncommitted", "max.poll.interval.ms=600000",
                    "max.poll.records=1" })
    public void writer(ConsumerRecord<String, String> r) {
        dispatch(r);
    }

    private void dispatch(ConsumerRecord<String, String> r) {
        AgentEvent e;
        try {
            e = objectMapper.readValue(r.value(), AgentEvent.class);
        } catch (RuntimeException ex) {
            log.warn("[파이프라인] 읽을 수 없는 이벤트 버림 ({}): {}", r.topic(), ex.toString());
            return;
        }
        try {
            router.route(r.topic(), e);
        } catch (RuntimeException ex) {
            // 에이전트 안에서 처리하지 못한 오류: 다시 읽어도 같은 결과라 재시도하지 않는다 (오케스트레이터가 사건을 실패 처리)
            log.error("[파이프라인] {} 처리 실패 (사건 {}): {}", r.topic(), e.incidentId(), ex.toString(), ex);
        }
    }
}
