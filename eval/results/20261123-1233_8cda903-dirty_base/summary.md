## 시간 순 재생 평가 — 사례 5건 × 3회 (사례당 최대 5단계)

| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | 잘못된 초안 | 분석 단계 (재확인) | 평균 / 최대 소요(초) |
|---|---|---|---|---|---|---|---|
| heater_degradation | 3/3 | 65(재확인), 65(재확인), 65(재확인) | 0, 0, 0 | 68 | 0 | 2 (1), 2 (1), 2 (1) | 51.5 / 68.5 |
| pressure_sensor_spike | 3/3 | 7, 7, 7 | 0, 0, 0 | 구간 내 없음 | 0 | 1 (0), 1 (0), 1 (0) | 43.0 / 45.9 |
| cooling_blockage | 3/3 | 40, 40, 40 | 0, 0, 0 | 116 | 0 | 1 (0), 1 (0), 1 (0) | 39.5 / 41.7 |
| check_ring_wear | 3/3 | 39, 39, 32 | 1, 1, 0 | 39 | 0 | 2 (0), 2 (0), 1 (0) | 59.8 / 79.3 |
| 단발성 경보 (shot 1934 쿠션량) | 3/3 | -, -, - | -, -, - | - | 0 | 2 (1), 2 (1), 2 (1) | 51.3 / 60.3 |

**진단까지 shot 분포** (미진단은 제외, 최소 / 중앙값 / 최대)

| 사례 | 진단 횟수 | 최소 | 중앙값 | 최대 |
|---|---|---|---|---|
| heater_degradation | 3/3 | 65 | 65 | 65 |
| pressure_sensor_spike | 3/3 | 7 | 7 | 7 |
| cooling_blockage | 3/3 | 40 | 40 | 40 |
| check_ring_wear | 3/3 | 32 | 39 | 39 |

- 정답 진단: **15/15** (고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE)
- 잘못 만든 초안: 0건, 실패(FAILED): 0건, JSON 형식 재요청: 0회
- 검증 에이전트: 되돌려 다시 분석 0회, 다시 분석한 뒤에도 근거 부족(WEAK)으로 남은 단계 0개
- LLM 호출 합계: 23회 (분석 23단계 + 검증 반려 재분석 + JSON 재요청), 도구 호출 합계: 169회
- 분석 1단계 소요: 평균 50.6초, 최소 37초, 최대 79초, 1분 초과 4/23단계
- 분석 1단계 토큰 평균: 입력 27579, 출력 1081, 생각 - (23단계)
- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'
- 단발성 경보는 첫 판단으로 채점하고, 이어진 재확인에서 만든 초안은 '잘못된 초안'에 셉니다

### 단계별 판단

