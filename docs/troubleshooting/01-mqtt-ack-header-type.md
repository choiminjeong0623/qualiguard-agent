# 01. MQTT 수동 ack 헤더 타입 오류

- 발생일: 2026-11-14
- 영향: 메시지는 Kafka에 정상 기록됐지만 MQTT ack가 실패했고, 로그는 "Kafka 기록 실패"로 잘못 표시됨

## 현상
시뮬레이터를 실행하자 브리지 로그에 메시지마다 다음 오류가 찍혔습니다.

```
Kafka 기록 실패 — MQTT ack 보류(재접속 시 재전송): ...
IllegalArgumentException: Incorrect type specified for header 'acknowledgmentCallback'.
Expected [interface org.springframework.integration.acks.AcknowledgmentCallback] but ...
```

## 원인
1. 수동 ack 모드에서 MQTT v5 어댑터는 확인용 객체를 `SimpleAcknowledgment` 타입으로 헤더에 넣습니다.
2. 코드는 이를 다른 용도(폴링 방식 어댑터)의 `AcknowledgmentCallback` 타입으로 꺼내려 했습니다.
3. ack 호출이 Kafka 기록과 **같은 try 블록** 안에 있어서, ack 단계의 오류가 "Kafka 기록 실패"로 잘못 기록됐습니다.

## 조치
- 헤더 값을 타입을 정하지 않고 꺼낸 뒤 `instanceof`로 실제 타입을 확인해 호출하도록 수정했습니다. 예상과 다른 타입이면 실제 타입 이름을 경고로 남깁니다.
- Kafka 기록과 ack를 **서로 다른 단계로 분리**해, 로그만 보고도 어느 단계가 실패했는지 구분되게 했습니다.

## 재발 방지
- 실패 로그는 "어느 단계에서, 무엇이 끝났고 무엇이 안 끝났는지"를 구분해 남긴다. (예: "MQTT ack 실패(Kafka 기록은 완료)")
- 이후 [02번 문제](02-mqtt-persistent-session-deadlock.md)로 MQTT 수신 구조 자체를 바꾸면서 이 코드는 Paho의 `messageArrivedComplete()` 호출로 대체됐습니다.

## 배운 점
외부 라이브러리 API는 이름이 비슷해도 용도가 다른 타입이 있다. 문서와 실제 객체 타입을 확인하고, 확신이 없을 때는 런타임에 타입을 검사해 **틀렸을 때 원인이 바로 드러나도록** 작성한다.
