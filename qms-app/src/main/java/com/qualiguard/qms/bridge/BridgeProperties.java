package com.qualiguard.qms.bridge;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 qg.mqtt / qg.bridge 설정. */
public final class BridgeProperties {

    private BridgeProperties() {
    }

    @ConfigurationProperties(prefix = "qg.mqtt")
    public record Mqtt(String url, String clientId, List<String> topics, int qos, long sessionExpirySeconds) {
    }

    @ConfigurationProperties(prefix = "qg.bridge")
    public record Bridge(int dedupCacheSize, long kafkaSendTimeoutSeconds) {
    }
}
