package com.qualiguard.qms.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Mosquitto 구독자 (Eclipse Paho MQTT v5 클라이언트를 직접 사용).
 *
 * 처음에는 Spring Integration 의 MQTT 어댑터를 썼지만, 지속 세션(cleanStart=false)으로 재접속할 때
 * 브로커가 보관하던 메시지가 밀려 있으면, 어댑터가 '연결 완료 콜백 안에서 구독 완료를 동기로 기다리는' 구조라
 * 콜백 스레드가 막혀 구독 응답을 처리하지 못하고 32초 뒤 타임아웃이 났다(교착 상태).
 * 이 클래스는 연결 완료 콜백에서 구독을 비동기로 요청만 하고 바로 반환해 그 문제를 피한다.
 *
 * 전달 보장: QoS 1 + 지속 세션 + 수동 ack.
 *  - 브리지가 꺼져 있는 동안의 메시지는 브로커가 세션에 보관했다가 재접속 시 전달한다.
 *  - Kafka 기록에 성공한 메시지만 ack 한다. ack 하지 않은 메시지는 다음 재접속 때 다시 온다.
 */
@Component
public class MqttTelemetrySubscriber implements SmartLifecycle, MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetrySubscriber.class);
    private static final long RETRY_SECONDS = 5;

    private final BridgeProperties.Mqtt props;
    private final TelemetryBridgeHandler handler;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connect-retry");
        t.setDaemon(true);
        return t;
    });

    private volatile MqttAsyncClient client;
    private volatile boolean running;

    public MqttTelemetrySubscriber(BridgeProperties.Mqtt props, TelemetryBridgeHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    // ── 시작/종료 (Spring 이 애플리케이션 시작·종료 시 호출) ──────────────────────

    @Override
    public void start() {
        try {
            client = new MqttAsyncClient(props.url(), props.clientId(), new MemoryPersistence());
            client.setManualAcks(true);
            client.setCallback(this);
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 클라이언트 생성 실패: " + props.url(), e);
        }
        running = true;
        connect();
    }

    private void connect() {
        if (!running) {
            return;
        }
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setCleanStart(false);                              // 지속 세션
        options.setSessionExpiryInterval(props.sessionExpirySeconds());
        options.setAutomaticReconnect(true);                       // 연결이 끊기면 자동 재접속
        options.setReceiveMaximum(100);                            // ack 안 된 메시지를 최대 100건까지만 받음(과부하 방지)
        if (props.isTls() && props.caFile() != null && !props.caFile().isBlank()) {
            // TLS (로드맵 Q5): 개발용 자체 CA 만 믿는다. 호스트 이름 검증은 켜 둔다 (인증서 SAN = localhost 등)
            options.setSocketFactory(MqttTls.socketFactory(props.caFile()));
            options.setHttpsHostnameVerificationEnabled(true);
        } else if (!props.isTls()) {
            log.info("MQTT 평문 접속 ({}). TLS 는 scripts/mqtt_tls.sh 와 QG_MQTT_URL=ssl://localhost:8883", props.url());
        }
        if (props.hasCredentials()) {                              // 장비 인증 (N-07): 브리지 계정으로 접속
            options.setUserName(props.username());
            options.setPassword(props.password().getBytes(StandardCharsets.UTF_8));
        } else {
            log.warn("MQTT 계정이 설정되지 않아 익명으로 접속합니다. 인증을 켠 브로커는 거부합니다 (.env 의 MQTT_BRIDGE_PASSWORD)");
        }
        try {
            client.connect(options, null, new MqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // 구독은 connectComplete 에서 처리
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    log.warn("MQTT 접속 실패({}) — {}초 후 재시도: {}", props.url(), RETRY_SECONDS, e.toString());
                    scheduleRetry();
                }
            });
        } catch (MqttException e) {
            log.warn("MQTT 접속 요청 실패 — {}초 후 재시도: {}", RETRY_SECONDS, e.toString());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (running) {
            retryExecutor.schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        running = false;
        retryExecutor.shutdownNow();
        MqttAsyncClient c = client;
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.disconnect().waitForCompletion(5_000);
            }
            c.close();
        } catch (MqttException e) {
            log.warn("MQTT 종료 중 오류: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── Paho 콜백 ─────────────────────────────────────────────────────────

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT {}: {} (clientId={})", reconnect ? "재접속" : "접속", serverURI, props.clientId());
        String[] topics = props.topics().toArray(String[]::new);
        int[] qos = new int[topics.length];
        Arrays.fill(qos, props.qos());
        try {
            // 중요: 여기서 구독 완료를 기다리지 않는다 (콜백 스레드를 막으면 교착 상태)
            IMqttToken token = client.subscribe(topics, qos);
            token.setActionCallback(new MqttActionListener() {
                @Override
                public void onSuccess(IMqttToken t) {
                    // 브로커가 구독을 거부하면 SUBACK 이유 코드가 0x80 이상 (N-08). Mosquitto 는 와일드카드 구독은 받아 두고
                    // 전달 단계에서 ACL 로 거르므로, 이 경고가 없어도 계정이 틀리면 메시지가 오지 않을 수 있다
                    int[] codes = t.getReasonCodes();
                    if (codes != null && Arrays.stream(codes).anyMatch(c -> c >= 0x80)) {
                        log.error("MQTT 구독 거부(권한 없음?): {} 이유 코드 {} — 브로커 ACL 과 계정({})을 확인하세요",
                                String.join(", ", topics), Arrays.toString(codes), props.username());
                        return;
                    }
                    log.info("MQTT 구독 완료: {}", String.join(", ", topics));
                }

                @Override
                public void onFailure(IMqttToken t, Throwable e) {
                    log.error("MQTT 구독 실패: {}", e.toString());
                }
            });
        } catch (MqttException e) {
            log.error("MQTT 구독 요청 실패: {}", e.toString());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        boolean ack;
        try {
            ack = handler.handle(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // 콜백에서 예외가 밖으로 나가면 Paho 가 연결을 끊으므로 여기서 모두 잡는다
            log.error("메시지 처리 중 예상하지 못한 오류 — ack 보류: {}", e.toString(), e);
            ack = false;
        }
        if (ack) {
            try {
                client.messageArrivedComplete(message.getId(), message.getQos());
            } catch (MqttException e) {
                log.warn("MQTT ack 실패(Kafka 기록은 완료) — 재전송되면 중복 제거로 걸러짐: {}", e.toString());
            }
        }
    }

    @Override
    public void disconnected(MqttDisconnectResponse response) {
        if (running) {
            log.warn("MQTT 연결 끊김 — 자동 재접속 시도: {}", response);
        }
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.error("MQTT 오류: {}", exception.toString());
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        // 이 클라이언트는 발행하지 않으므로 사용하지 않음
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // 인증 확장 미사용
    }
}
