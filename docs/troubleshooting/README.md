# 장애 분석 기록

개발 중 겪은 문제를 **현상 → 원인 → 조치 → 재발 방지** 순서로 기록합니다.

| 번호 | 제목 | 영향 | 관련 결정 |
|---|---|---|---|
| [01](01-mqtt-ack-header-type.md) | MQTT 수동 ack 헤더 타입 오류 | Kafka 기록은 성공했으나 ack 실패로 로그가 "기록 실패"로 잘못 표시 | — |
| [02](02-mqtt-persistent-session-deadlock.md) | 지속 세션 재접속 시 구독 교착 상태 | 브리지가 메시지를 받지 못함 | [ADR-004](../decisions/ADR-004-paho-direct-client.md) |
