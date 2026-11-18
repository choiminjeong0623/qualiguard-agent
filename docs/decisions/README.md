# 의사결정 기록 (ADR)

중요한 설계 결정마다 **배경 → 선택지 → 결정 → 결과**를 남깁니다. 나중에 "왜 이렇게 만들었는지"를 코드만 보고는 알기 어렵기 때문입니다.

| 번호 | 제목 | 상태 |
|---|---|---|
| [ADR-001](ADR-001-shot-level-message.md) | MQTT 메시지를 shot 단위로 묶는다 | 채택 |
| [ADR-002](ADR-002-synthetic-data.md) | 합성 데이터를 주력으로, 공개 데이터는 보조로 쓴다 | 채택 |
| [ADR-003](ADR-003-local-sql-server.md) | SQL Server를 로컬 컨테이너로 운영한다 | 채택 |
| [ADR-004](ADR-004-paho-direct-client.md) | MQTT 수신에 Paho 클라이언트를 직접 사용한다 | 채택 (초기 결정 대체) |
| [ADR-005](ADR-005-spc-detect-llm-reason.md) | 탐지는 SPC, 판단은 LLM이 맡는다 | 채택 |
| [ADR-006](ADR-006-kafka-streams-state.md) | 이상 탐지 상태를 Kafka Streams 저장소에 두고 exactly-once로 처리한다 | 채택 |
| [ADR-007](ADR-007-sqlserver-qms-schema.md) | QMS 데이터는 shot 원본으로 저장하고, 조회는 저장 프로시저로 제공한다 | 채택 |
| [ADR-008](ADR-008-agent-design.md) | 원인 분석 에이전트: 사건 단위 분석, 지식저장소 기반 추론, 사람 승인 | 채택 |
| [ADR-009](ADR-009-qms-review-screen.md) | QMS 검토 화면: 상태 규칙은 저장 프로시저에, 화면은 jQuery 정적 페이지로 | 채택 |
| [ADR-010](ADR-010-ncr-integration-outbox.md) | 승인 결과는 트랜잭션 아웃박스로 Kafka 에 보내고, 가상 MES/ERP 가 중복 없이 받는다 | 채택 |
| [ADR-011](ADR-011-qms-login.md) | QMS 로그인: DB 사용자 + BCrypt, 역할 두 가지, 에이전트 API 는 로컬 도구용으로 열어 둠 | 채택 |
| [ADR-012](ADR-012-mes-erp-ack.md) | MES/ERP 응답: 단계 번호로 순서를 지키고, 응답 ID 로 중복을 거르고, 실패한 시스템에만 다시 보낸다 | 채택 |
| [ADR-013](ADR-013-mcp-server.md) | 에이전트 도구를 MCP 서버로도 노출한다 — 기준 시점은 기준 경보로만 | 채택 |
| ADR-014 | 유사 사례 의미 검색 (SQL Server VECTOR) — 브랜치 `feat/ncr-vector-search` 에만 있음 | 보류 ([향후 계획](../future-work.md)) |
| [ADR-015](ADR-015-mqtt-device-auth.md) | MQTT 장비 인증과 토픽별 접근 권한 — 사용자명 = 설비 ID | 채택 |
| [ADR-016](ADR-016-multi-agent-pipeline.md) | 역할 분리형 멀티 에이전트 — 분류·분석·검증·작성·오케스트레이터를 Kafka 이벤트로 잇는다 | 채택 |
| [ADR-017](ADR-017-telemetry-nulls-serial.md) | 장비 데이터 결측 허용과 시리얼 번호 비고유 — 필수는 messageId·lineId·equipmentId·ts 뿐, 늦게 온 shot 은 저장만 | 채택 |
| [ADR-018](ADR-018-mes-erp-eai-interface-server.md) | MES/ERP 연계는 Kafka 대신 인터페이스(EAI) 서버로 보낸다 — 아웃박스는 유지 | 제안 (계획) |
