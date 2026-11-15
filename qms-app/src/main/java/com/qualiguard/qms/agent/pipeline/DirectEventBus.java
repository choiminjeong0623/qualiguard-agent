package com.qualiguard.qms.agent.pipeline;

/**
 * Kafka 없이 같은 스레드에서 바로 다음 에이전트를 부른다 (qg.agent.pipeline.transport=direct, 단위 시험).
 * 순서·상태 기계는 Kafka 전송과 똑같다.
 */
public class DirectEventBus implements EventBus {

    private final AgentEventRouter router;

    public DirectEventBus(AgentEventRouter router) {
        this.router = router;
    }

    @Override
    public void publish(String topic, AgentEvent event) {
        router.route(topic, event);
    }
}
