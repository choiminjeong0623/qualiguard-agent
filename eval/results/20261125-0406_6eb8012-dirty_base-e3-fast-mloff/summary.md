## 시간 순 재생 평가 — 사례 5건 × 3회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 3/3 | 47, 47, 47 | 0, 0, 0 | 68 | 0 | 2 (0), 2 (0), 2 (0) | 55.2 / 65.6 |
| pressure_sensor_spike | 3/3 | 7, 7, 7 | 0, 0, 0 | 구간 내 없음 | 0 | 1 (0), 1 (0), 1 (0) | 54.0 / 66.2 |
| cooling_blockage | 3/3 | 57, 57, 57 | 0, 0, 0 | 116 | 0 | 2 (0), 2 (0), 2 (0) | 51.3 / 56.2 |
| check_ring_wear | 3/3 | 39, 39, 39 | 1, 1, 1 | 39 | 0 | 2 (0), 2 (0), 2 (0) | 51.1 / 55.5 |
| 단발성 경보 (shot 1934 쿠션량) | 3/3 | -, -, - | -, -, - | - | 0 | 2 (1), 2 (1), 2 (1) | 52.5 / 64.5 |

**진단까지 shot 분포** (미진단은 제외, 최소 / 중앙값 / 최대)

| 사례 | 진단 횟수 | 최소 | 중앙값 | 최대 |
|---|---|---|---|---|
| heater_degradation | 3/3 | 47 | 47 | 47 |
| pressure_sensor_spike | 3/3 | 7 | 7 | 7 |
| cooling_blockage | 3/3 | 57 | 57 | 57 |
| check_ring_wear | 3/3 | 39 | 39 | 39 |

