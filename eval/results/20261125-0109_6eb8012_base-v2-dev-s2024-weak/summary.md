## 시간 순 재생 평가 — 사례 6건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 92 | 0 | 163 | 0 | 3 (0) | 62.8 / 75.9 |
| pressure_sensor_spike | 1/1 | 40 | 1 | 23 | 0 | 1 (0) | 40.3 / 40.3 |
| cooling_blockage | 1/1 | 97 | 0 | 148 | 0 | 5 (1) | 79.2 / 174.4 |
| check_ring_wear | 1/1 | 60 | 0 | 123 | 0 | 2 (0) | 76.7 / 85.3 |
| 단발성 경보 (shot 989 쿠션량) | 1/1 | - | - | - | 0 | 4 (3) | 52.0 / 75.5 |
| 단발성 경보 (shot 141 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 46.0 / 53.1 |

- 정답 진단: **6/6** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 19회 (분석 19단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 189회
- 분석 1단계 소요: 평균 61.6초, 최소 37초, 최대 174초, 1분 초과 6/19단계
- 분석 1단계 토큰 평균: 입력 47839, 출력 1197, 생각 - (19단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 67% | 100% | 94% (수치 440개) | 100% (인용 19건) | 0% (보류 2건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125004947-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 363 INDIVIDUAL cycleTime | OBSERVE | NOISE | 70 | PASS | 사이클 타임 최근 5 shot 평균이 추가 하락하여 29.5 s 이하로 지속되는지; 실린더 온도(barrelTemp)의 228 ℃ 이하 추가 하락 여부; 제품 취출 시간 및 형개폐 동작의 불규칙 변동 발생 여부 |
| 2 | shot 391 XBAR injectionPressure | OBSERVE | HEATER_DEGRADATION | 75 | PASS | 실린더 온도(barrelTemp) 최근 5 shot 평균이 227 ℃ 이하로 추가 하락하는지; 사출 압력(injectionPressure) 최근 5 shot 평균이 1215 bar를 초과하여 계속 상승하는지; 미성형(SHORT_SHOT) 등 외관 품질 불량 발생 여부 |
| 3 | shot 392 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 및 교체 후 실린더 온도(barrelTemp)의 230 ℃ 정상 복귀 여부; 사출 압력(injectionPressure) 최근 5 shot 평균의 1200 bar 이하 안정화 여부; 점검 및 조치 완료 전까지 생산 제품의 미성형(SHORT_SHOT) 불량 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1125004947-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 440 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값이 연속적으로 튀거나 기준 범위(1166~1238 bar)를 재이탈하는지 여부; 커넥터 재체결 후 노이즈 발생 여부 모니터링 |

**cooling_blockage — 1회차** (`replay-1125004947-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 925 XBAR cycleTime | OBSERVE | UNKNOWN | 40 | PASS | 사이클 타임 5 shot 평균이 30.09 s를 지속적으로 초과하거나 31.5 s 이상으로 추가 상승하는지 여부; 금형 온도가 60 ℃ 기준에서 62 ℃ 이상으로 상승하는 추세를 보이는지 여부; 사출 압력 및 쿠션량의 동반 변동 발생 여부 |
| 2 | shot 965 재확인 | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균이 30.5 s를 초과하거나 기준 대비 5% 수준인 31.5 s 이상으로 상승하는지 여부; 금형 온도 5 shot 평균이 61.5 ℃ 이상으로 상승하는 추세를 보이는지 여부; 제품 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 3 | shot 966 INDIVIDUAL cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균이 30.5 s를 초과하거나 기준 대비 5% 연장인 31.5 s 이상으로 상승하는지 여부; 금형 온도 5 shot 평균이 61.5 ℃ 이상으로 지속 상승하는지 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 4 | shot 971 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 70 | PASS | 금형 온도 5 shot 평균이 61.5 ℃ 이상 또는 62.0 ℃를 지속 초과하는지 여부; 사이클 타임 5 shot 평균이 30.5 s를 초과하거나 추가 지연되는지 여부; 치수(DIMENSION) 상한 초과 또는 휨(WARPAGE) 불량 발생 여부 |
| 5 | shot 997 INDIVIDUAL moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 냉각 계통 점검 및 조치 후 금형 온도 5 shot 평균이 기준선(60 ℃) 부근으로 하강 안정화되는지 여부; 사이클 타임 5 shot 평균이 30.2 s 이하로 정상 회복되는지 여부; 치수(DIMENSION) 상한 초과 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1125004947-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1225 XBAR cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임 5 shot 평균이 관리 한계 하한(29.91 s) 이하로 계속 유지되거나 추가 단축되는지 여부; 보압(holdingPressure)이 규격 상한(630 bar) 방향으로 계속 상승하는지 여부; 금형 온도(moldTemp)의 변동 여부 및 치수/외관 검사 결과 불량 발생 여부 |
| 2 | shot 1260 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 5 shot 평균이 4.80 mm 이하로 추가 감소하는지 여부; 사출 압력 표준편차가 15 bar 이상 지속되는지 여부; 외관 검사 시 미성형(SHORT_SHOT) 불량 발생 여부 |

**단발성 경보 (shot 989 쿠션량) — 1회차** (`replay-1125004947-r1-noise-IMM-01-989`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 989 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 60 | PASS | 쿠션량 최근 5 shot 평균 및 관리 범위(4.64~5.28 mm) 재이탈 여부; 사출 압력 표준편차 증가 및 계량 시간 지연 여부; 미성형(SHORT_SHOT) 추가 발생 여부 |
| 2 | shot 1029 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 체크 링 노후화에 대비하여 쿠션량 5 shot 이동평균이 4.8 mm 이하로 하향 추세를 보이는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급증하는지 여부 |
| 3 | shot 1069 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 체크 링 노후화에 대비하여 쿠션량 5 shot 평균이 4.8 mm 이하로 지속 감소하는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급증하는지 여부 |
| 4 | shot 1109 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 체크 링 노후화 대비 쿠션량 5 shot 평균 4.8 mm 이하 하향 지속 여부; 사출 압력 표준편차 15 bar 이상 급증 여부 |

**단발성 경보 (shot 141 사이클 타임) — 1회차** (`replay-1125004947-r1-noise-IMM-02-141`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 141 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 65 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s를 초과하여 유지되는지 여부; 금형 온도(moldTemp)의 상승 추세 발생 여부 |
| 2 | shot 181 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s를 초과하여 유지되는지 여부; 금형 온도(moldTemp)의 관리 상한 방향 상승 추세 발생 여부 |
| 3 | shot 221 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s를 초과하여 유지되는지 여부; 금형 온도(moldTemp)의 관리 상한 방향 상승 추세 발생 여부 |
| 4 | shot 261 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 최근 5 shot 평균이 30.5 s를 초과하여 유지되는지 여부; 금형 냉각 채널 스케일 누적에 따른 금형 온도(moldTemp) 상승 추세 발생 여부 |
