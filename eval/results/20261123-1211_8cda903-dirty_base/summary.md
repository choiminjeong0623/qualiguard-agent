## 시간 순 재생 평가 — 사례 5건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 65(재확인) | 0 | 68 | 0 | 2 (1) | 47.2 / 50.8 |
| pressure_sensor_spike | 1/1 | 7 | 0 | 구간 내 없음 | 0 | 1 (0) | 51.8 / 51.8 |
| cooling_blockage | 1/1 | 40 | 0 | 116 | 0 | 1 (0) | 45.3 / 45.3 |
| check_ring_wear | 1/1 | 32 | 0 | 39 | 0 | 1 (0) | 44.8 / 44.8 |
| 단발성 경보 (shot 1934 쿠션량) | 1/1 | - | - | - | 0 | 2 (1) | 47.3 / 48.2 |

- 정답 진단: **5/5** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 7회 (분석 7단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 51회
- 분석 1단계 소요: 평균 47.3초, 최소 44초, 최대 52초, 1분 초과 0/7단계
- 분석 1단계 토큰 평균: 입력 26398, 출력 1113, 생각 - (7단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 단계별 판단

**heater_degradation — 1회차** (`replay-1123120517-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 325 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도 최근 5 shot 평균이 228.0℃ 이하로 추가 하강하는지; 수지 용융 불량으로 인해 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)가 동반 상승하는지; 미성형(SHORT_SHOT) 등 품질 불량이 발생하는지 |
| 2 | shot 365 재확인 | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 조치 전까지 실린더 온도가 225.0℃ 이하로 추가 하락하는지; 사출 압력이 1220 bar 이상으로 급상승하거나 미성형(SHORT_SHOT) 불량이 발생하는지; 모터 전류가 관리 상한(50 A)에 근접하는지 |

**pressure_sensor_spike — 1회차** (`replay-1123120517-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 다음 shot에서 사출 압력이 기준 규격(1150~1280 bar) 내 정상 범위로 복귀하는지 여부; 사출 압력 신호의 단발성 스파이크 노이즈 재발 여부 |

**cooling_blockage — 1회차** (`replay-1123120517-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 940 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도가 61.5℃를 초과하여 추가 상승하는지 여부; 사이클 타임 5 shot 평균이 30.2s 이상으로 추가 지연되는지 여부; 성형품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1123120517-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1232 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 5 shot 평균이 4.5 mm 이하로 추가 저하되는지; 사출 압력의 표준편차가 15 bar 이상으로 산포가 커지는지; 미성형(SHORT_SHOT) 불량 발생 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1123120517-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 연속 하강하는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급증하거나 미성형(SHORT_SHOT) 불량이 추가 발생하는지 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 쿠션량 개별 측정값이 관리 범위(4.68~5.25 mm)를 재이탈하는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급증하거나 미성형 불량이 발생하는지 여부 |
