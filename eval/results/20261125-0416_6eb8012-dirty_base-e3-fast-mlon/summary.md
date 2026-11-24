## 시간 순 재생 평가 — 사례 5건 × 3회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 3/3 | 46, 46, 46 | 0, 0, 0 | 68 | 0 | 1 (0), 1 (0), 1 (0) | 1.5 / 3.9 |
| pressure_sensor_spike | 3/3 | 7, 7, 7 | 0, 0, 0 | 구간 내 없음 | 0 | 1 (0), 1 (0), 1 (0) | 27.0 / 40.9 |
| cooling_blockage | 3/3 | 57, 57, 57 | 0, 0, 0 | 116 | 0 | 2 (0), 2 (0), 2 (0) | 27.1 / 62.2 |
| check_ring_wear | 3/3 | 36, 36, 36 | 0, 0, 0 | 39 | 0 | 1 (0), 1 (0), 1 (0) | 0.4 / 0.6 |
| 단발성 경보 (shot 1934 쿠션량) | 3/3 | -, -, - | -, -, - | - | 0 | 2 (1), 2 (1), 2 (1) | 53.7 / 68.5 |

**진단까지 shot 분포** (미진단은 제외, 최소 / 중앙값 / 최대)

| 사례 | 진단 횟수 | 최소 | 중앙값 | 최대 |
|---|---|---|---|---|
| heater_degradation | 3/3 | 46 | 46 | 46 |
| pressure_sensor_spike | 3/3 | 7 | 7 | 7 |
| cooling_blockage | 3/3 | 57 | 57 | 57 |
| check_ring_wear | 3/3 | 36 | 36 | 36 |

- 정답 진단: **15/15** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 21회 (분석 21단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 143회
- 분석 1단계 소요: 평균 27.2초, 최소 0초, 최대 68초, 1분 초과 3/21단계
- 분석 1단계 토큰 평균: 입력 40304, 출력 1078, 생각 - (11단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 95% (수치 329개) | 100% (인용 12건) | - (보류 0건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125040632-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 97 | PASS | 실린더 온도 추이; 사출 압력 추이 |

**pressure_sensor_spike — 1회차** (`replay-1125040632-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 단발성 스파이크 재발 여부; 사출 압력 센서 커넥터 점검 후 신호 노이즈 안정화 여부 |

**cooling_blockage — 1회차** (`replay-1125040632-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 72 | PASS | moldTemp 5 shot 평균이 61.5℃ 이상으로 추가 상승하거나 관리 상한 이탈이 계속되는지; cycleTime 5 shot 평균이 30.2초 이상으로 증가하는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 100 | PASS | 금형 온도 추이; 사이클 타임 추이 |

**check_ring_wear — 1회차** (`replay-1125040632-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 90 | PASS | 쿠션량 추이; 보압 추이 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125040632-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 72 | PASS | 쿠션량 최근 5 shot 평균이 4.80 mm 이하로 하락하는 연속 추세 발생 여부; 사출 압력의 표준편차 증가 및 미성형(SHORT_SHOT) 불량 재발 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 88 | PASS | 쿠션량 5 shot 평균이 4.80 mm 이하로 하락하는 연속 추세 발생 여부; 차기 예방정비 시 체크 링 간극 및 마모 상태 점검 실측 |

**heater_degradation — 2회차** (`replay-1125040632-r2-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 97 | PASS | 실린더 온도 추이; 사출 압력 추이 |

**pressure_sensor_spike — 2회차** (`replay-1125040632-r2-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 단발성 스파이크 재발 여부; 사출 압력 5 shot 이동 평균 및 표준편차 추이; 모터 전류 및 쿠션량 등 연관 센서의 동반 변동 발생 여부 |

**cooling_blockage — 2회차** (`replay-1125040632-r2-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 70 | PASS | 금형 온도 5 shot 평균이 61.5℃ 이상으로 추가 상승하는지 여부; 사이클 타임의 지속적인 연장 추세 발생 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 100 | PASS | 금형 온도 추이; 사이클 타임 추이 |

**check_ring_wear — 2회차** (`replay-1125040632-r2-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 90 | PASS | 쿠션량 추이; 보압 추이 |

**단발성 경보 (shot 1934 쿠션량) — 2회차** (`replay-1125040632-r2-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 65 | PASS | 쿠션량 5 shot 평균이 기준값(5.0 mm) 대비 지속적으로 하락하거나 4.8 mm 미만으로 내려가는지; 사출 압력의 표준편차가 평소(약 7 bar) 대비 10 bar 이상으로 증가하는지; 미성형(SHORT_SHOT) 등 품질 불량이 추가로 발생하는지 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 체크 링 마모 주기 도래에 따른 쿠션량 5 shot 평균이 4.8 mm 미만으로 점진적 하강하는지 여부; 사출 압력 5 shot 표준편차가 평소(약 7 bar) 수준을 넘어 10 bar 이상으로 지속 확대되는지 여부 |

**heater_degradation — 3회차** (`replay-1125040632-r3-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 97 | PASS | 실린더 온도 추이; 사출 압력 추이 |

**pressure_sensor_spike — 3회차** (`replay-1125040632-r3-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 97 | PASS | 사출 압력 추이; 쿠션량 추이 |

**cooling_blockage — 3회차** (`replay-1125040632-r3-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 72 | PASS | 금형 온도 5 shot 평균의 61.5℃ 초과 지속 여부; 사이클 타임 추가 연장 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 100 | PASS | 금형 온도 추이; 사이클 타임 추이 |

**check_ring_wear — 3회차** (`replay-1125040632-r3-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 90 | PASS | 쿠션량 추이; 보압 추이 |

**단발성 경보 (shot 1934 쿠션량) — 3회차** (`replay-1125040632-r3-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 70 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 연속 하락하는지; 사출 압력 표준편차가 확대되는지; 미성형(SHORT_SHOT) 불량이 재발하는지 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 5 shot 평균이 4.8 mm 이하로 연속 하락하는지; 사출 압력 표준편차가 15 bar 이상으로 급증하는지 |
