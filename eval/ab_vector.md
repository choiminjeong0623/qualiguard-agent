# F4: 유사 사례 의미 검색 켬(A) vs 끔(B)

- 기준(A): `20261124-1551_22af36e_base-vector`
- 비교(B): `20261124-1558_22af36e_base-novector`

| 항목 | A | B |
|---|---|---|
| 설정 | model=gemini-3.8-flash, analysisMode=tools, vectorEnabled=True | model=gemini-3.8-flash, analysisMode=tools, vectorEnabled=False |
| 정답 (진단 성공 / 사례·회차) | 5/5 | 5/5 |
| 진단까지 shot (중앙값) | 38.0 | 38.0 |
| 잘못된 초안 | 0 | 0 |
| 실패한 단계 | 0 | 0 |
| 분석 단계 수 | 6 | 6 |
| LLM 호출 합계 | 6 | 6 |
| 도구 호출 합계 | 58 | 57 |
| 1단계 지연 평균 (초) | 62.6 | 60.7 |
| 1단계 지연 P95 (초) | 77.7 | 66.4 |
| 1단계 입력 토큰 평균 | 53,613 | 46,072 |
| 1단계 출력 토큰 평균 | 1,193 | 1,209 |
| 1단계 생각 토큰 평균 | 0 | 0 |
| 사례당 예상 비용 (USD, 예시 단가 0.3/2.5) | 0.0229 | 0.0202 |
| Top-3 적중 (마지막 판단, E5) | 100% | 100% |
| 근거 수치 일치율 (E5) | 96% | 96% |
| 인용 사례 실재율 (E5) | 100% | 100% |

## 사례별

| 사례 | 회차 | A 판단 | B 판단 |
|---|---|---|---|
| check_ring_wear | 1 | ✅ shot +37 | ✅ shot +37 |
| cooling_blockage | 1 | ✅ shot +39 | ✅ shot +39 |
| heater_degradation | 1 | ✅ shot +43 | ✅ shot +43 |
| pressure_sensor_spike | 1 | ✅ shot +7 | ✅ shot +7 |
| 단발성 경보 (shot 1934 쿠션량) | 1 | ✅ | ✅ |

- 비용은 예시 단가입니다. 쓰는 모델의 요금표로 INPUT_USD_PER_M·OUTPUT_USD_PER_M 를 바꿔 다시 만드세요.
- 사례 수가 적어(5사례) 1~2건 차이는 우연일 수 있습니다. 회차를 늘려(--runs 3) 분포로 보세요.
