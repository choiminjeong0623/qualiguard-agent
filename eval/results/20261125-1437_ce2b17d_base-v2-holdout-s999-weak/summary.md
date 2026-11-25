## 시간 순 재생 평가 — 사례 6건 × 1회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 1/1 | 64 | 0 | 124 | 0 | 3 (0) | 69.6 / 89.3 |
| pressure_sensor_spike | 1/1 | 20 | 0 | 31 | 0 | 1 (0) | 44.7 / 44.7 |
| cooling_blockage | 1/1 | 55 | 0 | 92 | 0 | 3 (0) | 57.6 / 73.2 |
| check_ring_wear | 1/1 | 71 | 0 | 158 | 0 | 2 (0) | 58.3 / 62.6 |
| 단발성 경보 (shot 58 사이클 타임) | 1/1 | - | - | - | 0 | 4 (3) | 49.2 / 57.4 |
| 단발성 경보 (shot 262 모터 전류) | 1/1 | - | - | - | 0 | 4 (3) | 44.9 / 53.7 |

- 정답 진단: **6/6** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE, 모호 사례: 틀린 원인으로 단정하지 않기)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 17회 (분석 17단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 153회
- 분석 1단계 소요: 평균 54.1초, 최소 34초, 최대 89초, 1분 초과 5/17단계
- 분석 1단계 토큰 평균: 입력 48678, 출력 1154, 생각 - (17단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 원인 평가 지표 (E5)

| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |
|---|---|---|---|---|
| 67% | 100% | 94% (수치 388개) | 100% (인용 10건) | 0% (보류 2건) |

- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)
- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)
- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율
- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)

### 단계별 판단

**heater_degradation — 1회차** (`replay-1125142137-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 315 XBAR moldTemp | OBSERVE | COOLING_ISSUE | 65 | PASS | 금형 온도 5 shot 평균이 60.7℃ 이상으로 추가 상승하는지 여부; 사이클 타임이 30.5s 이상으로 증가하는지 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 2 | shot 354 XBAR holdingPressure | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도가 227℃ 이하로 추가 하락하는지 여부; 사출 압력 및 모터 전류의 동반 상승 발생 여부; 미성형(SHORT_SHOT) 등 품질 불량 발생 여부 |
| 3 | shot 364 XBAR motorCurrent | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 저항 측정 및 부품 교체 후 실린더 온도의 목표치(230℃) 복귀 여부; 모터 전류 5 shot 평균의 관리 범위(43.68~45.43 A) 내 안정화 여부; 사출 압력의 기준치(1200 bar 부근) 안정화 및 미성형(SHORT_SHOT) 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1125142137-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 420 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 측정값의 단발성 스파이크 재발 여부; 사출 압력 센서 커넥터 점검 후 신호 노이즈 지속 여부; 모터 전류 및 쿠션량 등 관련 센서의 동반 변동 발생 여부 |

**cooling_blockage — 1회차** (`replay-1125142137-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 914 INDIVIDUAL barrelTemp | OBSERVE | NOISE | 85 | PASS | 실린더 온도(barrelTemp)의 228℃ 이하 하락 재발 여부; 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 여부 |
| 2 | shot 926 XBAR cycleTime | OBSERVE | UNKNOWN | 45 | PASS | 사이클 타임(cycleTime) 5 shot 평균의 30.2 s 초과 추가 상승 여부; 금형 온도(moldTemp)의 61.0 ℃ 초과 상승 동반 여부; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |
| 3 | shot 955 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도(moldTemp) 5 shot 평균의 61.5℃ 초과 추가 상승 여부; 사이클 타임(cycleTime) 5 shot 평균의 30.3s 초과 지속 여부; 치수(DIMENSION) 상한 초과 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 1회차** (`replay-1125142137-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1254 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 65 | PASS | 쿠션량 최근 5 shot 평균이 4.75 mm 이하로 추가 하락하거나 산포가 확대되는지; 사출 압력의 표준편차가 15 bar 이상으로 급증하거나 불안정해지는지; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |
| 2 | shot 1271 INDIVIDUAL holdingPressure | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 분해 점검 및 마모 측정 결과; 쿠션량 최근 5 shot 평균이 4.70 mm 이하로 추가 하락하는지; 미성형(SHORT_SHOT) 등 외관 불량 발생 여부 |

**단발성 경보 (shot 58 사이클 타임) — 1회차** (`replay-1125142137-r1-noise-IMM-01-58`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 58 INDIVIDUAL cycleTime | OBSERVE ✅ | NOISE | 80 | PASS | 사이클 타임 최근 5 shot 평균이 기준(30 s) 대비 지속적으로 벗어나는지 여부; 금형 온도(moldTemp) 및 냉각 계통의 동반 변동 발생 여부 |
| 2 | shot 98 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 사이클 타임 개별 shot 및 5 shot 이동평균이 관리 범위 내에서 안정적으로 유지되는지 지속 확인; 단발성으로 1건 발생한 버(FLASH) 불량이 연속 발생하는지 외관 검사 결과 모니터링 |
| 3 | shot 138 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 사이클 타임 개별 shot 및 5 shot 이동평균의 규격(28~32 s) 내 정상 유지 여부; 차기 정기 PM 도래 시 구동부 및 유압 계통 일상 점검 결과 모니터링 |
| 4 | shot 178 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 치수 불량의 연속 발생 여부 및 외관/치수 샘플링 검사 결과 모니터링; 사이클 타임 및 금형 온도의 관리 범위(28~32 s, 55~66 ℃) 내 안정 유지 여부 지속 확인 |

**단발성 경보 (shot 262 모터 전류) — 1회차** (`replay-1125142137-r1-noise-IMM-02-262`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 262 INDIVIDUAL motorCurrent | OBSERVE ✅ | NOISE | 85 | PASS | 모터 전류 최근 5 shot 평균이 관리 한계(42.63 A) 이하로 연속 하강하거나 XBAR 경보가 발생하는지; 사출 압력 및 실린더 온도의 동반 변화 발생 여부; 다음 50 shot 구간 내 불량(WARPAGE 등) 추가 발생 여부 |
| 2 | shot 302 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 모터 전류 개별값 및 5 shot 평균이 관리 한계(42.63~47.44 A) 내에서 지속 유지되는지; 다음 50 shot 구간 내 외관 불량 및 치수 이상 발생 여부 |
| 3 | shot 342 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 통상 감시 체제에서 모터 전류 개별값 및 5 shot 평균 관리 한계 이탈 여부 지속 모니터링 |
| 4 | shot 382 재확인 | OBSERVE ✅ | NOISE | 95 | PASS | 통상 감시 체제에서 모터 전류 개별값 및 5 shot 평균 관리 한계(42.63~47.44 A) 이탈 여부 지속 모니터링 |
