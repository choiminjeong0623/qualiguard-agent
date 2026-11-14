# ADR-006. 이상 탐지 상태를 Kafka Streams 저장소에 두고 exactly-once로 처리한다

- 상태: 채택
- 날짜: 2026-11-14

## 배경
SPC 판정에는 설비별 **상태**가 필요합니다. 기준 구간에서 계산한 평균·표준편차, 최근 값, 연속 이탈 횟수, 마지막 경보 시점 등입니다. 이 상태를 어디에 둘지, 장애 후 재처리 때 경보가 두 번 나가지 않게 하려면 어떻게 할지 정해야 했습니다.

## 고려한 선택지
| 선택지 | 단점 |
|---|---|
| 앱 메모리(Map) | 재시작하면 기준값이 사라져 처음 100 shot을 다시 학습해야 함 |
| DB에 매번 저장 | shot마다 DB 왕복, 메시지 처리와 DB 기록의 원자성 보장이 어려움 |
| **Kafka Streams 상태 저장소 + exactly-once v2** | 상태는 로컬 RocksDB에 두고 변경 내역은 Kafka changelog 토픽에 기록 → 재시작·장애 후 복원. 상태 갱신과 결과 기록이 하나의 트랜잭션 |

## 결정
`SpcProcessor`(Kafka Streams Processor API)가 설비 ID를 key로 상태를 읽고 갱신하며, `processing.guarantee=exactly_once_v2`로 처리합니다.

## 근거
- 같은 설비의 메시지는 key가 같아 같은 파티션·같은 처리 단위로 모이므로, 설비별 상태를 다른 스레드와 다투지 않고 다룰 수 있습니다.
- 상태 갱신과 `quality.anomaly` 기록이 함께 커밋되므로, 중간에 장애가 나서 재처리해도 경보가 중복 발행되지 않습니다.
- 브리지 재시작으로 메모리 중복 제거 기록이 사라져 Kafka에 중복이 들어오는 경우를, 설비별 상태의 최근 messageId 200건으로 **한 번 더** 거릅니다.

## 결과
- 판정 로직(`SpcEngine`)은 Kafka에 의존하지 않는 순수 Java로 분리해 단위 테스트로 검증합니다. Kafka Streams 쪽(`SpcProcessor`)은 상태 저장·출력만 담당합니다.
- 출력은 정제 데이터(`sensor.clean`)와 이상(`quality.anomaly`) 두 갈래입니다.
- 상태 파일은 `qms-app/build/kafka-streams-state`에 생기며 Git에서 제외됩니다.