- 정답 진단: **15/15** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 27회 (분석 27단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 234회
- 분석 1단계 소요: 평균 52.7초, 최소 44초, 최대 66초, 1분 초과 4/27단계
- 분석 1단계 토큰 평균: 입력 34640, 출력 1156, 생각 - (27단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 94% (수치 634개) | 100% (인용 27건) | - (보류 0건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125034142-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 70 | PASS | 실린더 온도 최근 5 shot 평균이 227℃ 이하로 지속 하락하거나 XBAR 관리 한계를 이탈하는지; 사출 압력 최근 5 shot 평균이 1210 bar를 초과하여 추가 상승하는지; 미성형(SHORT_SHOT) 등 외관 및 치수 불량이 발생하는지 |
| 2 | shot 347 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검/교체 후 실린더 온도가 설정치(230℃)로 회복되는지; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)가 안정화되는지; 온도 하락 구간(shot 328~347) 생산품에서 미성형(SHORT_SHOT) 불량이 발생하는지 |

**pressure_sensor_spike — 1회차** (`replay-1125034142-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 간헐적 스파이크 재발 여부; 사출 압력 급변 시 모터 전류 및 쿠션량의 동반 변화 여부 |

**cooling_blockage — 1회차** (`replay-1125034142-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 70 | PASS | 금형 온도(moldTemp) 5 shot 평균의 61.5℃ 초과 상승 지속 여부; 사이클 타임(cycleTime)의 추가 연장 추세 및 30.5초 초과 여부; 성형품 치수(DIMENSION) 공차 이탈 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 회로별 냉각수 유량 및 입출구 온도차 측정 결과; 금형 온도 61.5℃ 초과 지속 여부 및 사이클 타임 30.3s 초과 여부; 성형품 치수(DIMENSION) 공차 이탈 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1125034142-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 65 | PASS | 쿠션량 5 shot 평균이 4.5 mm 이하로 추가 하락하는지 여부; 사출 압력 구간 표준편차가 15 bar 이상으로 확대되는지 여부; 미성형(SHORT_SHOT) 불량 발생 여부 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 분해 점검 시 마모 측정값 및 교체 여부; 정비 후 쿠션량 최근 5 shot 평균의 기준치(5.0 mm) 회복 여부; 미성형(SHORT_SHOT) 불량 재발 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125034142-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.70 mm 이하로 하향 추세를 보이는지; 사출 압력의 표준편차가 10 bar 이상으로 확대되는지; 미성형(SHORT_SHOT) 불량이 추가 발생하는지 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 체크 링 교체 주기 도래에 따른 쿠션량 5 shot 평균 하향 추세(4.70 mm 이하) 모니터링; 사출 압력 5 shot 표준편차 10 bar 초과 여부 지속 확인 |

**heater_degradation — 2회차** (`replay-1125034142-r2-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 70 | PASS | 실린더 온도 최근 5 shot 평균이 227.0℃ 이하로 추가 하락하거나 XBAR 경보가 발생하는지 확인; 사출 압력과 모터 전류의 지속적인 상승세 및 미성형(SHORT_SHOT) 불량 발생 여부 감시 |
| 2 | shot 347 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 전까지 실린더 온도의 225℃ 이하 추가 하락 여부; 미성형(SHORT_SHOT) 불량 발생 여부 및 사출 압력 1220 bar 초과 여부 |

**pressure_sensor_spike — 2회차** (`replay-1125034142-r2-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 단발성 스파이크 재발 여부; 사출 압력 센서 커넥터 점검 후 5 shot 평균(기준 1200 bar) 및 산포 안정성 유지 여부 |

**cooling_blockage — 2회차** (`replay-1125034142-r2-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 72 | PASS | 금형 온도 최근 5 shot 평균의 61.5℃ 초과 지속 상승 여부; 사이클 타임의 30.2초 초과 연장 여부; 치수(DIMENSION) 또는 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 냉각 채널 세척 후 금형 온도가 기준치(60℃)로 회복되는지 확인; 사이클 타임 5 shot 평균이 관리 범위(29.61~30.09 s) 내로 복귀하는지 점검; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 지속 모니터링 |

**check_ring_wear — 2회차** (`replay-1125034142-r2-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 60 | PASS | 쿠션량 5 shot 평균이 4.7 mm 이하로 추가 하락하거나 산포가 확대되는지 확인; 사출 압력의 표준편차가 15 bar 이상으로 급격히 증가하는지 확인; 미성형(SHORT_SHOT) 불량이 발생하는지 모니터링 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 교체 후 쿠션량 관리 범위(4.64~5.28 mm) 및 기준값(5.0 mm) 정상 복귀 여부; 사출 압력 표준편차 10 bar 이하로 안정화 여부; 추가 미성형(SHORT_SHOT) 불량 발생 여부 |

**단발성 경보 (shot 1934 쿠션량) — 2회차** (`replay-1125034142-r2-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 65 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 연속 하락하는지; 사출 압력 산포(표준편차)가 7.2 bar 대비 2배 이상 커지는지; 미성형(SHORT_SHOT) 발생 빈도가 증가하는지 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 80 | PASS | 쿠션량 5 shot 이동평균이 4.8 mm 이하로 재하락하는지; 사출 압력 5 shot 표준편차가 14 bar 이상으로 확대되는지 |

**heater_degradation — 3회차** (`replay-1125034142-r3-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 70 | PASS | 실린더 온도가 관리 하한(226.25℃) 이하로 연속 이탈하거나 226.0℃ 미만으로 추가 하락하는지 여부; 사출 압력(최근 5 shot 평균 1208.75 bar) 및 모터 전류(최근 5 shot 평균 45.21 A)의 상승세 지속 여부; 차기 검사 구간에서 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |
| 2 | shot 347 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 점검 및 부품 교체 후 실린더 온도 5 shot 평균이 기준치(230℃)로 복귀하는지 여부; 사출 압력(1200 bar) 및 모터 전류(45 A)의 정상 기준선 안정화 여부; 차기 검사 구간에서 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |

**pressure_sensor_spike — 3회차** (`replay-1125034142-r3-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 단발 스파이크 재발 여부; 센서 커넥터 재체결 후 사출 압력 측정값 안정화 여부; 모터 전류 및 쿠션량 등 타 센서의 동반 변화 발생 여부 |

**cooling_blockage — 3회차** (`replay-1125034142-r3-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 75 | PASS | 금형 온도(moldTemp) 5 shot 평균의 61.5℃ 초과 지속 여부; 사이클 타임(cycleTime)의 30.5s 초과 지속 여부; 치수(DIMENSION) 불량 또는 휨 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 냉각 계통 조치 후 금형 온도(moldTemp) 60±1℃ 및 사이클 타임(cycleTime) 30.0s 복귀 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 전수 검사 |

**check_ring_wear — 3회차** (`replay-1125034142-r3-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 60 | PASS | 쿠션량 5 shot 평균이 4.70 mm 이하로 추가 하락하거나 편차가 확대되는지 여부; 사출 압력 표준편차 상승 여부 및 미성형(SHORT_SHOT) 발생 여부; 위치 센서 신호 접촉 및 측정 계통 이상 징후 발생 여부 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 분해 점검 시 마모 및 유격 실측값 확인; 부품 교체 후 쿠션량 기준값(5.0 mm) 회복 및 편차 안정화 여부; 사출 압력 표준편차 안정화(10 bar 이하) 및 미성형 불량 재발 여부 |

**단발성 경보 (shot 1934 쿠션량) — 3회차** (`replay-1125034142-r3-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 70 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 하락하는지 여부; 사출 압력 표준편차 및 변동폭 확대 여부; 미성형(SHORT_SHOT) 불량 재발 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 5 shot 이동평균의 4.8 mm 이하 지속 하락 여부; 사출 압력 구간 표준편차의 비정상적 확대(15 bar 초과) 여부; 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |
