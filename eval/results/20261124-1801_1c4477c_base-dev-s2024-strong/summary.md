## 시간 순 재생 평가 — 사례 7건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 61 | 0 | 123 | 0 | 1 (0) | 63.1 / 63.1 |
| pressure_sensor_spike | 1/1 | 100 | 1 | 23 | 0 | 4 (1) | 70.7 / 90.2 |
| cooling_blockage | 1/1 | 37 | 0 | 126 | 0 | 2 (0) | 64.9 / 76.0 |
| check_ring_wear | 1/1 | 24 | 0 | 113 | 0 | 1 (0) | 71.6 / 71.6 |
| sensor_drift | 0/1 | 미진단 | - | 85 | 2 | 5 (1) | 87.6 / 182.4 |
| 단발성 경보 (shot 1922 사이클 타임) | 1/1 | - | - | - | 0 | 2 (1) | 65.9 / 76.2 |
| 단발성 경보 (shot 141 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 56.0 / 67.3 |

- 정답 진단: **6/7** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 2건, 실패(FAILED): 1건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 19회 (분석 19단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 178회
- 분석 1단계 소요: 평균 70.6초, 최소 49초, 최대 182초, 1분 초과 12/19단계
- 분석 1단계 토큰 평균: 입력 44313, 출력 1170, 생각 - (18단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 86% | 86% | 92% (수치 390개) | 100% (인용 17건) | 0% (보류 1건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1124173749-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 361 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 온도 5 shot 평균이 227.5℃ 이하로 추가 하강하는지 여부; 사출 압력 5 shot 평균의 1210 bar 초과 상승 여부; 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1124173749-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 418 XBAR cycleTime | OBSERVE | COOLING_ISSUE | 45 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s 이상으로 추가 연장되는지; 금형 온도(moldTemp)가 목표치 60 ℃ 대비 61 ℃ 이상으로 상승하는 추세가 나타나는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량이 발생하는지 |
| 2 | shot 424 INDIVIDUAL injectionPressure | OBSERVE | NOISE | 55 | PASS | 다음 5~10 shot에서 사출 압력이 목표치 1200 bar(관리 범위 1166.37~1237.99 bar)로 복귀하는지; 미성형(SHORT_SHOT) 불량이 추가 shot에서 재발하는지; 쿠션량(cushion) 및 모터 전류(motorCurrent) 등 관련 센서의 동반 변동 발생 여부 |
| 3 | shot 464 재확인 | OBSERVE | NOISE | 85 | PASS | 사출 압력 5 shot 평균이 관리 범위(1166.37~1237.99 bar) 내에서 지속 유지되는지; 사출 압력의 단발성 스파이크(±50 bar 이상 변동) 재발 여부 |
| 4 | shot 500 S_CHART injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 90 | PASS | 사출 압력 센서 커넥터 점검 및 조치 후 단발성 스파이크(±50 bar 이상) 재발 여부; 쿠션량(cushion) 및 보압(holdingPressure) 등 관련 공정 센서의 동반 변동 발생 여부 |

**cooling_blockage — 1회차** (`replay-1124173749-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 925 XBAR cycleTime | OBSERVE | COOLING_ISSUE | 65 | PASS | 사이클 타임 5 shot 평균이 30.5 s를 초과하여 계속 연장되는지; 금형 온도가 61.0 ℃ 이상으로 지속 상승하는지; 치수(DIMENSION) 또는 휨(WARPAGE) 불량이 발생하는지 |
| 2 | shot 937 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 냉각수 유량계 수치(정상 8~12 L/min 범위 미달 여부) 및 입·출구 온도차; 금형 온도 5 shot 평균이 61.5 ℃를 초과하여 추가 상승하는지; 치수(DIMENSION) 또는 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1124173749-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1224 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 5 shot 평균이 4.7 mm 이하로 추가 하락하거나 규격 하한(3.5 mm)에 근접하는지 여부; 사출 압력의 산포(표준편차) 증가 및 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |

**sensor_drift — 1회차** (`replay-1124173749-r1-sensor_drift`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1585 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도(barrelTemp) 5 shot 평균의 지속 하락 및 228℃ 이하 진입 여부; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 상승 동반 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1613 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 70 | PASS | 실린더 온도(barrelTemp) 5 shot 평균의 228.0℃ 지속 하회 여부; 사출 압력(injectionPressure) 1215 bar 초과 또는 모터 전류(motorCurrent) 46.5 A 초과 동반 상승 여부; 미성형(SHORT_SHOT) 등 제품 외관 불량 발생 여부 |
| 3 | shot 1653 재확인 | FAILED | None | - | - | - |
| 4 | shot 1689 XBAR barrelTemp | NCR_DRAFT | HEATER_DEGRADATION | 75 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 225.0℃ 이하로 추가 하락하는지 여부; 사출 압력(injectionPressure) 1215 bar 초과 또는 모터 전류(motorCurrent) 46.5 A 초과 동반 상승 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 5 | shot 1731 INDIVIDUAL barrelTemp | NCR_DRAFT | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 및 교체 조치 후 실린더 온도가 설정 목표치(230℃)로 정상 회복되는지 여부; 실린더 온도 추가 저하 시 사출 압력(1215 bar 초과) 및 모터 전류(46.5 A 초과)의 동반 상승 발생 여부; 미성형(SHORT_SHOT) 불량의 추가 발생 여부 및 구간 불량률 추이 |

**단발성 경보 (shot 1922 사이클 타임) — 1회차** (`replay-1124173749-r1-noise-IMM-01-1922`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1922 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임이 관리 범위(29.53~30.69 s) 내에서 지속 유지되는지 여부; 최근 5 shot 평균 사이클 타임의 추가적인 하락 또는 상승 추세 발생 여부; DIMENSION(치수) 불량의 연속 발생 여부 |
| 2 | shot 1962 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 치수(DIMENSION) 불량의 추가 연속 발생 여부; 사이클 타임이 관리 범위(29.53~30.69 s)를 다시 이탈하는지 여부 |

**단발성 경보 (shot 141 사이클 타임) — 1회차** (`replay-1124173749-r1-noise-IMM-02-141`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 141 INDIVIDUAL cycleTime | OBSERVE ✅ | UNKNOWN | 45 | PASS | 사이클 타임 최근 5 shot 평균이 30.3 s 이상으로 유지되거나 관리 한계 이탈(XBAR) 경보가 발생하는지; 금형 온도(moldTemp)가 기준치 60 ℃ 대비 유의미하게 상승하는지; 치수(DIMENSION) 또는 휨(WARPAGE) 불량이 발생하는지 |
| 2 | shot 181 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 단발성 지연(30.5 s 초과) 재발 여부; 금형 온도(moldTemp) 60 ℃ 기준 ±1 ℃ 초과 변동 여부 |
| 3 | shot 221 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 30.5 s 초과 단발성 지연 재발 여부; 금형 온도(moldTemp) 60 ℃ 기준 ±1 ℃ 초과 변동 여부 |
| 4 | shot 261 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임이 30.5 s를 초과하거나 연속적인 지연 추세가 발생하는지; 금형 온도(moldTemp)가 기준 60 ℃ 대비 61 ℃ 이상으로 추세적 상승을 보이는지 |
