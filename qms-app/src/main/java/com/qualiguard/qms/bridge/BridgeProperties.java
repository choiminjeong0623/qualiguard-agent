package com.qualiguard.qms.bridge;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 qg.mqtt / qg.bridge 설정. */
public final class BridgeProperties {

    private BridgeProperties() {
    }

    @ConfigurationProperties(prefix = "qg.mqtt")
    public record Mqtt(String url, String clientId, List<String> topics, int qos, long sessionExpirySeconds,
            String username, String password, String caFile) {

        /** TLS 접속인지 (ssl:// 로 시작, 로드맵 Q5). */
        public boolean isTls() {
            return url != null && url.startsWith("ssl://");
        }

        /** 브로커 계정이 설정됐는지 (N-07). 비어 있으면 익명으로 접속하고, 인증을 켠 브로커는 거부한다. */
        public boolean hasCredentials() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {   // 비밀번호가 로그에 찍히지 않게
            return "Mqtt[url=" + url + ", clientId=" + clientId + ", topics=" + topics + ", qos=" + qos
                    + ", username=" + username + ", password=" + (password == null || password.isBlank() ? "(없음)" : "****")
                    + ", caFile=" + caFile + "]";
        }
    }

    @ConfigurationProperties(prefix = "qg.bridge")
    public record Bridge(int dedupCacheSize, long kafkaSendTimeoutSeconds) {
    }
}
