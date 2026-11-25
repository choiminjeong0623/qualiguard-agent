## 시간 순 재생 평가 — 사례 8건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 46 | 0 | 123 | 0 | 2 (0) | 50.8 / 57.6 |
| pressure_sensor_spike | 1/1 | 7 | 0 | 23 | 0 | 1 (0) | 36.7 / 36.7 |
| cooling_blockage | 1/1 | 58 | 0 | 126 | 0 | 2 (0) | 55.6 / 68.0 |
| check_ring_wear | 1/1 | 39 | 0 | 113 | 0 | 2 (0) | 53.3 / 55.2 |
| sensor_drift | 0/1 | 미진단 | - | 85 | 0 | 5 (2) | 86.4 / 170.6 |
| 단발성 경보 (shot 1922 사이클 타임) | 1/1 | - | - | - | 0 | 2 (1) | 50.2 / 54.9 |
| 단발성 경보 (shot 1934 쿠션량) | 2/2 | -, - | -, - | - | 0 | 2 (1), 2 (1) | 49.6 / 68.5 |
| 단발성 경보 (shot 141 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 42.1 / 53.5 |

- 정답 진단: **8/9** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 22회 (분석 22단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 204회
- 분석 1단계 소요: 평균 57.0초, 최소 29초, 최대 171초, 1분 초과 5/22단계
- 분석 1단계 토큰 평균: 입력 40141, 출력 1121, 생각 - (22단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 95% (수치 486개) | 100% (인용 20건) | 0% (보류 1건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125002604-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 343 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도(barrelTemp) 최근 5 shot 평균이 228℃ 이하로 추가 하락하는지 여부; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 지속적 동반 상승 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 346 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 구역별 저항 점검 완료 시까지 실린더 온도 225℃ 이하 추가 하락 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 전수 검사; 사출 압력 1220 bar 초과 여부 모니터링 |

**pressure_sensor_spike — 1회차** (`replay-1125002604-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 단발성 스파이크 재발 여부; 사출 압력 센서 신호 전압 및 커넥터 접촉 상태; 이후 연속 샷에서 쿠션량 및 모터 전류 동반 변동 발생 여부 |

**cooling_blockage — 1회차** (`replay-1125002604-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 942 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 72 | PASS | moldTemp 최근 5 shot 평균이 61.5 ℃를 초과하여 계속 상승하는지; cycleTime이 기준(30 s) 대비 5% 이상 지연되는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량이 발생하는지 |
| 2 | shot 958 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도(moldTemp) 5 shot 평균이 62.0 ℃를 초과하여 계속 상승하는지; 사이클 타임(cycleTime)이 30.3 s를 초과하여 지연이 심화되는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량이 실제로 검출되는지 |

**check_ring_wear — 1회차** (`replay-1125002604-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1233 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 60 | PASS | 쿠션량 5 shot 평균이 4.75 mm 이하로 추가 하락하는지; 사출 압력 표준편차가 평소(5~8 bar) 대비 크게 확대되는지; 미성형(SHORT_SHOT) 등 품질 불량이 발생하는지 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 점검 및 교체 후 쿠션량 평균이 정상 기준(5.0 mm 부근)으로 회복되는지; 사출 압력 5 shot 표준편차가 평소 수준(5~8 bar)으로 안정화되는지; 미성형(SHORT_SHOT) 불량의 재발 여부 |

**sensor_drift — 1회차** (`replay-1125002604-r1-sensor_drift`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1575 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 60 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 229.5℃ 이하로 계속 하락하는지; 사출 압력(injectionPressure)이 기준(1200bar)을 상회하여 동반 상승하는지; 모터 전류(motorCurrent)가 46A를 초과하여 추가 상승하는지; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1613 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 55 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 228.0℃ 이하로 추가 하락하는지; 사출 압력(injectionPressure) 5 shot 평균이 기준(1200 bar)을 지속적으로 상회하는지; 모터 전류(motorCurrent) 5 shot 평균이 46 A를 초과하여 동반 상승하는지; 미성형(SHORT_SHOT) 불량 발생 여부 |
| 3 | shot 1653 재확인 | OBSERVE | HEATER_DEGRADATION | 45 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 226.5℃ 이하로 추가 하락하는지; 사출 압력(injectionPressure) 5 shot 평균이 기준(1200 bar)을 넘어 1205 bar 이상으로 동반 상승하는지; 모터 전류(motorCurrent) 5 shot 평균이 46 A를 초과하여 상승하는지; 미성형(SHORT_SHOT) 불량 발생 여부 |
| 4 | shot 1679 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 40 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 225.0℃ 이하로 추가 하락하는지; 사출 압력(injectionPressure) 5 shot 평균이 기준(1200 bar)을 넘어 1205 bar 이상으로 동반 상승하는지; 모터 전류(motorCurrent) 5 shot 평균이 46 A를 초과하여 동반 상승하는지; 미성형(SHORT_SHOT) 불량 발생 여부 |
| 5 | shot 1719 재확인 | OBSERVE | HEATER_DEGRADATION | 35 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 225.0℃ 이하로 추가 하락하는지; 사출 압력(injectionPressure) 5 shot 평균이 기준(1200 bar)을 넘어 1205 bar 이상으로 동반 상승하는지; 모터 전류(motorCurrent) 5 shot 평균이 46 A를 초과하여 동반 상승하는지; 미성형(SHORT_SHOT) 불량 발생 여부 |

**단발성 경보 (shot 1922 사이클 타임) — 1회차** (`replay-1125002604-r1-noise-IMM-01-1922`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1922 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 최근 5 shot 평균이 기준값(30 s)에서 추가 편차를 보이는지; 금형 온도(moldTemp)의 변동 및 연속적인 관리 한계 이탈 발생 여부 |
| 2 | shot 1962 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 측정값이 관리 한계(29.53~30.69 s)를 다시 이탈하는지 여부; 금형 온도(moldTemp)가 규격(55~66 ℃) 내에서 안정적인지 모니터링 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125002604-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 70 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 점진 하락하는지 여부; 사출 압력의 구간 표준편차가 10 bar 이상으로 확대되는지 여부; 미성형(SHORT_SHOT) 등 품질 불량 재발 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 하강하는지 여부; 사출 압력 5 shot 구간 표준편차가 10 bar를 지속 초과하는지 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125002604-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.80 mm 이하로 지속 하강하는지 여부; 사출 압력 구간 표준편차가 10 bar 이상으로 확대되는지 여부; 미성형(SHORT_SHOT) 등 품질 불량 추가 발생 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 최근 5 shot 평균이 4.80 mm 이하로 점진적 하락 추세를 보이는지 여부; 사출 압력 5 shot 구간 표준편차가 10 bar를 지속적으로 초과하는지 여부; 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |

**단발성 경보 (shot 141 사이클 타임) — 1회차** (`replay-1125002604-r1-noise-IMM-02-141`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 141 INDIVIDUAL cycleTime | OBSERVE ✅ | UNKNOWN | 45 | PASS | 사이클 타임 연속 5 shot 평균이 30.5 s를 초과하여 유지되는지 여부; 금형 온도(moldTemp) 상승 및 냉각수 유량 변동 발생 여부 |
| 2 | shot 181 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 측정값 30.5 s 초과 재발 여부; 금형 온도(moldTemp) 기준치(60 ℃) 유지 상태 |
| 3 | shot 221 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 측정값 30.5 s 초과 재발 여부; 금형 온도(moldTemp) 기준치(60 ℃) 유지 상태 |
| 4 | shot 261 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 측정값 30.5 s 초과 재발 여부; 금형 온도(moldTemp) 60 ℃ 기준 안정 유지 상태 |