**heater_degradation — 1회차** (`replay-1123121321-r1-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 325 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도(barrelTemp) 최근 5 shot 평균이 228.0℃ 이하로 추가 하강하는지; 수지 점도 증가에 따른 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 여부; 미성형(SHORT_SHOT) 등 제품 품질 불량 발생 여부 |
| 2 | shot 365 재확인 | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 히터 점검 및 교체 조치 완료 여부; 실린더 온도(barrelTemp)의 규격 하한(220℃) 접근 여부; 사출 압력 지속 상승 및 미성형(SHORT_SHOT) 등 제품 불량 발생 여부 |

**pressure_sensor_spike — 1회차** (`replay-1123121321-r1-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 85 | PASS | 사출 압력 센서의 추가 단발성 튐 또는 연속 이상값 발생 여부; 쿠션량 및 모터 전류 등 연관 공정 파라미터의 동반 변동 여부; 사출 압력 최근 5 shot 평균 및 산포 안정화 여부 |

**cooling_blockage — 1회차** (`replay-1123121321-r1-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 940 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도 최근 5 shot 평균이 61.0℃ 이상 유지되거나 추가 상승하는지; 사이클 타임 5 shot 평균이 30.1s 이상으로 지속 연장되는지; 제품 검사에서 치수(DIMENSION) 및 휨(WARPAGE) 불량이 발생하는지 |

**check_ring_wear — 1회차** (`replay-1123121321-r1-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1232 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 65 | PASS | 쿠션량 5 shot 평균이 4.70 mm 이하로 지속 하락하는지; 사출 압력의 5 shot 표준편차가 평소(약 9.6 bar) 대비 15 bar 이상으로 급증하는지; 미성형(SHORT_SHOT) 불량이 발생하는지 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 점검 전까지 추가 미성형(SHORT_SHOT) 불량 발생 여부; 쿠션량 측정값이 4.60 mm 이하로 추가 하락하는지 여부; 사출 압력의 5 shot 변동폭(표준편차)이 15 bar 이상으로 급증하는지 여부 |

**단발성 경보 (shot 1934 쿠션량) — 1회차** (`replay-1123121321-r1-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.7 mm 이하로 지속 저하되는지 여부; 사출 압력의 표준편차 증가 또는 미성형(SHORT_SHOT) 불량 발생 추이 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 통상 양산 SPC 관리 한계선 이탈 여부 주기적 모니터링 |

**heater_degradation — 2회차** (`replay-1123121321-r2-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 325 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도(barrelTemp) 5 shot 평균이 228.0℃ 이하로 지속 하강하는지 여부; 수지 용융 저항 증가에 따른 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 여부; 미성형(SHORT_SHOT) 등 제품 외관 불량 발생 여부 |
| 2 | shot 365 재확인 | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 실린더 히터 밴드 존별 저항값 측정 및 정격 대비 증가율 확인; 실린더 온도 추가 하강(225.0℃ 접근 여부) 및 최저점 갱신 여부; 수지 미용융에 따른 미성형(SHORT_SHOT) 불량 발생 여부 |

**pressure_sensor_spike — 2회차** (`replay-1123121321-r2-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 90 | PASS | 사출 압력 측정값이 추가로 단발성 또는 연속적으로 튀는지 여부; 모터 전류 및 쿠션량 등 동반 센서의 변동 발생 여부; 센서 커넥터 점검 및 재체결 후 압력 신호 안정화 여부 |

**cooling_blockage — 2회차** (`replay-1123121321-r2-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 940 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도 최근 5 shot 평균이 61.0℃ 이상으로 지속 상승하는지; 사이클 타임 최근 5 shot 평균이 30.2s 이상으로 추가 지연되는지; 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 2회차** (`replay-1123121321-r2-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1232 XBAR cushion | OBSERVE | CHECK_RING_WEAR | 68 | PASS | 쿠션량 5 shot 평균이 4.7 mm 이하로 추가 하강하거나 규격 하한(3.5 mm)에 근접하는지; 사출 압력의 표준편차(산포)가 평소(약 9.6 bar) 이상으로 증가하는지; 미성형(SHORT_SHOT) 불량이 발생하는지 |
| 2 | shot 1239 INDIVIDUAL cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 체크 링 교체 후 쿠션량 평균이 기준치(5.0 mm) 부근으로 정상 복귀하는지; 사출 압력 5 shot 표준편차가 8 bar 이하 수준으로 안정화되는지; 미성형(SHORT_SHOT) 불량이 추가 발생하지 않는지 |

**단발성 경보 (shot 1934 쿠션량) — 2회차** (`replay-1123121321-r2-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.80 mm 이하로 지속 하강하는지; 사출 압력의 변동폭 및 표준편차가 증가하는지; 미성형(SHORT_SHOT) 불량이 연속 발생하는지 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 85 | PASS | 체크 링 권장 주기 도래에 따른 쿠션량 5 shot 평균의 4.80 mm 이하 하강 여부; 사출 압력 표준편차가 15 bar 이상으로 급격히 증가하는지 여부 |

**heater_degradation — 3회차** (`replay-1123121321-r3-heater_degradation`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 325 XBAR barrelTemp | OBSERVE | HEATER_DEGRADATION | 65 | PASS | 실린더 온도가 228.0℃ 이하로 추가 하강하는지; 수지 점도 증가로 인한 사출 압력(injectionPressure) 및 모터 전류(motorCurrent)의 동반 상승 여부; 미성형(SHORT_SHOT) 등 제품 불량 발생 여부 |
| 2 | shot 365 재확인 | NCR_DRAFT ✅ | HEATER_DEGRADATION | 85 | PASS | 히터 밴드 점검 및 교체 후 실린더 온도가 기준치 230℃로 회복되는지; 사출 압력이 기준치(1200 bar 수준)로 안정화되는지; 온도 저하로 인한 미성형(SHORT_SHOT) 등 제품 외관 불량 발생 여부 |

**pressure_sensor_spike — 3회차** (`replay-1123121321-r3-pressure_sensor_spike`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 407 INDIVIDUAL injectionPressure | SENSOR_CHECK ✅ | SENSOR_FAULT | 88 | PASS | 사출 압력 센서 값의 간헐적 급증(스파이크) 재발 여부; 이후 shot에서 사출 압력이 관리 범위(1164.02~1243.23 bar) 내로 정상 복귀하는지 |

**cooling_blockage — 3회차** (`replay-1123121321-r3-cooling_blockage`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 940 XBAR moldTemp | NCR_DRAFT ✅ | COOLING_ISSUE | 85 | PASS | 금형 온도 5 shot 평균이 61.0℃ 이상으로 지속 유지되거나 추가 상승하는지 여부; 사이클 타임 평균이 관리 상한(30.06s 이상)을 지속 초과하는지 모니터링; 사출 성형품 외곽 치수(DIMENSION) 및 휨(WARPAGE) 불량 발생 여부 |

**check_ring_wear — 3회차** (`replay-1123121321-r3-check_ring_wear`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1232 XBAR cushion | NCR_DRAFT ✅ | CHECK_RING_WEAR | 85 | PASS | 쿠션량 최근 5 shot 평균이 4.7 mm 이하로 추가 하락하거나 규격 하한(3.5 mm)에 접근하는지; 사출 압력의 표준편차(산포)가 급증하는지; 미성형(SHORT_SHOT) 등 품질 불량이 발생하는지 |

**단발성 경보 (shot 1934 쿠션량) — 3회차** (`replay-1123121321-r3-noise-IMM-01-1934`)

| 단계 | 시작 | 판단 | 원인 | 확신도 | 검증 | watchItems |
|---|---|---|---|---|---|---|
| 1 | shot 1934 INDIVIDUAL cushion | OBSERVE ✅ | NOISE | 75 | PASS | 쿠션량 최근 5 shot 평균이 4.80 mm 미만으로 지속 하강하는지 여부; 사출 압력의 표준편차 및 변동폭 확대 여부; 미성형(SHORT_SHOT) 불량 발생 빈도 증가 여부 |
| 2 | shot 1974 재확인 | OBSERVE ✅ | NOISE | 90 | PASS | 체크 링 사용 주기(733일 경과) 감안, 쿠션량 5 shot 평균이 4.80 mm 미만으로 점진적 하강하는지 여부; 사출 압력 표준편차가 15 bar 이상으로 급격히 증가하는지 여부 |
