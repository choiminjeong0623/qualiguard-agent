## 시간 순 재생 평가 — 사례 6건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 81 | 1 | 39 | 0 | 4 (0) | 127.7 / 185.3 |
| pressure_sensor_spike | 1/1 | 76 | 0 | 122 | 0 | 1 (0) | 49.4 / 49.4 |
| cooling_blockage | 1/1 | 61 | 0 | 63 | 0 | 3 (0) | 62.7 / 63.9 |
| check_ring_wear | 1/1 | 51 | 0 | 55 | 0 | 3 (1) | 78.4 / 90.1 |
| 단발성 경보 (shot 904 실린더 온도) | 1/1 | - | - | - | 0 | 4 (3) | 56.3 / 68.4 |
| 단발성 경보 (shot 1991 실린더 온도) | 1/1 | - | - | - | 0 | 1 (0) | 49.4 / 49.4 |

- 정답 진단: **6/6** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 2건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 16회 (분석 16단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 168회
- 분석 1단계 소요: 평균 78.6초, 최소 44초, 최대 185초, 1분 초과 11/16단계
- 분석 1단계 토큰 평균: 입력 42363, 출력 1244, 생각 - (14단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 67% | 100% | 92% (수치 337개) | 100% (인용 12건) | 0% (보류 1건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1124161828-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 363 XBAR cycleTime | FAILED | None | - | - | - |
| 2 | shot 363 XBAR cycleTime | FAILED | None | - | - | - |
| 3 | shot 363 XBAR cycleTime | OBSERVE | MATERIAL | 65 | PASS | 사이클 타임 5 shot 평균이 관리 하한(29.91 s) 이하로 지속 유지되는지 확인; 쿠션량(최근 평균 5.084 mm)의 추가 상승 및 편차 확대 여부 모니터링; 차기 구간에서 버(FLASH) 불량의 추가 발생 여부 추적 |
| 4 | shot 381 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 온도 5 shot 평균의 추가 하락(228.0℃ 미만 진입) 여부; 사출 압력(1208 bar 초과) 및 모터 전류의 추가 상승 추세 지속 여부; 온도 하락에 따른 미성형(SHORT_SHOT) 등 품질 불량 신규 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1124161828-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 476 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 88 | PASS | 사출 압력 단발성 튐 재발 여부 및 발생 주기; 사출 압력 연속 2 shot 이상 관리 한계 이탈 여부; 쿠션량(기준 5mm) 및 보압의 동반 변동 발생 여부 |

**cooling_blockage — 1회차** (`replay-1124161828-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 936 INDIVIDUAL cycleTime | OBSERVE | NOISE | 75 | PASS | 사이클 타임이 관리 상한(30.56 s)을 연속 이탈하거나 31.5 s 이상으로 지연되는지; 금형 온도(moldTemp)가 기준(60 ℃) 대비 상승하는지; 치수(DIMENSION) 또는 미성형(SHORT_SHOT) 등 품질 불량이 발생하는지 |
| 2 | shot 947 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 55 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 관리 하한(229.66 ℃) 이하로 재하락하는지; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 추세가 나타나는지; 미성형(SHORT_SHOT) 등 외관 및 치수 불량 발생 여부 |
| 3 | shot 961 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도(moldTemp) 5 shot 평균이 61.5 ℃를 초과하여 추가 상승하는지; 사이클 타임(cycleTime) 평균이 30.5 s 이상으로 추가 지연되는지; 942 shot 이후 생산 로트에서 치수(DIMENSION) 규격 이탈 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1124161828-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1207 XBAR cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균의 관리 하한(29.91 s) 미만 지속 여부 및 추가 하락 추이; 실린더 온도(barrelTemp)의 231 ℃ 초과 상승 여부; 휨(WARPAGE) 불량의 연속 발생 여부 및 불량률 변동 |
| 2 | shot 1247 재확인 | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균이 관리 하한(29.91 s) 미만으로 재하락하는지 여부; 휨(WARPAGE) 및 치수(DIMENSION) 불량 재발 여부; 금형 온도(moldTemp)의 관리 상한(66 ℃) 근접 변동 여부 |
| 3 | shot 1251 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 78 | PASS | 쿠션량 5 shot 평균의 추가 하락 및 관리 하한(4.64 mm) 연속 이탈 여부; 사출 압력(injectionPressure)의 산포 증가 및 압력 변동폭 확대 여부; 초품 및 양산품 외관 검사 시 미성형(SHORT_SHOT) 불량 발생 여부 |

**단발성 경보 (shot 904 실린더 온도) — 1회차** (`replay-1124161828-r1-noise-IMM-01-904`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 904 INDIVIDUAL barrelTemp | OBSERVE ✅ | NOISE | 85 | PASS | 실린더 온도의 추가 관리 한계(232.68 ℃) 이탈 및 연속 이탈(XBAR 경보) 발생 여부; 실린더 온도가 SOP-INJ-001 기준 허용치(±5 ℃)를 벗어나는지 확인; 사출 압력 및 모터 전류 센서의 동반 변동 여부 |
| 2 | shot 944 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 실린더 온도의 추가 관리 한계(232.68 ℃) 이탈 및 XBAR 경보 발생 여부; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)의 동반 상승 여부; 미성형(SHORT_SHOT) 등 품질 불량 추가 발생 여부 |
| 3 | shot 984 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 실린더 온도의 추가 관리 한계(232.68 ℃) 이탈 및 5 shot 평균 관리 한계(XBAR) 경보 발생 여부; 미성형(SHORT_SHOT) 및 치수(DIMENSION) 불량이 연속 발생하여 구간 불량률이 관리 한계(5%)를 초과하는지 여부; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)의 지속적인 동반 상승 추세 발생 여부 |
| 4 | shot 1024 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 실린더 온도의 관리 한계(232.68 ℃) 초과 및 5 shot 평균 관리 한계(XBAR) 경보 발생 여부; 사출 압력(기준 1200 bar) 및 모터 전류(기준 45 A)의 지속적인 동반 상승 추세 발생 여부; 미성형(SHORT_SHOT) 등 품질 불량 추가 발생 여부 |

**단발성 경보 (shot 1991 실린더 온도) — 1회차** (`replay-1124161828-r1-noise-IMM-02-1991`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1991 INDIVIDUAL barrelTemp | OBSERVE ✅ | NOISE | 85 | PASS | 실린더 온도가 다음 5 shot 동안 관리 범위(227.60~233.81℃) 내로 안정적으로 복귀하는지; 사출 압력 및 모터 전류의 지속적인 상승 추세가 나타나는지; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
