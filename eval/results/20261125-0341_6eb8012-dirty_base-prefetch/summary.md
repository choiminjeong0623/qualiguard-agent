## 시간 순 재생 평가 — 사례 5건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 47 | 0 | 68 | 0 | 2 (0) | 34.3 / 45.4 |
| pressure_sensor_spike | 1/1 | 7 | 0 | 구간 내 없음 | 0 | 1 (0) | 20.6 / 20.6 |
| cooling_blockage | 1/1 | 57 | 0 | 116 | 0 | 2 (0) | 28.7 / 32.7 |
| check_ring_wear | 1/1 | 39 | 1 | 39 | 0 | 2 (0) | 25.5 / 32.8 |
| 단발성 경보 (shot 1934 쿠션량) | 1/1 | - | - | - | 0 | 2 (1) | 31.4 / 37.1 |

- 정답 진단: **5/5** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 9회 (분석 9단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 66회
- 분석 1단계 소요: 평균 28.9초, 최소 18초, 최대 45초, 1분 초과 0/9단계
- 분석 1단계 토큰 평균: 입력 7772, 출력 723, 생각 2171 (9단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 100% | 100% | 90% (수치 169개) | 100% (인용 9건) | - (보류 0건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125033651-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 346 INDIVIDUAL barrelTemp | OBSERVE | HEATER_DEGRADATION | 75 | PASS | 실린더 온도(barrelTemp) 5 shot 평균의 226 ℃ 이하 추가 하락 여부; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 지속 상승 추세; 미성형(SHORT_SHOT) 등 제품 외관 불량 발생 여부 |
| 2 | shot 347 XBAR barrelTemp | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 및 교체 후 실린더 온도의 목표값(230 ℃) 복귀 여부; 사출 압력(1200 bar) 및 모터 전류(45 A) 부하 안정화 추이; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1125033651-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 재발 및 추가 스파이크 발생 여부; 센서 커넥터 재체결 후 노이즈 소멸 여부 |

**cooling_blockage — 1회차** (`replay-1125033651-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 941 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 70 | PASS | 금형 온도(moldTemp) 5 shot 평균이 61.5℃를 초과하여 추가 상승하는지 여부; 사이클 타임(cycleTime)이 기준(30.0s) 대비 5% 이상(31.5s 초과) 지연되는지 여부; 치수(DIMENSION) 또는 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 957 XBAR cycleTime | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 냉각수 공급 압력 및 유량 회복 여부; 사이클 타임 5 shot 평균의 관리 상한(30.09 s) 이내 복귀 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 추이 |

**check_ring_wear — 1회차** (`replay-1125033651-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1236 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 65 | PASS | 쿠션량 5 shot 평균이 4.80 mm 이하로 계속 유지되거나 추가 하강하는지; 사출 압력의 변동(산포) 증가 또는 Z-score의 급격한 변화 여부; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 분해 점검 전까지 추가 미성형 발생 여부 전수 검사; 사출 압력 변동폭 확대 및 쿠션량의 4.60 mm 이하 추가 하강 추이 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1125033651-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.8 mm 이하로 연속 하락하는지; 사출 압력 변동폭 확대 및 Z-score 변화 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 쿠션량 개별 측정값이 관리 범위(4.64~5.28 mm)를 다시 이탈하는지 여부; 차기 정기 예방보전 시 체크 링 마모 상태 육안 점검 |
