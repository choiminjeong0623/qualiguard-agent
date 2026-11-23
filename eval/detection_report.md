# 이상 탐지 평가 (DetectionEval)

`cd qms-app && ./gradlew detectionEval` 로 다시 만들 수 있습니다. 에이전트(LLM) 없이 SPC 엔진만 채점합니다.

- 데이터: 시뮬레이터 `--out` 파일 출력, seed 5개 (seed-1, seed-2, seed-3, seed-4, seed-5). 결측·중복·늦은 메시지가 섞인 그대로 재생
- 엔진: 운영과 같은 `SpcEngine` (기준 100 shot, 개별값 ±4.0σ, X̄ ±3.0σ 연속 2회, S 관리도, P 관리도 50 shot)
- 채점 단위: 기준 구간 뒤 50 shot 창. 창 안에 고장 shot 이 있으면 고장 창, 경보가 있으면 경보 창
- 수치는 seed 평균 ± 표본 표준편차

## 창 단위 혼동행렬

| seed | TP | FP | FN | TN | 정밀도 | 재현율 | F1 | 오경보율(FPR) | 놓침률(FNR) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| seed-1 | 34 | 3 | 6 | 33 | 0.919 | 0.850 | 0.883 | 0.083 | 0.150 |
| seed-2 | 35 | 1 | 5 | 35 | 0.972 | 0.875 | 0.921 | 0.028 | 0.125 |
| seed-3 | 34 | 1 | 6 | 35 | 0.971 | 0.850 | 0.907 | 0.028 | 0.150 |
| seed-4 | 36 | 4 | 4 | 32 | 0.900 | 0.900 | 0.900 | 0.111 | 0.100 |
| seed-5 | 34 | 1 | 6 | 35 | 0.971 | 0.850 | 0.907 | 0.028 | 0.150 |
| **평균 ± 표준편차** | | | | | 0.947 ± 0.035 | 0.865 ± 0.022 | 0.904 ± 0.014 | 0.056 ± 0.039 | 0.135 ± 0.022 |

## 고장별 탐지 지연

탐지 지연 = 고장 구간 안 첫 경보 shot − 고장 시작 shot. 놓친 구간은 지연 평균에서 빼고 탐지율에 반영합니다.

| 고장 | 설비 | 탐지율 | 지연(shot) 평균 ± 표준편차 | 첫 불량 전 탐지 |
|---|---|---:|---:|---:|
| heater_degradation | IMM-01 | 5/5 | 39.0 ± 8.8 | 3/5 |
| pressure_sensor_spike | IMM-02 | 5/5 | 12.0 ± 18.6 | 5/5 |
| cooling_blockage | IMM-02 | 5/5 | 34.4 ± 2.3 | 2/5 |
| check_ring_wear | IMM-01 | 5/5 | 31.4 ± 7.4 | 3/5 |

## 규칙별 경보 수 (seed 합계)

| 규칙 | 경보 | 고장 중 shot | 정상 shot |
|---|---:|---:|---:|
| INDIVIDUAL | 192 | 185 | 7 |
| P_CHART | 61 | 55 | 6 |
| S_CHART | 60 | 58 | 2 |
| XBAR | 171 | 170 | 1 |

## 재생한 메시지

| seed | 메시지 | 중복 무시 | 늦게 온 shot 재판정 |
|---|---:|---:|---:|
| seed-1 | 4072 | 90 | 31 |
| seed-2 | 4067 | 80 | 37 |
| seed-3 | 4058 | 80 | 24 |
| seed-4 | 4048 | 73 | 33 |
| seed-5 | 4046 | 55 | 20 |

## 읽는 법과 한계

- 고장은 서서히 진행되므로 고장 초반 창은 센서 변화가 작아 놓치는 것이 정상입니다. 재현율보다 탐지 지연과 '첫 불량 전 탐지' 를 함께 봐야 합니다.
- 고장이 끝난 직후 창의 경보는 오경보(FP)로 셉니다. 실제로는 고장의 여파일 수 있어 정밀도를 조금 낮게 잡는 방식입니다.
- '첫 불량 전 탐지' 의 첫 불량에는 고장과 무관한 평상시 불량(약 0.6%)이 섞일 수 있습니다. 그래서 이 열은 조금 엄격하게 나옵니다.
- 시뮬레이터 데이터이므로 실제 설비 성능을 뜻하지 않습니다. 실제 공개 데이터 확인은 SECOM 보조 검증(`./gradlew secomValidation`)을 봅니다.

## 시뮬레이터 조건 (첫 seed 의 ground_truth.meta.json)

```json
{
  "config": "scenarios.yaml",
  "configSha256": "5b2d1f16e98c69d21cd487f9b09b535369cb1d1badfd72e693dc8cf807a7ad4c",
  "seed": 1,
  "shotsPerEquipment": 2000,
  "equipment": [
    "IMM-01",
    "IMM-02"
  ],
  "faults": [
    {
      "name": "heater_degradation",
      "equipment": "IMM-01",
      "startShot": 300,
      "duration": 500
    },
    {
      "name": "pressure_sensor_spike",
      "equipment": "IMM-02",
      "startShot": 400,
      "duration": 200
    },
    {
      "name": "cooling_blockage",
      "equipment": "IMM-02",
      "startShot": 900,
      "duration": 500
    },
    {
      "name": "check_ring_wear",
      "equipment": "IMM-01",
      "startShot": 1200,
      "duration": 600
    }
  ]
}
```
