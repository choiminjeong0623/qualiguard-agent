# 장애 분석 기록

개발 중 겪은 문제를 **현상 → 원인 → 조치 → 재발 방지** 순서로 기록합니다.

| 번호 | 제목 | 영향 | 관련 결정 |
|---|---|---|---|
| [01](01-mqtt-ack-header-type.md) | MQTT 수동 ack 헤더 타입 오류 | Kafka 기록은 성공했으나 ack 실패로 로그가 "기록 실패"로 잘못 표시 | — |
| [02](02-mqtt-persistent-session-deadlock.md) | 지속 세션 재접속 시 구독 교착 상태 | 브리지가 메시지를 받지 못함 | [ADR-004](../decisions/ADR-004-paho-direct-client.md) |
| [03](03-agent-llm-response-parsing.md) | 원인 분석 에이전트의 LLM 호출 실패와 응답 해석 실패 | 평가 5건 중 4~5건 분석 실패 (결과·초안 미생성) → 해결 후 5/5 | [ADR-008](../decisions/ADR-008-agent-design.md) |
| [04](04-agent-evaluation-leakage.md) | 에이전트 평가 방식의 허점: 첫 평가 5/5는 유리한 조건이었다 | 성과가 실제보다 좋게 측정됨 (5/5 → 반복 평가 12/15) | [ADR-007](../decisions/ADR-007-sqlserver-qms-schema.md), [ADR-008](../decisions/ADR-008-agent-design.md) |
| [05](05-agent-speed-vs-diagnosis-timing.md) | 분석을 빠르게 만든 설정이 진단 시점을 늦췄다 | thinking LOW로 19초까지 빨라졌지만 냉각 막힘 진단 +35 → +76 shot (미채택) | [ADR-008](../decisions/ADR-008-agent-design.md) |
| [06](06-knowledge-base-future-leakage.md) | 승인한 AI 초안이 지식저장소로 돌아오며 생긴 세 번째 미래 정보 누수 | 재분석·재생 평가가 같은 고장의 확정 보고서를 미리 볼 수 있었음 (구현 중 차단) | [ADR-008](../decisions/ADR-008-agent-design.md) 결정 6 |
| [07](07-qms-static-cache.md) | QMS 화면을 고쳤는데 브라우저에 새 기능이 보이지 않음 | 브라우저가 예전 JS를 캐시 → 정적 파일 no-cache | [ADR-009](../decisions/ADR-009-qms-review-screen.md) |
| [08](08-local-dev-environment.md) | 로컬 개발 환경에서 평가·검증을 돌리며 겪은 문제들 | 포트 점유, `gradlew clean`으로 Streams 상태 삭제, 테스트의 소비자 그룹 간섭, 로컬·원격 불일치, 작업 중단 | [ADR-006](../decisions/ADR-006-kafka-streams-state.md) |
| [09](09-cold-start-schema-order.md) | 빈 DB 에서 처음 띄우면 DB 초기화가 04 에서 멈춤 | `mssql-init` Exited (1), 05~19 미실행 → `02_schema.sql` 로 `cause_code` 를 옮겨 해결 | 로드맵 F1 |
