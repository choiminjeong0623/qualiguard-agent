# 장애 분석 기록

개발 중 겪은 문제를 **현상 → 원인 → 조치 → 재발 방지** 순서로 기록합니다.

| 번호 | 제목 | 영향 | 관련 결정 |
|---|---|---|---|
| [01](01-mqtt-ack-header-type.md) | MQTT 수동 ack 헤더 타입 오류 | Kafka 기록은 성공했으나 ack 실패로 로그가 "기록 실패"로 잘못 표시 | — |
| [02](02-mqtt-persistent-session-deadlock.md) | 지속 세션 재접속 시 구독 교착 상태 | 브리지가 메시지를 받지 못함 | [ADR-004](../decisions/ADR-004-paho-direct-client.md) |
| [03](03-agent-llm-response-parsing.md) | 원인 분석 에이전트의 LLM 호출 실패와 응답 해석 실패 | 평가 5건 중 4~5건 분석 실패 (결과·초안 미생성) → 해결 후 5/5 | [ADR-008](../decisions/ADR-008-agent-design.md) |
| [04](04-agent-evaluation-leakage.md) | 에이전트 평가 방식의 허점: 첫 평가 5/5는 유리한 조건이었다 | 성과가 실제보다 좋게 측정됨 (5/5 → 반복 평가 12/15) | [ADR-007](../decisions/ADR-007-sqlserver-qms-schema.md), [ADR-008](../decisions/ADR-008-agent-design.md) |
