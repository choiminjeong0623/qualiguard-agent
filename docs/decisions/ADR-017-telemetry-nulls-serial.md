# ADR-017. 장비 데이터 결측 허용과 시리얼 번호 비고유

- 상태: 채택
- 날짜: 2026-11-17

## 배경
멘토링 피드백: 실제 설비 데이터는 결측이 많다. 센서 하나가 끊기면 PLC·게이트웨이는 그 값만 비우거나(`"value": null`)
항목 자체를 빼고 보내고, 비전 검사기가 멈추면 검사 결과 없이 공정 값만 올라온다. shot 카운터가 빠지는 메시지도 있다.
센서가 끊기면 같은 센서가 몇~수십 shot 동안 계속 비고, 네트워크 문제로 shot 메시지가 통째로 빠지거나(누락)
같은 메시지가 다시 오거나(재전송) 늦게 와서 순서가 어긋난다.
또 **시리얼 번호는 출하 전까지 고유 키가 아니다.** 공정 중에는 없거나(null) 임시값(`TMP-…`)이고, 재작업·라벨 재출력으로
같은 임시값이 다시 쓰인다. 확정 시리얼은 출하 때 붙는다.

지금까지는 `shotNo`·`sensors`·센서 `value`·`unit`·`inspection.result` 가 모두 필수였다. 그래서 결측이 하나라도 있는 메시지는
DLQ 로 가서 **공정 데이터 전체를 잃었다.** 센서 1개가 빠졌다고 나머지 6개 센서와 검사 결과까지 버리는 것은 현장 데이터에 맞지 않는다.

## 고려한 선택지
| 선택지 | 장점 | 단점 |
|---|---|---|
| A. 지금처럼 모두 필수, 결측은 DLQ | 하류 코드가 단순 | 결측 1개로 shot 전체를 잃음. 센서 고장 시 그 설비의 감시가 사실상 멈춤 |
| B. 결측을 앞에서 채움(직전 값·평균) | 하류는 그대로 | 없는 값을 만들어 냄 → 관리도·원인 분석이 가짜 값에 끌려감 |
| **C. 결측을 그대로 받고, 쓰는 곳마다 '없음'으로 처리** | 받은 값은 모두 살림. 없는 값을 지어내지 않음 | 하류(SPC·DB·프로시저)가 NULL 을 다뤄야 함 |

## 결정
**C** 를 택했다.

- **필수는 추적·순서에 쓰는 항목뿐**: `messageId`, `lineId`, `equipmentId`, `ts`. 이것이 없거나 JSON 이 깨지면 여전히 DLQ.
- `shotNo` 는 없어도 된다(있으면 양수). `sensors` 는 null·빈 맵·일부 누락·`value: null` 모두 허용. `inspection` 은 null 허용.
- **추적 키는 `messageId`(중복 제거)와 `equipmentId + shotNo + ts`(사람이 읽는 위치)** 다. `shotNo` 는 빠지거나
  설비 카운터 리셋으로 되풀이될 수 있어 `ts` 와 함께 본다. `serialNo` 는 추적 키로 쓰지 않는다.
- `serialNo` 는 null·임시값·중복을 그대로 받아 `process_shot.serial_no`(공정 중 시리얼)에 적재한다(UNIQUE 없음, 찾기용 비고유 인덱스만).
- **중복은 세 층에서 한 번만 반영**한다: 브리지 `MessageIdDeduplicator`(Kafka 앞) → SPC `recentMessageIds`(브리지 재시작 뒤
  들어온 중복) → DB `ux_process_shot_message` + `INSERT … WHERE NOT EXISTS`(동시에 들어와 고유 인덱스에 걸려도 실패로 보지 않고 중복으로 셈).
- **늦게 온 shot**(그 설비에서 이미 판정한 shot 보다 `ts` 가 앞섬)은 **저장은 하고 실시간 관리도 판정은 건너뛴다.**
  X̄·S 관리도의 부분군과 '연속 2회' 판정은 시간 순서를 전제로 하므로, 순서가 어긋난 값을 넣으면 판정이 흔들린다.
  `shotNo` 가 아니라 `ts` 로 판단하는 이유는 shotNo 가 빠질 수 있고 설비 카운터가 리셋되면 작아지기 때문이다(리셋 뒤 shot 은 ts 가 뒤라 정상 처리).
  해당 행은 `process_shot.arrival_late = 1` 로 남고 `qg.spc.late_shots` 로 센다.
