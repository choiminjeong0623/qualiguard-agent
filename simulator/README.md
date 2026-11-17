# 센서 시뮬레이터 (사출성형 라인)

사출성형기(IMM-01, IMM-02)가 shot마다 공정 데이터를 MQTT로 보내는 상황을 흉내 낸다.
지정한 시점에 고장 시나리오를 주입하고, 평가용 정답을 `../data/ground_truth.csv`에 남긴다.

## 실행

```bash
cd ~/dev/qualiguard-agent/simulator
uv sync                                   # Python 3.12 가상환경과 의존성 설치
uv run qg-simulator --dry-run --shots 3   # 브로커 없이 출력만 확인
uv run qg-simulator                       # Mosquitto(localhost:1883)로 발행
```

설비마다 따로 접속하며 **사용자명 = 설비 ID**, 비밀번호는 프로젝트 루트 `.env` 의 `MQTT_DEVICE_PASSWORD_IMM_01` 처럼 설비 ID 의 `-` 를 `_` 로 바꾼 키에서 읽습니다. 브로커는 장비 인증·토픽 권한을 켠 상태라 `./scripts/mqtt_users.sh` 를 먼저 실행해야 합니다 ([ADR-015](../docs/decisions/ADR-015-mqtt-device-auth.md)). 다른 `.env` 를 쓰려면 `--env-file`.

메시지 확인(다른 터미널):

```bash
# 브리지 계정으로 읽는다 (ACL 상 telemetry 읽기는 브리지 계정만 가능)
docker exec qg-mosquitto mosquitto_sub -u qg-bridge -P "$(grep ^MQTT_BRIDGE_PASSWORD= ../.env | cut -d= -f2)" \
  -t 'factory/+/+/telemetry' -v -C 5
```

## 메시지 형식

토픽: `factory/{line}/{equipment}/telemetry` — 예: `factory/LINE1/IMM-01/telemetry`

```json
{
  "messageId": "b1c2…", "lineId": "LINE1", "equipmentId": "IMM-01", "shotNo": 301,
  "serialNo": "TMP-LINE1-IMM-01-261117-00301",
  "ts": "2026-11-14T16:20:01.123+09:00",
  "sensors": { "moldTemp": {"value": 60.3, "unit": "C"}, "barrelTemp": {"value": 229.1, "unit": "C"}, "…": {} },
  "inspection": { "result": "OK", "defectType": null }
}
```

실제 설비처럼 결측이 섞인 메시지도 보낸다 ([ADR-017](../docs/decisions/ADR-017-telemetry-nulls-serial.md)).
필수는 `messageId`·`lineId`·`equipmentId`·`ts` 뿐이다.

```json
{
  "messageId": "c3d4…", "lineId": "LINE1", "equipmentId": "IMM-02",
  "serialNo": null,
  "ts": "2026-11-14T16:20:01.623+09:00",
  "sensors": { "moldTemp": {"value": null, "unit": "C"}, "barrelTemp": {"value": 229.4, "unit": "C"} },
  "inspection": null
}
```
위 예는 `shotNo` 없음, 시리얼 미할당, 금형 온도 값 없음, 센서 5개 키 없음, 검사 결과 없음이다.

| `scenarios.yaml` `missing` | 기본값 | 의미 |
|---|---|---|
| `sensor_null_rate` | 0.03 | 센서마다 `"value": null` 로 보낼 확률 |
| `sensor_drop_rate` | 0.02 | 센서마다 키 자체를 뺄 확률 |
| `inspection_missing_rate` | 0.01 | `inspection: null` 로 보낼 확률 |
| `shotno_missing_rate` | 0.005 | `shotNo` 를 뺄 확률 |
| `serial_unassigned_rate` | 0.05 | `serialNo: null`(미할당) 확률. 할당돼도 공정 중에는 임시값 `TMP-…` |
| `serial_duplicate_rate` | 0.02 | 직전 임시 시리얼을 다시 쓸 확률 — 확정 시리얼은 출하 때 붙고 그때만 고유 |
| `sensor_outage_rate` | 0.01 | shot 마다 센서 하나가 끊기기 시작할 확률. 끊긴 동안 그 센서는 계속 null |
| `sensor_outage_min` / `max` | 5 / 30 | 끊김 길이(shot) |
| `message_drop_rate` | 0.005 | shot 메시지를 통째로 보내지 않을 확률 |
| `resend_rate` | 0.02 | 같은 `messageId` 를 한 번 더 보낼 확률(바로 또는 몇 shot 뒤) |
| `delay_rate` | 0.01 | 메시지를 늦춰 뒤 shot 보다 늦게 보낼 확률(순서 어긋남) |
| `delay_max_shots` | 5 | 늦출 때 최대 몇 shot 뒤 |

모두 0 이면 예전처럼 빠짐없는 메시지를 보낸다(공정 값·불량은 같은 seed 에서 같다). 결측은 측정·전송 문제라
불량 판정과 `data/ground_truth.csv` 는 실제 값 기준이고, 정답 파일에 `messageId`·`serialNo`·`missingFields`(보낼 때 비운 항목)·`delivery`(`sent`·`dropped`·`delayed`, 재전송이면 `+resent`)를 함께 남긴다.
끝날 때 `전송 n건 = shot - 통째 누락 + 재전송, 늦게 보냄 n건` 과 센서 끊김 구간 수를 출력한다.
`--dry-run` 은 정답 파일을 쓰지 않는다(실제 발행 때 만든 정답을 덮어쓰지 않도록).

시험: `uv run --with pytest pytest tests`

| 센서 | 의미 | 기준값 |
|---|---|---|
| moldTemp | 금형 온도 | 60 ℃ |
| barrelTemp | 실린더(용융) 온도 | 230 ℃ |
| injectionPressure | 사출 압력 | 1200 bar |
| holdingPressure | 보압 | 600 bar |
| cushion | 쿠션량(사출 후 남은 수지) | 5 mm |
| cycleTime | 사이클 타임 | 30 s |
| motorCurrent | 모터 전류 | 45 A |

## 고장 시나리오

| 이름 | 현상 | 품질 영향 |
|---|---|---|
| heater_degradation | 실린더 온도 하락, 사출 압력·모터 전류 상승 | 미성형 증가 |
| cooling_blockage | 금형 온도 상승, 사이클 타임 증가 | 치수 불량·휨 증가 |
| check_ring_wear | 쿠션량 감소, 사출 압력 변동 확대 | 미성형 증가 |
| pressure_sensor_spike | 사출 압력 값이 가끔 튐 (측정 이상) | 없음 — 오탐 판별 시험용 |

데이터는 합성 데이터이며, 공정 변수와 불량의 관계는 일반적인 사출성형 지식을 바탕으로 단순화했다.
