package com.qualiguard.qms.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 최근 처리한 messageId 를 기억해 MQTT QoS 1 의 중복 수신을 거른다.
 *
 * 메모리에만 두므로 브리지를 재시작하면 기록이 사라진다. 그래서 재시작 직후 중복이 Kafka에
 * 들어갈 수 있고, 최종 방어선은 하류(소비자/DB)의 messageId 기준 멱등 처리다.
 */
@Component
public class MessageIdDeduplicator {

    private final Map<String, Boolean> recent;

    public MessageIdDeduplicator(BridgeProperties.Bridge props) {
        int max = props.dedupCacheSize();
        this.recent = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > max;
            }
        };
    }

    public synchronized boolean alreadyProcessed(String messageId) {
        return recent.containsKey(messageId);
    }

    /** Kafka 기록이 성공한 뒤에만 호출한다 (실패한 메시지가 재전송될 때 중복으로 버려지지 않도록). */
    public synchronized void markProcessed(String messageId) {
        recent.put(messageId, Boolean.TRUE);
    }
}
