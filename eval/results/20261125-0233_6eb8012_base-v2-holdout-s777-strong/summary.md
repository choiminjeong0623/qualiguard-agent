## 시간 순 재생 평가 — 사례 11건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 63 | 0 | 73 | 0 | 5 (0) | 91.3 / 183.1 |
| pressure_sensor_spike | 1/1 | 16 | 0 | 구간 내 없음 | 0 | 1 (0) | 41.2 / 41.2 |
| cooling_blockage | 1/1 | 48 | 0 | 78 | 0 | 4 (1) | 57.3 / 69.3 |
| check_ring_wear | 0/1 | 미진단 | - | 48 | 0 | 5 (0) | 69.5 / 80.9 |
| 단발성 경보 (shot 128 금형 온도) | 1/1 | - | - | - | 0 | 4 (3) | 64.2 / 86.3 |
| 단발성 경보 (shot 965 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 47.0 / 58.9 |
| 단발성 경보 (shot 1021 금형 온도) | 1/1 | - | - | - | 0 | 4 (3) | 46.7 / 54.6 |
| 단발성 경보 (shot 1069 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 53.4 / 76.8 |
| 단발성 경보 (shot 1941 사이클 타임) | 1/1 | - | - | - | 0 | 2 (1) | 56.1 / 57.3 |
| 단발성 경보 (shot 58 실린더 온도) | 1/1 | - | - | - | 0 | 4 (3) | 46.2 / 52.2 |
| 단발성 경보 (shot 271 실린더 온도) | 1/1 | - | - | - | 0 | 4 (3) | 57.3 / 79.4 |

- 정답 진단: **10/11** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 1건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 41회 (분석 41단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 374회
- 분석 1단계 소요: 평균 59.7초, 최소 39초, 최대 183초, 1분 초과 13/41단계
- 분석 1단계 토큰 평균: 입력 46999, 출력 1154, 생각 - (40단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 73% | 100% | 94% (수치 930개) | 100% (인용 31건) | 0% (보류 6건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125015156-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 304 XBAR moldTemp | OBSERVE | UNKNOWN | 45 | PASS | 금형 온도(moldTemp) 5 shot 평균이 61.5℃ 이상으로 상승 추세를 지속하는지; 사이클 타임(cycleTime)이 30.5초 이상으로 지연되는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량이 발생하는지 |
| 2 | shot 318 XBAR cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임(cycleTime) 5 shot 평균이 29.5 s 이하로 추가 하락하는지; 금형 온도(moldTemp)가 61.5℃ 이상으로 재상승하는지; 미성형(SHORT_SHOT) 또는 치수(DIMENSION) 불량이 새로 발생하는지 |
| 3 | shot 340 XBAR barrelTemp | FAILED | None | - | - | - |
| 4 | shot 340 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 70 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 228.0℃ 이하로 추가 하락하는지; 모터 전류(motorCurrent) 5 shot 평균이 46.0A 이상으로 계속 상승하는지; 미성형(SHORT_SHOT) 등 외관 불량이 발생하는지 |
| 5 | shot 363 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 저항 측정 및 점검 조치 후 실린더 온도 5 shot 평균이 기준(230℃)으로 복귀하는지; 사출 압력 5 shot 평균이 1200bar 수준으로 안정화되는지; 선별 검사 중 미성형(SHORT_SHOT) 불량이 검출되는지 |

**pressure_sensor_spike — 1회차** (`replay-1125015156-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 416 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 추가적인 간헐적 급락 또는 튐 현상 재발 여부; 사출 압력 변동 시 쿠션량이나 모터 전류의 동반 변동 발생 여부 |

**cooling_blockage — 1회차** (`replay-1125015156-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 900 INDIVIDUAL barrelTemp | OBSERVE | NOISE | 75 | PASS | 실린더 온도가 연속으로 관리 한계를 이탈하거나 평균 이동(XBAR 경보)이 발생하는지; 사출 압력 및 모터 전류가 동반 상승하는지 여부 |
| 2 | shot 940 재확인 | OBSERVE | NOISE | 85 | PASS | 실린더 온도 5 shot 이동평균(XBAR)의 하락 추세 발생 여부; 사출 압력 및 모터 전류의 동반 상승 여부 |
| 3 | shot 946 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 75 | PASS | 금형 온도 5 shot 평균이 61.2℃ 이상으로 계속 상승하는지 여부; 사이클 타임의 추가 연장 추이; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 4 | shot 948 INDIVIDUAL moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 냉각 계통 점검 및 조치 후 금형 온도(목표 60℃) 및 사이클 타임(목표 30s)의 정상 복귀 여부; 금형 온도 상승 구간(shot 909~948) 생산품에 대한 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 정밀 전수 검사 |

**check_ring_wear — 1회차** (`replay-1125015156-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1203 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 55 | PASS | 금형 온도 5 shot 평균이 61.0℃를 초과하여 추가 상승하는지 여부; 사이클 타임이 30.5초 이상으로 연장되며 냉각 지연 징후가 나타나는지 여부; 치수(DIMENSION) 또는 휨(WARPAGE) 불량이 추가 발생하여 불량률이 증가하는지 여부 |
| 2 | shot 1228 XBAR cycleTime | OBSERVE | COOLING_ISSUE | 50 | PASS | 사이클 타임 5 shot 평균이 29.8 s 이하로 추가 하락하거나 30.5 s 이상으로 연장되는지 여부; 금형 온도 5 shot 평균이 61.5 ℃를 초과하여 추가 상승하는지 여부; 치수(DIMENSION) 또는 휨(WARPAGE) 불량이 새로 발생하는지 여부 |
| 3 | shot 1240 INDIVIDUAL moldTemp | OBSERVE | COOLING_ISSUE | 45 | PASS | 금형 온도 측정값이 연속 2 shot 이상 62.0 ℃를 초과하여 유지되는지 여부; 사이클 타임이 30.5 s 이상으로 연장되며 냉각 지연 현상이 동반되는지 여부; 치수(DIMENSION) 또는 휨(WARPAGE) 불량이 발생하는지 여부 |
| 4 | shot 1242 INDIVIDUAL cushion | OBSERVE | COOLING_ISSUE | 40 | PASS | 쿠션량 측정값이 4.60 mm 이하로 연속 발생하거나 하락 추세가 형성되는지 여부; 사출 압력의 표준편차가 15 bar 이상으로 급증하거나 변동폭이 확대되는지 여부; 미성형(SHORT_SHOT) 또는 치수(DIMENSION) 불량이 새로 발생하는지 여부 |
| 5 | shot 1250 INDIVIDUAL injectionPressure | OBSERVE | COOLING_ISSUE | 35 | PASS | 사출 압력 측정값이 1230 bar 이상으로 연속 발생하거나 표준편차가 15 bar 이상으로 급증하는지 여부; 쿠션량 측정값이 4.60 mm 이하로 하락 추세를 형성하는지 여부; 미성형(SHORT_SHOT) 또는 치수(DIMENSION) 불량이 추가 발생하는지 여부 |

**단발성 경보 (shot 128 금형 온도) — 1회차** (`replay-1125015156-r1-noise-IMM-01-128`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 128 INDIVIDUAL moldTemp | OBSERVE ✅ | NOISE | 70 | PASS | 금형 온도(moldTemp)의 개별값 관리 한계(62.17℃) 재이탈 또는 5 shot 평균 상승 여부; 사이클 타임(cycleTime)이 냉각 지연으로 인해 증가(30.5s 초과)하는지 여부; 이후 shot에서 미성형 또는 치수 관련 추가 불량 발생 여부 |
| 2 | shot 168 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 금형 온도(moldTemp)가 다시 개별값 관리 한계(62.17℃)를 벗어나거나 5 shot 평균이 지속 상승하는지 여부; 사이클 타임(cycleTime)이 냉각 이상으로 인해 30.5s를 초과하는지 여부; 치수 및 휨 등 냉각 계통 연관 불량 발생 여부 |
| 3 | shot 208 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 금형 온도(moldTemp) 5 shot 이동평균이 61.5℃를 초과하여 지속 상승하는지 여부; 사이클 타임(cycleTime)이 30.5s를 초과하여 냉각 지연 패턴을 보이는지 여부 |
| 4 | shot 248 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 미성형(SHORT_SHOT) 불량의 추가 발생 여부 및 불량률 추이; 체크 링 마모 징후인 쿠션량(cushion) 4.8mm 이하 하락 및 사출 압력 변동폭 확대 여부; 금형 온도(moldTemp) 5 shot 이동평균의 관리 한계 재이탈 여부 |

**단발성 경보 (shot 965 사이클 타임) — 1회차** (`replay-1125015156-r1-noise-IMM-01-965`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 965 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임이 연속으로 관리 한계를 벗어나거나 5 shot 평균 이동이 발생하는지 확인; 취출 동작 및 금형 개폐 시간 등 기계 동작 시간의 추가 편차 여부 |
| 2 | shot 1005 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임의 개별값 또는 XBAR 관리 한계 재이탈 여부; 취출 로봇 및 금형 개폐 기계 동작 시간 편차 발생 여부 |
| 3 | shot 1045 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임의 개별값 또는 XBAR 관리 한계 재이탈 여부; 취출 로봇 및 금형 개폐 기계 구동부 동작 시간의 간헐적 편차 발생 여부 |
| 4 | shot 1085 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임의 개별값 및 XBAR 관리 한계 재이탈 여부; 취출 로봇 동작 시간 및 금형 개폐 구동 시간의 간헐적 편차 발생 여부 |

**단발성 경보 (shot 1021 금형 온도) — 1회차** (`replay-1125015156-r1-noise-IMM-01-1021`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1021 INDIVIDUAL moldTemp | OBSERVE ✅ | NOISE | 65 | PASS | 금형 온도(moldTemp)가 연속 2회 이상 관리 상한(62.17 ℃)을 이탈하거나 평균 이동이 발생하는지; 사이클 타임(cycleTime)이 냉각 지연으로 인해 증가 추세를 보이는지; 치수(DIMENSION) 등 품질 불량이 발생하는지 |
| 2 | shot 1061 재확인 | OBSERVE ✅ | NOISE | 75 | PASS | 금형 온도(moldTemp)가 다시 관리 상한(62.17 ℃)에 근접하거나 초과하는지; 치수(DIMENSION) 불량이 연속 또는 반복 발생하는지 여부; 냉각수 유량 및 사이클 타임(cycleTime)의 미세 증가 추세 발생 여부 |
| 3 | shot 1101 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 금형 온도(moldTemp)가 관리 상한(62.17 ℃)을 재이탈하는지; 치수(DIMENSION) 등 품질 불량이 재발하는지; 사이클 타임(cycleTime)이 30.5 s 이상으로 지속 지연되는지 |
| 4 | shot 1141 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 금형 온도(moldTemp)가 관리 상한(62.17 ℃)을 재이탈하거나 상승 추세를 보이는지 여부; 치수(DIMENSION) 등 제품 외곽 품질 불량의 재발 여부; 사이클 타임(cycleTime)이 30.5 s 이상으로 지연되는지 여부 |

**단발성 경보 (shot 1069 사이클 타임) — 1회차** (`replay-1125015156-r1-noise-IMM-01-1069`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1069 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 75 | PASS | 사이클 타임 최근 5 shot 평균이 관리 범위(29.53~30.69 s) 내에서 안정적으로 유지되는지; 치수 불량(DIMENSION)의 추가 발생 여부; 금형 온도 최근 5 shot 평균이 기준값(60℃) 대비 안정적으로 유지되는지 |
| 2 | shot 1109 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 최근 5 shot 평균이 관리 범위(29.53~30.69 s) 내에서 안정적으로 유지되는지; 치수(DIMENSION) 등 추가 제품 품질 불량 발생 여부 |
| 3 | shot 1149 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 관리 범위(29.53~30.69 s) 이탈 경보 재발 여부; 치수(DIMENSION) 등 제품 품질 불량 발생 여부 |
| 4 | shot 1189 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 관리 범위(29.53~30.69 s) 이탈 경보 재발 여부; 치수(DIMENSION) 등 추가 불량 발생 여부 및 샘플 치수 측정 결과; 금형 온도 평균(60.68℃) 및 쿠션량(5.05 mm)의 안정적 유지 여부 |

**단발성 경보 (shot 1941 사이클 타임) — 1회차** (`replay-1125015156-r1-noise-IMM-01-1941`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1941 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 최근 5 shot 평균이 기준 관리 범위(29.53~30.69 s) 내에서 안정적으로 유지되는지; moldTemp 및 cushion 센서에서 지속적인 추세 이동이 발생하는지 여부 |
| 2 | shot 1981 재확인 | OBSERVE ✅ | NOISE | 92 | PASS | 사이클 타임 최근 5 shot 평균이 관리 범위(29.53~30.69 s) 내에서 유지되는지 여부; 금형 온도(moldTemp) 및 쿠션량(cushion)의 추세 이동 발생 여부 |

**단발성 경보 (shot 58 실린더 온도) — 1회차** (`replay-1125015156-r1-noise-IMM-02-58`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 58 INDIVIDUAL barrelTemp | OBSERVE ✅ | NOISE | 75 | PASS | 실린더 온도 최근 5 shot 평균이 228℃ 이하로 지속 하락하는지 여부; 사출 압력 및 모터 전류의 동반 상승 추세 발생 여부; 미성형(SHORT_SHOT) 불량 발생 여부 |
| 2 | shot 98 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 실린더 온도 개별값 또는 평균의 관리 한계 재이탈 여부 모니터링; 사출 압력 및 모터 전류의 급격한 변동 발생 여부 확인 |
| 3 | shot 138 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 실린더 온도 개별값 또는 평균의 관리 한계 재이탈 여부 모니터링; 사출 압력 및 모터 전류의 급격한 변동 발생 여부 확인 |
| 4 | shot 178 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 실린더 온도 개별값 또는 평균의 관리 한계 재이탈 여부 일상 모니터링; 사출 압력 및 모터 전류의 급격한 변동 발생 여부 확인 |

**단발성 경보 (shot 271 실린더 온도) — 1회차** (`replay-1125015156-r1-noise-IMM-02-271`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 271 INDIVIDUAL barrelTemp | OBSERVE ✅ | NOISE | 80 | PASS | 다음 10~20 shot 동안 실린더 온도가 228 ℃ 이하로 지속 하락하는지 여부; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)의 동반 상승 추세 발생 여부 |
| 2 | shot 311 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 실린더 온도가 관리 하한(227.60℃) 이하로 재이탈하는지 여부; 사출 압력 및 모터 전류의 장기 추세 변동 여부 |
| 3 | shot 351 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 실린더 온도 최근 5 shot 평균이 관리 하한(227.60 ℃) 이하로 재이탈하는지 여부; 휨 불량이 후속 구간에서 연속 발생하거나 금형 온도 편차가 증가하는지 여부 |
| 4 | shot 391 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 실린더 온도의 관리 범위(227.60~233.81 ℃) 이탈 재발 여부; 사출 압력 및 모터 전류의 장기 추세 변동 여부 |
