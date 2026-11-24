# A1: 도구 호출 에이전트(A) vs 도구 미리 조회 + LLM 1회(B)

- 기준(A): `20261125-0336_6eb8012-dirty_base-tools`
- 비교(B): `20261125-0341_6eb8012-dirty_base-prefetch`

| 항목 | A | B |
|---|---|---|
| 설정 | model=gemini-3.8-flash, analysisMode=tools, vectorEnabled=True | model=gemini-3.8-flash, analysisMode=prefetch, vectorEnabled=True |
| 정답 (진단 성공 / 사례·회차) | 5/5 | 5/5 |
| 진단까지 shot (중앙값) | 43.0 | 43.0 |
| 잘못된 초안 | 0 | 0 |
| 실패한 단계 | 0 | 0 |
| 분석 단계 수 | 9 | 9 |
| LLM 호출 합계 | 9 | 9 |
| 도구 호출 합계 | 79 | 66 |
| 1단계 지연 평균 (초) | 53.8 | 28.9 |
| 1단계 지연 P95 (초) | 73.9 | 42.1 |
| 1단계 입력 토큰 평균 | 38,659 | 7,772 |
| 1단계 출력 토큰 평균 | 1,167 | 723 |
| 1단계 생각 토큰 평균 | 0 | 2,171 |
| 사례당 예상 비용 (USD, 예시 단가 0.3/2.5) | 0.0261 | 0.0172 |
| Top-3 적중 (마지막 판단, E5) | 100% | 100% |
| 근거 수치 일치율 (E5) | 91% | 90% |
| 인용 사례 실재율 (E5) | 100% | 100% |

## 사례별

| 사례 | 회차 | A 판단 | B 판단 |
|---|---|---|---|
| check_ring_wear | 1 | ✅ shot +39 | ✅ shot +39 |
| cooling_blockage | 1 | ✅ shot +57 | ✅ shot +57 |
| heater_degradation | 1 | ✅ shot +47 | ✅ shot +47 |
| pressure_sensor_spike | 1 | ✅ shot +7 | ✅ shot +7 |
| 단발성 경보 (shot 1934 쿠션량) | 1 | ✅ | ✅ |

- 비용은 예시 단가입니다. 쓰는 모델의 요금표로 INPUT_USD_PER_M·OUTPUT_USD_PER_M 를 바꿔 다시 만드세요.
- 사례 수가 적어(5사례) 1~2건 차이는 우연일 수 있습니다. 회차를 늘려(--runs 3) 분포로 보세요.
