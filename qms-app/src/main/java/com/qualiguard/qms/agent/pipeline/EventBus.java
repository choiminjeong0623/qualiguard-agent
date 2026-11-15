package com.qualiguard.qms.agent.pipeline;

/** 에이전트 이벤트 전송. 운영은 Kafka, 단위 시험은 같은 스레드에서 바로 전달. */
public interface EventBus {

    void publish(String topic, AgentEvent event);
}
