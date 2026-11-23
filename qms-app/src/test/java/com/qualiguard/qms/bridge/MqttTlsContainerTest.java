package com.qualiguard.qms.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * MQTT TLS (로드맵 Q5): scripts/mqtt_tls.sh 로 만든 인증서로 Mosquitto 8883 을 띄우고,
 * 브리지가 쓰는 MqttTls 소켓으로 접속·수신되는지, 개발 CA 를 모르면 거부되는지 확인한다.
 * 실행: ./gradlew containerTest (Docker·openssl 필요)
 */
@Tag("container")
class MqttTlsContainerTest {

    private static final Path CERTS = Path.of("build/mqtt-tls-test/certs");
    private static GenericContainer<?> broker;

    @BeforeAll
    static void 인증서를_만들고_브로커를_띄운다() throws Exception {
        Path conf = Path.of("build/mqtt-tls-test/tls.conf");
        Files.createDirectories(CERTS);
        // 스크립트는 저장소 루트로 cd 하므로 경로는 루트 기준으로 넘긴다
        ProcessBuilder pb = new ProcessBuilder("bash", "scripts/mqtt_tls.sh", "--renew")
                .directory(Path.of("..").toFile()).redirectErrorStream(true);
        pb.environment().put("CERT_DIR", "qms-app/" + CERTS);
        pb.environment().put("TLS_CONF", "qms-app/" + conf);
        Process run = pb.start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(run.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(out).isZero();

        Path brokerConf = Path.of("build/mqtt-tls-test/mosquitto.conf");
        Files.writeString(brokerConf, "allow_anonymous true\n" + Files.readString(conf));
        broker = new GenericContainer<>("eclipse-mosquitto:2")
                .withCopyFileToContainer(MountableFile.forHostPath(brokerConf), "/mosquitto/config/mosquitto.conf")
                .withCopyFileToContainer(MountableFile.forHostPath(CERTS, 0755), "/mosquitto/certs")
                .withExposedPorts(8883)
                .waitingFor(Wait.forLogMessage(".*running.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        broker.start();
    }

    private static String url() {
        return "ssl://localhost:" + broker.getMappedPort(8883);
    }

    @Test
    void 개발_CA_를_믿으면_TLS_로_접속하고_메시지를_받는다() throws Exception {
        MqttConnectionOptions o = new MqttConnectionOptions();
        o.setSocketFactory(MqttTls.socketFactory(CERTS.resolve("ca.crt").toString()));
        o.setHttpsHostnameVerificationEnabled(true);
        MqttClient sub = new MqttClient(url(), "tls-sub", null);
        MqttClient pub = new MqttClient(url(), "tls-pub", null);
        try {
            sub.connect(o);
            CompletableFuture<String> got = new CompletableFuture<>();
            // subscribe(String, int, listener) 는 paho v5 에서 자기 자신을 다시 부르는 버그가 있어 배열 형태를 쓴다
            sub.subscribe(new MqttSubscription[] { new MqttSubscription("factory/LINE1/IMM-01/telemetry", 1) },
                    new IMqttMessageListener[] {
                        (topic, msg) -> got.complete(new String(msg.getPayload(), StandardCharsets.UTF_8)) });
            pub.connect(o);
            pub.publish("factory/LINE1/IMM-01/telemetry", "tls-ok".getBytes(StandardCharsets.UTF_8), 1, false);
            assertThat(got.get(10, TimeUnit.SECONDS)).isEqualTo("tls-ok");
        } finally {
            if (pub.isConnected()) {
                pub.disconnect();
            }
            if (sub.isConnected()) {
                sub.disconnect();
            }
            pub.close();
            sub.close();
        }
    }

    @Test
    void 개발_CA_를_모르면_인증서_검증에서_거부된다() throws Exception {
        MqttClient c = new MqttClient(url(), "tls-noca", null);
        try {
            assertThatThrownBy(() -> c.connect(new MqttConnectionOptions()))   // JVM 기본 신뢰 목록만 사용
                    .isInstanceOf(MqttException.class);
        } finally {
            c.close();
        }
    }
}
