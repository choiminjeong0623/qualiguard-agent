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

메시지 확인(다른 터미널):

```bash
docker exec qg-mosquitto mosquitto_sub -t 'factory/#' -v -C 5
```

## 메시지 형식

토픽: `factory/{line}/{equipment}/telemetry` — 예: `factory/LINE1/IMM-01/telemetry`

```json
{
  "messageId": "b1c2…", "lineId": "LINE1", "equipmentId": "IMM-01", "shotNo": 301,
  "ts": "2026-11-14T16:20:01.123+09:00",
  "sensors": { "moldTemp": {"value": 60.3, "unit": "C"}, "barrelTemp": {"value": 229.1, "unit": "C"}, "…": {} },
  "inspection": { "result": "OK", "defectType": null }
}
```

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
