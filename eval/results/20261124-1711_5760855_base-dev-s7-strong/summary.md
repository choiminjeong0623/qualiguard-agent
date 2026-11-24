## 시간 순 재생 평가 — 사례 7건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 50 | 1 | 39 | 0 | 3 (1) | 59.8 / 67.1 |
| pressure_sensor_spike | 1/1 | 4 | 0 | 122 | 0 | 1 (0) | 77.6 / 77.6 |
| cooling_blockage | 1/1 | 58 | 0 | 63 | 0 | 4 (0) | 88.9 / 164.6 |
| check_ring_wear | 1/1 | 28 | 0 | 45 | 0 | 2 (0) | 73.1 / 83.1 |
| 단발성 경보 (shot 1027 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 57.3 / 73.1 |
| 단발성 경보 (shot 1559 쿠션량) | 1/1 | - | - | - | 0 | 4 (3) | 47.8 / 53.4 |
| 단발성 경보 (shot 1795 실린더 온도) | 1/1 | - | - | - | 0 | 4 (3) | 65.1 / 79.0 |

- 정답 진단: **7/7** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 22회 (분석 22단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 187회
- 분석 1단계 소요: 평균 65.4초, 최소 41초, 최대 165초, 1분 초과 12/22단계
- 분석 1단계 토큰 평균: 입력 38734, 출력 1142, 생각 - (22단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 86% | 100% | 94% (수치 516개) | 100% (인용 16건) | 0% (보류 3건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1124164605-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 301 XBAR cycleTime | OBSERVE | UNKNOWN | 65 | PASS | 사이클 타임 5 shot 평균이 29.8 s 이하로 추가 하락하거나 편차가 지속되는지 여부; 금형 온도(moldTemp)의 변동이나 냉각 시간 관련 이상 징후 발생 여부; 치수(DIMENSION) 등 제품 불량 발생 여부 |
| 2 | shot 341 재확인 | OBSERVE | UNKNOWN | 70 | PASS | shot 337~341 이후 미성형(SHORT_SHOT) 불량이 추가로 발생하는지 여부; 사이클 타임 5 shot 평균이 관리 한계(29.91s 미만)를 다시 벗어나는지 여부; 쿠션량 및 사출 압력의 급격한 산포 증가 여부 |
| 3 | shot 350 INDIVIDUAL barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 및 조치 후 실린더 온도(barrelTemp)의 목표값(230℃) 복귀 여부; 사출 압력(injectionPressure) 5 shot 평균이 1200 bar 수준으로 안정화되는지 여부; 모터 전류(motorCurrent) 하향 안정화 및 미성형(SHORT_SHOT) 추가 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1124164605-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 404 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 단발성 스파이크 재발 여부; 차기 50 shot 동안 사출 압력 평균(목표 1200 bar) 및 표준편차 안정성 유지 여부 |

**cooling_blockage — 1회차** (`replay-1124164605-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 936 INDIVIDUAL cycleTime | OBSERVE | NOISE | 70 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s 이상으로 지속 상승하거나 관리 한계를 재이탈하는지; 금형 온도(moldTemp)가 기준(60 ℃) 대비 상승 동반 변화를 보이는지 |
| 2 | shot 937 XBAR cycleTime | OBSERVE | COOLING_ISSUE | 60 | PASS | 사이클 타임 최근 5 shot 평균이 기준 대비 5% 이상인 31.5 s에 근접하거나 초과하는지; 금형 온도(moldTemp)가 기준(60 ℃) 대비 상승 추세로 전환되는지; 치수(DIMENSION) 등 외관·치수 불량이 발생하는지 |
| 3 | shot 943 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 60 | PASS | 실린더 온도(barrelTemp) 최근 5 shot 평균이 다시 관리 하한 229.66 ℃ 밑으로 하락하는지; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 징후가 나타나는지; 미성형(SHORT_SHOT) 등 외관 불량이 발생하는지 |
| 4 | shot 958 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도 최근 5 shot 평균이 61.5 ℃를 초과하거나 관리 상한 방향으로 추가 상승하는지; 사이클 타임 최근 5 shot 평균이 30.7 s 이상으로 추가 지연되는지; 냉각 불균일 및 지연으로 인한 제품 치수(DIMENSION) 수축 및 휨(WARPAGE) 불량이 발생하는지 |

**check_ring_wear — 1회차** (`replay-1124164605-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1211 INDIVIDUAL injectionPressure | OBSERVE | NOISE | 75 | PASS | 사출 압력 최근 5 shot 평균이 1220 bar 이상으로 지속 상승하거나 관리 한계를 연속 이탈하는지 여부; 쿠션량(cushion) 평균 하락(4.5 mm 이하) 또는 산포 급증 여부(체크 링 마모 징후); 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |
| 2 | shot 1228 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 80 | PASS | 쿠션량 최근 5 shot 평균이 4.7 mm 이하로 추가 하락하거나 개별값이 4.5 mm 이하로 떨어지는지 확인; 사출 압력 변동폭 확대 및 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 실시간 확인; 체크 링 분해 점검 시 스크루 헤드 및 시트 링 마모 상태 확인 |

**단발성 경보 (shot 1027 사이클 타임) — 1회차** (`replay-1124164605-r1-noise-IMM-01-1027`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1027 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 88 | PASS | 사이클 타임의 관리 하한(29.53 s) 추가 이탈 발생 여부; 연속 2회 이상 평균 이동(XBAR 경보) 발생 여부; 금형 온도 및 취출 동작 시간의 동반 변화 여부 |
| 2 | shot 1067 재확인 | OBSERVE ✅ | NOISE | 92 | PASS | 사이클 타임 관리 범위(29.53~30.69 s) 추가 이탈 발생 여부; 금형 온도 및 쿠션량의 기준값 유지 여부 |
| 3 | shot 1107 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 관리 범위(29.53~30.69 s) 추가 이탈 발생 여부; 금형 온도 및 쿠션량 등 주요 센서의 Cpk 1.67 이상 유지 여부 |
| 4 | shot 1147 재확인 | OBSERVE ✅ | NOISE | 98 | PASS | 사이클 타임 관리 범위(29.53~30.69 s) 일상 모니터링 유지; 쿠션량 설정값(5 mm) ±0.5 mm 범위 내 안정 유지 여부 |

**단발성 경보 (shot 1559 쿠션량) — 1회차** (`replay-1124164605-r1-noise-IMM-02-1559`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1559 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 다음 shot 측정값이 관리 범위(4.68~5.28 mm) 내로 복귀하는지; 쿠션량의 연속적인 상승 또는 하락 추세 발생 여부; 사출 압력 및 보압의 변동폭(산포) 확대 여부 |
| 2 | shot 1599 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 향후 쿠션량의 관리 한계(4.68~5.28 mm) 재이탈 발생 여부; 정기 예방정비 주기 준수 및 스크류 체크 링 상태 모니터링 |
| 3 | shot 1639 재확인 | OBSERVE ✅ | NOISE | 98 | PASS | 쿠션량 관리 한계(4.68~5.28 mm) 재이탈 여부; 쿠션량 평균의 4.5 mm 이하 하향 추세 발생 여부 (SOP-INJ-012 기준); 사출 압력 산포(표준편차) 확대 여부 |
| 4 | shot 1679 재확인 | OBSERVE ✅ | NOISE | 99 | PASS | 향후 쿠션량 관리 한계(4.68~5.28 mm) 재이탈 발생 여부; 쿠션량 평균의 4.5 mm 이하 하향 추세 발생 여부 (SOP-INJ-012 기준); 사출 압력 및 보압 산포의 유의미한 확대 여부 |

**단발성 경보 (shot 1795 실린더 온도) — 1회차** (`replay-1124164605-r1-noise-IMM-02-1795`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1795 INDIVIDUAL barrelTemp | OBSERVE ✅ | NOISE | 75 | PASS | 실린더 온도(barrelTemp)가 228℃ 이하로 연속 하강하는지 여부; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 발생 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1835 재확인 | OBSERVE ✅ | NOISE | 80 | PASS | 치수(DIMENSION) 불량의 추가 발생 여부 및 연속 검출 여부; 금형 온도(moldTemp) 및 사이클 타임(cycleTime)의 지연·상승 징후; 실린더 온도(barrelTemp)가 228℃ 이하로 재이탈하는지 여부 |
| 3 | shot 1875 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 실린더 온도(barrelTemp)의 228℃ 이하 재하강 및 관리 한계 이탈 여부; 차기 50 shot 구간의 치수(DIMENSION) 및 외관 불량 검출 여부 |
| 4 | shot 1915 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 차기 50 shot 구간에서 치수(DIMENSION) 불량의 추가 및 연속 검출 여부; 금형 온도(moldTemp)의 61℃ 초과 상승 및 사이클 타임(cycleTime) 지연 발생 여부; 실린더 온도(barrelTemp)의 228℃ 이하 재하락 및 관리 한계 이탈 여부 |
