# 02. 지속 세션 재접속 시 구독 교착 상태

- 발생일: 2026-11-14
- 영향: 브리지가 재시작 후 MQTT 구독에 실패해 메시지를 받지 못함
- 관련 결정: [ADR-004](../decisions/ADR-004-paho-direct-client.md)

## 현상
01번 문제를 고친 뒤 브리지를 재시작하자 다음 오류로 구독에 실패했습니다.

```
Error subscribing to [MqttSubscription [topic=factory/+/+/telemetry, qos=1, ...]]
서버에서 응답을 기다리는 중 제한시간 초과 (32000)
  at org.eclipse.paho.mqttv5.client.MqttToken.waitForCompletion(...)
  at ...Mqttv5PahoMessageDrivenChannelAdapter.subscribe(...)
  at ...Mqttv5PahoMessageDrivenChannelAdapter.connectComplete(...)
  at org.eclipse.paho.mqttv5.client.internal.CommsCallback.run(...)
```

## 원인 분석

**1. 상황 정리**
- 01번 문제 때문에 이전 실행에서 ack를 보내지 못한 메시지가 수백 건 있었습니다.
- 지속 세션이라 Mosquitto가 이 메시지들을 보관하고 있었습니다.

**2. 스택 트레이스 읽기**

맨 아래부터 위로 읽으면 호출 순서가 보입니다.
`CommsCallback.run` (Paho의 콜백 스레드) → `connectComplete` (접속 완료 콜백) → `subscribe` → `waitForCompletion` (구독 완료까지 대기)

즉 **콜백 스레드 안에서** 구독 완료를 기다리고 있었습니다.

**3. 교착 상태가 생긴 구조**

```
[Paho 콜백 스레드 — 하나뿐]
  ① 접속 완료 콜백 실행 → 구독 요청 → "구독 완료"를 기다리며 멈춤
  ② (대기열) 브로커가 보내온 밀린 메시지 수백 건
  ③ (대기열) "구독 완료" 처리
          ↑ ①이 끝나야 처리되는데, ①은 ③을 기다림 → 교착 상태 → 32초 후 타임아웃
```

비유하면, 창구 직원이 한 명뿐인 우체국에서 직원이 "내 서류 승인 전화가 올 때까지 기다린다"며 창구를 멈췄는데, 그 승인 전화도 같은 창구 줄 뒤에 서 있는 상황입니다.

**4. 왜 설정 조정으로 해결되지 않는가**

밀린 메시지가 있는 상태에서 재접속하는 것은 **지속 세션을 쓰는 이유 그 자체**입니다. 지속 세션을 끄면 문제는 사라지지만, 브리지가 꺼진 동안의 데이터를 잃게 됩니다.

## 조치
Spring Integration 어댑터를 Paho MQTT v5 클라이언트 직접 사용(`MqttTelemetrySubscriber`)으로 교체했습니다.

```java
@Override
public void connectComplete(boolean reconnect, String serverURI) {
    // 중요: 여기서 구독 완료를 기다리지 않는다 (콜백 스레드를 막으면 교착 상태)
    IMqttToken token = client.subscribe(topics, qos);
    token.setActionCallback(/* 결과는 비동기 알림으로 받아 로그만 남김 */);
}
```

함께 보강한 것:
- 수신 상한(`receiveMaximum`) 100건: 밀린 메시지가 아무리 많아도 감당할 만큼씩만 받음
- 첫 접속 실패 시 5초마다 재시도
- 콜백 안의 모든 예외 처리 (예외가 밖으로 나가면 Paho가 연결을 끊음)

## 확인
- 재시작 후 `MQTT 접속` → `MQTT 구독 완료` 로그가 순서대로 출력
- 시뮬레이터 40건 발행 → `published` 40, `failed` 0

## 재발 방지
- **콜백 스레드 안에서는 절대 블로킹 대기를 하지 않는다.** 결과가 필요하면 비동기 알림으로 받는다.
- "평소에는 문제없지만 장애 복구 상황에서만 드러나는" 경로(밀린 메시지가 있는 재접속)를 시험 항목에 넣는다.

## 배운 점
프레임워크가 감싸 주는 편의 기능은 대부분의 상황에서 잘 동작하지만, **장애 복구처럼 드문 상황**에서는 내부 스레드 구조가 그대로 드러난다. 스택 트레이스를 아래에서 위로 읽어 "어느 스레드에서 무엇을 기다리고 있는지"를 찾는 것이 원인 파악의 출발점이었다.
