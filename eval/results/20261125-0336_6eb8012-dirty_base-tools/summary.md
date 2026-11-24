## 시간 순 재생 평가 — 사례 5건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 47 | 0 | 68 | 0 | 2 (0) | 48.1 / 52.0 |
| pressure_sensor_spike | 1/1 | 7 | 0 | 구간 내 없음 | 0 | 1 (0) | 51.4 / 51.4 |
| cooling_blockage | 1/1 | 57 | 0 | 116 | 0 | 2 (0) | 53.6 / 68.0 |
| check_ring_wear | 1/1 | 39 | 1 | 39 | 0 | 2 (0) | 51.5 / 59.0 |
| 단발성 경보 (shot 1934 쿠션량) | 1/1 | - | - | - | 0 | 2 (1) | 63.4 / 77.7 |

- 정답 진단: **5/5** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 9회 (분석 9단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 79회
- 분석 1단계 소요: 평균 53.8초, 최소 39초, 최대 78초, 1분 초과 2/9단계
- 분석 1단계 토큰 평균: 입력 38659, 출력 1167, 생각 - (9단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 91% (수치 204개) | 100% (인용 9건) | - (보류 0건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125032809-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 68 | PASS | 실린더 온도의 최근 5 shot 평균이 227℃ 이하로 추가 하락하는지; 사출 압력 및 모터 전류의 상승 추세가 계속 이어지는지; 미성형(SHORT_SHOT) 불량이 발생하는지 |
| 2 | shot 347 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 저항 점검 및 조치 후 실린더 온도가 목표치(230℃)로 회복되는지; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)가 안정화되는지; 미성형(SHORT_SHOT) 불량 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1125032809-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 센서의 단발성 스파이크 재발 여부; 모터 전류 및 쿠션량 등 동반 센서의 변동 징후 발생 여부 |

**cooling_blockage — 1회차** (`replay-1125032809-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 75 | PASS | 금형 온도 5 shot 평균의 61.5℃ 초과 지속 상승 여부; 사이클 타임이 30.2s 이상으로 추가 지연되는지 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 사이클 타임 5 shot 평균 30.2 s 초과 지속 여부; 금형 온도 5 shot 평균 61.5℃ 초과 여부; 초물/중물 검사 시 제품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1125032809-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 60 | PASS | 쿠션량 5 shot 평균이 4.70 mm 이하로 추가 하락하거나 산포가 확대되는지; 사출 압력의 표준편차(stdev)가 15 bar 이상으로 급증하는지; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 분해 점검 시 마모 및 틈새 간극 실측치; 조치 후 쿠션량 평균이 관리 목표치(5.0 mm) 및 관리범위 내로 회복되는지; 사출 압력 표준편차가 10 bar 미만으로 안정화되는지 및 미성형 재발 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125032809-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 65 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 지속 하강하는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급증하는지 여부; 미성형(SHORT_SHOT) 불량이 추가 발생하는지 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 최근 5 shot 평균의 4.8 mm 이하 하강 여부; 사출 압력 5 shot 표준편차 15 bar 이상 급증 여부; 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |
