## 시간 순 재생 평가 — 사례 6건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 66 | 0 | 163 | 0 | 2 (0) | 81.9 / 90.5 |
| pressure_sensor_spike | 1/1 | 40 | 1 | 23 | 0 | 1 (0) | 51.4 / 51.4 |
| cooling_blockage | 0/1 | 미진단 | - | 148 | 0 | 5 (1) | 87.9 / 144.2 |
| check_ring_wear | 1/1 | 64 | 0 | 123 | 0 | 2 (0) | 72.5 / 80.3 |
| 단발성 경보 (shot 989 쿠션량) | 1/1 | - | - | - | 0 | 4 (3) | 58.5 / 78.3 |
| 단발성 경보 (shot 141 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 64.1 / 71.9 |

- 정답 진단: **5/6** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 18회 (분석 18단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 167회
- 분석 1단계 소요: 평균 71.7초, 최소 47초, 최대 144초, 1분 초과 13/18단계
- 분석 1단계 토큰 평균: 입력 47674, 출력 1193, 생각 - (18단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 83% | 100% | 95% (수치 419개) | 100% (인용 16건) | 0% (보류 2건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1124171323-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 363 INDIVIDUAL cycleTime | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도 5 shot 평균이 228℃ 이하로 지속 하락하는지 여부; 사출 압력 및 보압 상승과 함께 미성형(SHORT_SHOT) 불량이 발생하는지 여부; 사이클 타임이 연속으로 관리 한계를 벗어나 XBAR 경보로 전이되는지 여부 |
| 2 | shot 366 XBAR cycleTime | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 1·2·3번 존 히터 밴드 저항 측정치 및 온도 조절기 출력률; 사출 압력 1220 bar 초과 여부 및 미성형(SHORT_SHOT) 불량 발생 여부; 실린더 온도의 추가 하락 및 검사 규격 하한(220℃) 접근 여부 |

**pressure_sensor_spike — 1회차** (`replay-1124171323-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 440 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 값의 추가적인 단발성 이상값 발생 여부; 센서 커넥터 점검 및 재체결 후 측정값 안정화 여부 |

**cooling_blockage — 1회차** (`replay-1124171323-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 925 XBAR cycleTime | OBSERVE | UNKNOWN | 70 | PASS | 사이클 타임 5 shot 평균이 30.09 s를 초과하여 지속 상승하는지 여부; 금형 온도(moldTemp)가 기준치(60 ℃) 대비 상승 추세를 보이는지 여부; 제품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 965 재확인 | OBSERVE | COOLING_ISSUE | 65 | PASS | 사이클 타임 5 shot 평균이 기준 대비 5% 초과인 31.5 s 에 근접하는지 여부; 금형 온도가 62 ℃ 를 초과하여 지속 상승하는지 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 3 | shot 966 INDIVIDUAL cycleTime | OBSERVE | COOLING_ISSUE | 70 | PASS | 사이클 타임 5 shot 평균이 기준 대비 5% 지연 기준인 31.5 s에 도달하는지 여부(SOP-INJ-001); 금형 온도(moldTemp) 5 shot 평균이 62.0 ℃를 초과하여 지속 상승하는지 여부; 제품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 4 | shot 972 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 75 | PASS | 금형 온도 5 shot 평균이 62.0 ℃를 초과하여 지속 상승하는지 여부; 사이클 타임 5 shot 평균이 기준치 5% 지연 기준인 31.5 s에 도달하는지 여부 (SOP-INJ-001); 제품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 5 | shot 997 INDIVIDUAL moldTemp | OBSERVE | COOLING_ISSUE | 75 | PASS | 금형 온도 5 shot 평균이 62.0 ℃를 초과하여 상한 규격(66 ℃) 방향으로 지속 상승하는지 여부; 사이클 타임 5 shot 평균이 기준 대비 5% 지연 기준인 31.5 s에 도달하는지 여부(SOP-INJ-001); 제품 치수(DIMENSION) 상한 초과 및 휨(WARPAGE) 불량 발생 여부(SOP-QC-041) |

**check_ring_wear — 1회차** (`replay-1124171323-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1250 XBAR cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균이 29.80s 이하로 추가 단축되거나 관리 범위(29.91~30.30s)로 복귀하는지 여부; 금형 온도(moldTemp) 및 사출 압력(injectionPressure)의 동반 변동 발생 여부; 치수 및 외관 불량 발생 여부 |
| 2 | shot 1264 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 5 shot 평균의 4.80mm 이하 추가 하락 여부; 사출 압력 5 shot 표준편차가 15 bar 이상으로 지속되는지 여부; 미성형(SHORT_SHOT) 불량 발생 여부 모니터링 |

**단발성 경보 (shot 989 쿠션량) — 1회차** (`replay-1124171323-r1-noise-IMM-01-989`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 989 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 70 | PASS | 쿠션량 측정값이 관리 범위(4.64~5.28 mm)를 다시 이탈하는지; 사출 압력 최근 5 shot 표준편차가 평소(7.35 bar) 대비 유의미하게 증가하는지; 미성형(SHORT_SHOT) 불량이 추가 발생하는지 |
| 2 | shot 1029 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 체크 링 마모 진행에 따른 쿠션량의 지속적 하락(4.5 mm 이하) 추세 발생 여부; 사출 압력 5 shot 표준편차의 지속적인 증가 및 산포 확대 여부 |
| 3 | shot 1069 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 체크 링 마모 진행에 따른 쿠션량의 지속적 하락(4.5 mm 이하) 발생 여부; 사출 압력 5 shot 표준편차의 지속적인 증가 및 산포 확대 여부 |
| 4 | shot 1109 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 체크 링 마모 진행에 따른 쿠션량 4.5 mm 이하로의 지속적 하락 여부; 사출 압력 5 shot 표준편차의 지속적인 산포 확대 여부; 미성형(SHORT_SHOT) 등 성형 불량 발생 여부 |

**단발성 경보 (shot 141 사이클 타임) — 1회차** (`replay-1124171323-r1-noise-IMM-02-141`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 141 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 최근 5 shot 평균이 기준 30 s 대비 5%(31.5 s) 이상 지속 연장되는지; 금형 온도(moldTemp)가 기준치 60 ℃ 대비 동반 상승 추세를 보이는지 |
| 2 | shot 181 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 최근 5 shot 평균이 기준 30 s 대비 5%(31.5 s) 이상 다시 연장되는지; 금형 온도(moldTemp)가 관리 상한(66 ℃) 방향으로 지속 상승하는지 |
| 3 | shot 221 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 최근 5 shot 평균이 기준 30 s 대비 5%(31.5 s) 이상 다시 연장되는지; 금형 온도(moldTemp)가 규격 상한(66 ℃) 방향으로 지속 상승하는지 |
| 4 | shot 261 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 최근 5 shot 평균이 기준 30 s 대비 5%(31.5 s) 이상 다시 연장되는지; 금형 온도(moldTemp)가 규격 상한(66 ℃) 방향으로 지속 상승하는지 |