- **통째 누락**(shotNo 건너뜀)은 채우지 않는다. 받은 shot 만으로 판정한다.
- **센서 끊김**: 같은 센서 값이 5 shot 연속 비면 로그 `[센서 끊김]`과 `qg.spc.sensor_outage`(equipment, sensor)로 알린다.
  이상 탐지 경보(`quality.anomaly`)로 만들지 않는다 — 공정 이상이 아니라 계측 문제라 원인 분석 에이전트의 대상이 아니다.
  끊긴 동안 그 센서는 판정에서 빠지고, 기준값(μ, σ)과 진행 중인 부분군은 그대로 이어진다.

## 시리얼 확정 연결 설계 (향후)
공정 중 시리얼은 고유하지 않으므로, 출하 확정 때 **확정 시리얼 ↔ shot** 연결 표 `dbo.shipment_serial_link` 에 한 줄씩 넣고
**이때만 고유성을 강제**한다(`15_shipment_serial_link.sql`, 표만 먼저 만듦).

| 칸 | 의미 |
|---|---|
| `confirmed_serial` (UNIQUE) | 출하 때 확정한 시리얼 — 한 번만 |
| `message_id` (UNIQUE, FK → `process_shot.message_id`) | 그 제품을 만든 shot — shot 하나는 확정 시리얼 하나에만 |
| `equipment_id`, `shot_no`, `shot_ts` | 사람이 읽는 추적 키 (shot_no 는 없을 수 있음) |
| `provisional_serial` | 공정 중에 쓰던 임시 시리얼(대조용) |
| `shipment_no`, `confirmed_at`, `confirmed_by` | 출하 번호, 확정 시각·사람 |

채우는 로직(출하 확정 화면·API, 임시 시리얼 중복 시 사람이 shot 을 고르는 절차)은 다음 단계에서 만든다.

## 쓰는 곳별 처리
| 곳 | 처리 |
|---|---|
| 브리지 | 결측 메시지도 `sensor.raw` 로 보냄. 빠진 항목은 `qg.bridge.missing_fields`(field, kind=absent/null) 카운터 |
| SPC(`SpcEngine`) | 값이 없는 센서는 그 shot 에서 invalid — 나머지 센서만 판정. 기준값(μ, σ)도 값이 있는 shot 으로만 |
| 불량률(p 관리도) | 검사 결과가 없는 shot 은 **OK 로 세지 않고 뺀다.** 기준 불량률은 검사 결과가 있는 처음 100 shot 으로 |
| 늦게 온 shot | 값 정리만 하고 관리도·불량률·기준값에 넣지 않음, `arrival_late = 1` 로 저장, `qg.spc.late_shots` |
| 센서 끊김 | 5 shot 연속 결측이면 로그·`qg.spc.sensor_outage` (경보 아님), 회복 시 로그 |
| 경보 위치 | `shotNo` 가 없는 shot 에서 난 경보는 그 설비에서 마지막으로 받은 shot 번호를 쓰고 `shotNoEstimated` 를 남김 (`anomaly_event.shot_no` 는 에이전트가 범위 계산에 쓰므로 NOT NULL 유지) |
| DB | `process_shot.shot_no`·`inspection_result` NULL 허용, `serial_no`·`arrival_late` 추가 (`14_telemetry_nulls.sql`), 확정 시리얼 연결 표 (`15_shipment_serial_link.sql`) |
| 프로시저·에이전트 | 불량률 분모는 검사 결과가 있는 shot 수(`usp_GetDefectSummary.inspected`, 검증 에이전트의 불량률 비교) |

## 결과
- 시뮬레이터 `scenarios.yaml` 의 `missing` 항목으로 결측 비율을 조절한다. 모두 0 이면 예전과 같은 메시지.
  결측은 측정·전송 문제라 불량 판정과 `ground_truth.csv` 는 실제 값 기준이고, 정답 파일에 `serialNo`·`missingFields` 를 함께 남긴다.
- 로컬 확인(설비 2대 × 600 shot, `scenarios.yaml` 기본 비율): shot 1,200건 중 통째 누락 8건 → 1,192건 적재(messageId 모두 다름),
  재전송 29건은 브리지에서 걸러 DB 중복 0건, DLQ 0건, 늦게 도착 10건(`arrival_late`), 센서 끊김 10구간, 센서가 빈 행 469건,
  shotNo 없음 5건, 검사 결과 없음 7건, 시리얼 미할당 59건·임시값 중복 24묶음, 이상 탐지(XBAR·INDIVIDUAL·S_CHART·P_CHART) 그대로 동작.
  SPC·DB 층 중복은 같은 메시지를 `sensor.raw`·`sensor.clean` 에 두 번씩 직접 넣어 각각 1행만 생기는 것을 확인했다.
- 남은 일: 출하 확정 때 `shipment_serial_link` 채우기, 늦게 온 shot 을 나중에 순서대로 다시 판정할지(지금은 저장만).
