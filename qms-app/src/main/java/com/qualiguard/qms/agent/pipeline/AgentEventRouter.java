package com.qualiguard.qms.agent.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/** 토픽 → 그 토픽을 받는 에이전트. 각 에이전트가 만들어질 때 자기가 받을 토픽을 등록한다. */
@Component
public class AgentEventRouter {

    private final Map<String, Consumer<AgentEvent>> handlers = new ConcurrentHashMap<>();

    public void register(String topic, Consumer<AgentEvent> handler) {
        if (handlers.putIfAbsent(topic, handler) != null) {
            throw new IllegalStateException("이미 등록된 토픽: " + topic);
        }
    }

    public void route(String topic, AgentEvent event) {
        Consumer<AgentEvent> h = handlers.get(topic);
        if (h == null) {
            throw new IllegalStateException("받을 에이전트가 없는 토픽: " + topic);
        }
        h.accept(event);
    }
}
