## 시간 순 재생 평가 — 사례 5건 × 3회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 3/3 | 43, 43, 46 | 0, 0, 0 | 68 | 0 | 1 (0), 1 (0), 2 (0) | 67.2 / 79.3 |
| pressure_sensor_spike | 3/3 | 7, 7, 7 | 0, 0, 0 | 구간 내 없음 | 0 | 1 (0), 1 (0), 1 (0) | 48.5 / 52.3 |
| cooling_blockage | 3/3 | 39, 39, 39 | 0, 0, 0 | 116 | 0 | 1 (0), 1 (0), 1 (0) | 60.7 / 63.8 |
| check_ring_wear | 3/3 | 37, 37, 37 | 0, 0, 0 | 39 | 0 | 1 (0), 1 (0), 1 (0) | 59.9 / 66.2 |
| 단발성 경보 (shot 1934 쿠션량) | 3/3 | -, -, - | -, -, - | - | 0 | 2 (1), 2 (1), 2 (1) | 67.6 / 80.7 |

**진단까지 shot 분포** (미진단은 제외, 최소 / 중앙값 / 최대)

| 사례 | 진단 횟수 | 최소 | 중앙값 | 최대 |
|---|---|---|---|---|
| heater_degradation | 3/3 | 43 | 43 | 46 |
| pressure_sensor_spike | 3/3 | 7 | 7 | 7 |
| cooling_blockage | 3/3 | 39 | 39 | 39 |
| check_ring_wear | 3/3 | 37 | 37 | 37 |

- 정답 진단: **15/15** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 19회 (분석 19단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 177회
- 분석 1단계 소요: 평균 62.2초, 최소 43초, 최대 81초, 1분 초과 11/19단계
- 분석 1단계 토큰 평균: 입력 49354, 출력 1185, 생각 - (19단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 94% (수치 416개) | 100% (인용 19건) | - (보류 0건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1124141936-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 343 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 온도가 227℃ 이하로 추가 하락하는지 여부; 사출 압력의 지속 상승 추이 및 미성형(SHORT_SHOT) 불량 발생 여부; 온도 조절기의 구역별 히터 출력률 상승 여부 |

**pressure_sensor_spike — 1회차** (`replay-1124141936-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 90 | PASS | 사출 압력 센서 값의 추가적인 이상 튐(스파이크) 발생 여부; 센서 커넥터 점검 및 재체결 후 사출 압력 Cpk의 1.33 이상 회복 여부 |

**cooling_blockage — 1회차** (`replay-1124141936-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 939 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도 5 shot 평균이 61℃ 이상 지속 유지되거나 규격 상한(66℃)에 근접하는지 여부; 사이클 타임 평균이 기준(30초) 대비 5% 이상 연장(31.5초 초과)되는지 여부; 향후 생산 로트에서 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1124141936-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1237 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 최근 5 shot 평균이 추가 하락(규격 하한 3.5 mm 접근)하는지 여부; 미성형(SHORT_SHOT) 및 중량 미달 불량 발생 여부; 사출 압력 및 보압 변동폭 확대 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1124141936-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 65 | PASS | 다음 20 shot 동안 쿠션량이 4.5 mm 미만으로 추가 하락하거나 산포가 커지는지 여부; 사출 압력 표준편차가 평소(7.2 bar 수준) 대비 급증하는지 여부; 미성형(SHORT_SHOT) 불량이 반복 발생하는지 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량이 4.5 mm 미만으로 재차 하락하거나 산포가 확대되는지 여부; 사출 압력 표준편차가 평소(7.2 bar 수준) 대비 10 bar 이상으로 급증하는지 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |

**heater_degradation — 2회차** (`replay-1124141936-r2-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 343 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 온도의 추가 하락 및 규격 하한(220℃) 접근 여부; 모터 전류 및 사출 압력의 추가 상승 지속 여부; 미성형(SHORT_SHOT) 불량 발생 여부 |

**pressure_sensor_spike — 2회차** (`replay-1124141936-r2-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 센서 후속 shot에서 간헐적 스파이크 재발 여부; 모터 전류 및 쿠션량 등 연관 공정 변수의 동반 변동 발생 여부 |

**cooling_blockage — 2회차** (`replay-1124141936-r2-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 939 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도(moldTemp) 5 shot 평균이 61℃ 이상으로 추가 상승하는지 여부; 사이클 타임(cycleTime)이 30.5초 이상으로 지연되는지 여부; 생산품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 2회차** (`replay-1124141936-r2-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1237 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 최근 5 shot 평균이 추가 하락하여 LSL 3.5 mm에 근접하는지 여부; 사출 압력 변동폭 확대 및 미성형(SHORT_SHOT) 불량 발생 여부; 계량 시간이 평소 대비 10% 이상 길어지는지 여부 |

**단발성 경보 (shot 1934 쿠션량) — 2회차** (`replay-1124141936-r2-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 65 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 지속 하락하거나 XBAR 경보가 발생하는지 여부; 사출 압력의 표준편차 증가 및 동반 흔들림 발생 여부; 미성형(SHORT_SHOT) 불량이 추가 발생하여 불량률이 상승하는지 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 5 shot 이동평균이 4.8 mm 이하로 하강하거나 XBAR 경보가 발생하는지 여부; 사출 압력 표준편차가 급증하거나 압력 변동폭이 커지는지 여부; 체크 링 권장 주기 도래에 따른 미성형(SHORT_SHOT) 불량 발생 여부 |

**heater_degradation — 3회차** (`replay-1124141936-r3-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 343 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 227.5℃ 이하로 지속 하락하는지 여부; 수지 점도 증가로 인한 사출 압력(injectionPressure) 1215 bar 초과 상승 여부; 모터 전류(motorCurrent)의 46 A 이상 상승 추세 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 346 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 온도 5 shot 평균의 추가 하락 및 규격 하한(LSL 220℃) 접근 여부; 사출 압력의 1215 bar 초과 지속 및 모터 전류의 46 A 초과 여부; 온도 하락에 따른 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |

**pressure_sensor_spike — 3회차** (`replay-1124141936-r3-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 센서 측정값에서 추가적인 스파이크 또는 신호 노이즈가 재발하는지 확인; 쿠션량 및 모터 전류 등 사출 관련 센서의 동반 변동 발생 여부 관찰 |

**cooling_blockage — 3회차** (`replay-1124141936-r3-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 939 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 82 | PASS | 금형 온도 5 shot 평균의 61.5 ℃ 초과 여부; 사이클 타임(cycleTime)의 지속 상승 추세 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 3회차** (`replay-1124141936-r3-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1237 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 80 | PASS | 쿠션량 5 shot 평균이 4.5 mm 이하 또는 규격 하한(3.5 mm)으로 추가 하락하는지; 사출 압력의 변동폭(표준편차)이 급증하는지; 미성형(SHORT_SHOT) 등 외관 불량이 발생하는지 |

**단발성 경보 (shot 1934 쿠션량) — 3회차** (`replay-1124141936-r3-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 연속 하락하는지 여부; 사출 압력 변동폭 확대 및 계량 시간 증가 여부; XBAR 경보 발생 또는 미성형(SHORT_SHOT) 불량 재발 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 5 shot 이동평균이 4.8 mm 이하로 하락하거나 편차가 확대되는지 여부; 차기 설비 정지 시 SOP-INJ-012에 따른 쿠션 유지 시험 결과 |
