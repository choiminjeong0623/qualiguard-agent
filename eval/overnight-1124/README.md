# 11/24 밤 맥 실행 결과 (2026-11-25 00:13~04:17)

코드: main 5b98f21 (PR #103 포함). 모델 gemini-3.8-flash, 프롬프트 analysis-v2@941e709b. 평가 결과 폴더 이름의 커밋 6eb8012 는 이 브랜치의 캡처 커밋입니다(코드 변경 없음).

| 단계 | 결과 | 위치 |
|---|---|---|
| 0 F3 캡처 | 자동 실행(해소) · 승인 대기 · 효과 없음(재분석) 3장. ESCALATED 는 재분석이 OBSERVE/UNKNOWN 으로 끝나 만들지 못함 | `docs/demo/f3/` |
| 1 O4 틀린 키 | 400 API_KEY_INVALID → 재시도 없이 9.06초 만에 FAILED, 경보 4건 NEW 복귀 | `o4-wrong-key.txt` |
| 2 v2 개발셋 s2024-strong | 8/9 (sensor_drift 미진단), 잘못된 초안 0, 평균 57.0초 | `eval/results/20261125-0047_*` |
| 2 v2 개발셋 s2024-weak | 6/6, 잘못된 초안 0, 평균 61.6초 | `eval/results/20261125-0109_*` |
| 3 홀드아웃 s777-weak | 11/12 (check_ring_wear 미진단), 평균 53.8초 | `eval/results/20261125-0149_*` |
| 3 홀드아웃 s777-strong | 10/11 (check_ring_wear 미진단, 냉각으로 봄), FAILED 1, 평균 59.7초 | `eval/results/20261125-0233_*` |
| 3 홀드아웃 ambiguous | 11/13 (material_moisture·mold_wear 미진단), FAILED 2, 평균 60.9초 | `eval/results/20261125-0325_*` |
| 4 E7 | v2 홀드아웃 50~69 구간 적중 27%(3/11), 70~79 80%, 80~89 97%, 90+ 100% | `eval/calibration_report.md` |
| 5 A1 | 정답 5/5 같음, prefetch 지연 53.8→28.9초, 입력 토큰 38.7k→7.8k, 비용 0.0261→0.0172 USD | `eval/ab_prefetch.md` |
| 6 E3-fast mloff | 15/15, 평균 52.7초 (E3 62.2초), 수치 일치 94% | `eval/results/20261125-0406_*` |
| 6 E3-fast mlon | 15/15, 평균 27.2초, LLM 호출 21회 (mloff 27회) | `eval/results/20261125-0416_*` |
| 6 ML 통계 | 사건 18, 건너뜀 10, wouldSkip 66.7%, LLM 과 일치 100% (비교 8, wouldSkip 비교 2) | `ml-triage-stats.json` |

참고: `application.yml` 의 `qg.agent.ml-triage.mode` 기본값은 실제로 `"off"` 라서(주석은 skip 이 기본이라고 함), mlon 은 `QG_AGENT_ML_TRIAGE_MODE=skip` 으로 켰습니다.
