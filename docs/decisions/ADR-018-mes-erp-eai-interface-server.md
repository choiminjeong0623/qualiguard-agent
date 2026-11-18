# ADR-018. MES/ERP 연계는 Kafka 대신 인터페이스(EAI) 서버로 보낸다 — 아웃박스는 유지

- 상태: **제안 (계획, 아직 구현 전)**
- 날짜: 2026-11-18
- 관련: F-09, [ADR-010](ADR-010-ncr-integration-outbox.md)(아웃박스 → Kafka), [ADR-012](ADR-012-mes-erp-ack.md)(응답·다시 보내기)
- 상세 설계와 구현 순서: [06. MES/ERP 연계 EAI 계획](../06-mes-erp-eai-plan.md)

## 배경
ADR-010 에서 승인 결과를 Kafka(`quality.ncr.approved`)로 보낸 이유는 "이미 센서용 Kafka 가 있으니 재사용"이었습니다. 다시 따져 보니:

- NCR 승인은 **하루 몇 건~몇십 건**입니다. 아웃박스에 많은 데이터가 실시간으로 쌓이지 않습니다.
- 제조 현장의 MES/ERP 연계는 **EAI·인터페이스 서버**를 두는 경우가 흔하고, 연계 이력 테이블로 운영합니다.
- "한 번만 처리"를 보장하는 것은 Kafka 가 아니라 **아웃박스 + 재시도 + 받는 쪽 중복 제거**입니다.

## 선택지
| 선택지 | 장점 | 단점 |
|---|---|---|
| Kafka 유지 (현재) | 인프라 재사용, 받는 쪽이 늘어도 QMS 수정 없음, 보관·다시 읽기 | 양에 비해 무거움, MES/ERP 담당자에게 낯선 운영(토픽·오프셋) |
| QMS 가 MES·ERP 를 직접 REST 호출 | 가장 단순 | QMS 가 시스템마다 주소·형식·재시도를 알아야 함, 시스템이 늘면 QMS 수정 |
| **인터페이스 서버 (EAI) + 아웃박스** | 현장에 익숙, QMS 는 연계 서버 하나만 앎, 라우팅·어댑터로 대상 추가, 연계 이력 한곳에서 추적 | 서버 하나 더 운영, 다시 읽기는 직접 구현(원문 저장) |

## 결정
1. **아웃박스는 그대로** 둡니다. 승인과 이벤트 기록은 한 트랜잭션입니다.
2. `OutboxPublisher` 는 인터페이스 서버를 **REST 로 호출**하고(`Idempotency-Key = eventId`), **202 를 받은 뒤에만** 보낸 것으로 기록합니다. 일시적 실패는 **지수 백오프**(2초부터 두 배, 상한 5분, 최대 10회), 형식 오류는 재시도 없이 `DEAD`.
3. 인터페이스 서버는 받은 원문을 저장하고, 라우팅 규칙에 따라 **MES·ERP 어댑터**로 나눠 보냅니다. 중복 제거는 `(event_id, system)` 고유 제약.
4. 처리 결과는 지금과 같은 `RECEIVED(1) → PROCESSED / FAILED(2)` 응답을 **QMS 콜백 API** 로 보냅니다. 콜백도 아웃박스 + 재시도이고, QMS 는 `usp_ApplyIntegrationAck`(response_id 중복 제거, seq 큰 것만)를 그대로 씁니다.
5. 실패한 시스템만 다시 보내기(새 event_id + `targetSystems`)는 그대로 유지합니다.
6. 전송 방식은 `qg.integration.transport: kafka | eai` 로 고릅니다. 전환 전까지 기본값은 `kafka`.
7. **Kafka 는 센서 이벤트와 에이전트 이벤트(`agent.*`)에 계속 씁니다.**

## 결과 (구현 후 기대)
- 연계 경로: 아웃박스 → 인터페이스 서버 → MES/ERP 어댑터 → 콜백 → QMS
- `quality.ncr.approved`, `integration.ncr.ack` 토픽과 `MesErpReceiver`, `IntegrationAckListener` 는 `transport=kafka` 일 때만 사용

## 한계
- Kafka 의 보관·오프셋 되돌리기 대신 `if_message` 원문 재처리를 직접 만들어야 합니다.
- at-least-once 구간이 둘(발행, 콜백)이 되어, 양쪽 중복 제거가 모두 필요합니다.
- 알림·통계 등 받는 쪽이 많이 늘어나면 다시 Kafka(또는 인터페이스 서버 뒤에 메시지 브로커)가 나을 수 있습니다.
