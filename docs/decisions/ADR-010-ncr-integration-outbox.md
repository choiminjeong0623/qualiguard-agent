# ADR-010. 승인 결과는 트랜잭션 아웃박스로 Kafka 에 보내고, 가상 MES/ERP 가 중복 없이 받는다

- 상태: 채택
- 날짜: 2026-11-15
- 관련 요구사항: F-09(승인 결과를 다른 시스템으로 전달), 문제 정의서 범위(실제 MES/ERP 대신 가상 수신 서비스)

## 배경
담당자가 부적합을 승인하면 MES(설비 점검 지시)와 ERP(품질 비용 전표)가 알아야 합니다. 승인은 SQL Server 트랜잭션이고, 전달은 Kafka(`quality.ncr.approved`)입니다. **두 저장소에 함께 쓰는 순간 둘 중 하나만 성공하는 경우**가 생깁니다.

| 방식 | 문제 |
|---|---|
| 승인 API 에서 DB 커밋 후 Kafka 전송 | Kafka 가 잠시 내려가 있으면 승인은 됐는데 이벤트는 사라짐 |
| Kafka 전송 후 DB 커밋 | 커밋이 실패하면 승인되지 않은 부적합이 MES/ERP 로 나감 |
| 분산 트랜잭션 | SQL Server 와 Kafka 를 묶는 표준 방법이 없고 무거움 |

## 결정 1. 트랜잭션 아웃박스
승인 프로시저(`usp_ReviewNcr`)가 **같은 트랜잭션 안에서** `usp_EnqueueNcrApproved`로 `integration_outbox`에 이벤트(JSON)를 씁니다. 앱의 `OutboxPublisher`가 2초마다 아직 보내지 않은 이벤트를 Kafka 로 보내고, **기록 성공을 확인한 뒤에만** `published_at`을 채웁니다.
- 승인과 이벤트 기록은 함께 커밋되거나 함께 취소됩니다.
- Kafka 가 내려가 있으면 `attempts`·`last_error`만 쌓이고, 복구되면 그대로 나갑니다 (최대 10회).
- 메시지 key 는 설비 ID 입니다 (같은 설비 이벤트의 순서 보장, [아키텍처](../02-architecture.md)와 같은 원칙). 전송이 실패하면 그 주기는 멈춰 뒤 이벤트가 앞질러 가지 않게 합니다.

## 결정 2. 최소 1번 전송 + 받는 쪽 중복 제거
아웃박스는 "보냈는데 `published_at` 기록 전에 앱이 죽는" 경우 같은 이벤트를 다시 보낼 수 있습니다(at-least-once). 그래서 수신 쪽 `integration_receipt`에 `(event_id, system_name)` 고유 제약을 두고 `INSERT … WHERE NOT EXISTS`로 한 번만 처리합니다. 수집 구간의 "중복 제거 + 멱등 적재"([아키텍처 4장](../02-architecture.md))와 같은 방식입니다.

## 결정 3. 가상 MES/ERP 는 같은 앱의 별도 소비자 그룹
| 선택지 | 장단점 |
|---|---|
| 별도 서비스(컨테이너) | 실제 구성과 비슷하지만 8GB 환경에서 JVM 이 하나 더 필요 |
| **같은 앱의 `MesErpReceiver` (소비자 그룹 `qg-mes-erp-sim`)** | JVM 하나 유지. 소비자 그룹이 달라 DB 적재 소비자와 서로 영향 없음. 패키지(`integration/`)를 분리해 나중에 떼어 내기 쉬움 |

수신하면 MES 에는 "설비 점검 작업 지시", ERP 에는 "품질 비용 전표 대기"를 기록합니다. 금액 계산은 하지 않습니다.

## 결과
- 화면 상세에 "MES/ERP 연계"(전송 대기·전송됨·수신 내용)가 보입니다.
- 설정: `qg.integration.publish-enabled`, `qg.integration.poll-ms`, `qg.integration.receiver-enabled`

## 한계
- ~~실제 MES/ERP 의 응답(작업 지시 번호 등)을 받아 QMS 에 되돌려 기록하는 흐름은 없습니다.~~ → [ADR-012](ADR-012-mes-erp-ack.md)에서 응답(수신 확인·처리 완료·실패)과 다시 보내기를 추가했습니다.
- 반려·조치 완료는 보내지 않습니다 (승인만 다른 시스템의 업무를 만들기 때문).
