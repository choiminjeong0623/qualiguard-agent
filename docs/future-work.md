# 향후 계획

2026-11-15 기준으로 구현·측정을 마친 범위와, 이어서 할 일을 정리합니다. 각 항목은 지금까지 문서(ADR·평가·장애 분석)에 "후보"나 "한계"로 남긴 것들입니다. 로컬 소형 LLM, MQTT 장비 인증·ACL, Redis 도입 판단은 [별도 문서](03-future-local-llm-and-device-security.md)에 있습니다.

## 1. 머지했지만 재생 평가를 아직 돌리지 않은 것

2026-11-21 두 브랜치(`feat/ncr-vector-search`, `feat/agent-token-usage`)를 main 에 합쳤습니다. 브랜치가 갈라진 뒤 분석이 멀티 에이전트 파이프라인(ADR-016)으로 바뀌어, 토큰 기록은 분석 에이전트의 LLM 호출마다 도구 기록에 `  ↳ 토큰: 입력 N, 출력 N, 생각 N` 한 줄을 남기고 저장할 때 더하는 방식으로 옮겼습니다(재분석·형식 수정 호출 포함).

| 항목 | 현재 상태 | 다음 할 일 |
|---|---|---|
| 유사 사례 **의미 검색** (SQL Server 2025 `VECTOR` + Gemini 임베딩 768차원, [ADR-014](decisions/ADR-014-ncr-vector-search.md)) | 단위 테스트 통과, 로컬 SQL Server 2025 CU11 에서 `VECTOR_DISTANCE` 동작 확인. 끄려면 `qg.knowledge.vector-enabled=false`. **에이전트 입력이 바뀌었는데 재생 평가는 아직** | 재생 평가 `--runs 3`·숨김 실험으로 정확도·진단 시점이 떨어지지 않는지 확인. 떨어지면 기본값을 false 로 |
| 분석 1단계 **토큰 기록** (입력·출력·생각) | `agent_analysis` 토큰 열 3개, 분석 결과(`tokens`)·평가 스크립트 평균 | 토큰 분포를 본 뒤 도구 결과 크기 줄이기 등을 정확도·진단 시점과 함께 측정 |

> 참고: 새 DB 는 `11_ncr_vector.sql`·`12_agent_tokens.sql` 이 init 순서대로 적용됩니다. 기존 로컬 DB 에는 두 스크립트가 이미 적용돼 있습니다.

## 2. 측정·평가

| 항목 | 이유 | 현재 상태 | 다음 할 일 |
|---|---|---|---|
| 과거 사례만 숨긴 실험 **5회 반복** | 숨김 실험의 압력 센서 진단 시점이 측정마다 달랐음(+145 / +7) | 3회씩 두 번만 측정. 정비 이력까지 숨긴 "처음 보는 고장" 5회는 완료(25/25) | `python3 scripts/evaluate_agent.py --runs 5 --hide-matching-ncr` 로 분포(최소·중앙값·최대) 기록 |
| 분석 1단계 **1분 이내** | 목표 미달 (E3 재평가 평균 62.2초, 최대 81초; 11-15 는 평균 44초) | 생각 수준 LOW 는 두 배 빠르지만 진단이 늦어져 채택 안 함, MEDIUM·답 길이 제한은 효과 없음 | 토큰 기록(위 브랜치)으로 원인을 본 뒤, 진단 시점을 해치지 않는 범위에서만 다시 시도 |
| 다른 고장 강도·시작 시점 | 지금 결과는 시나리오 데이터 1벌을 반복한 것 | 시뮬레이터 seed 고정 | 고장 진행 속도·센서 잡음을 바꾼 시나리오로 재생 평가 |

## 3. 탐지·분석

| 항목 | 이유 | 현재 상태 | 다음 할 일 |
|---|---|---|---|
| **탐지 시점 앞당기기** (체크 링 마모) | 첫 경보(+35)와 첫 불량(+39) 사이가 4 shot 이라, 에이전트가 첫 경보에서 맞혀도 여유가 없음 ([평가 2-8절](evaluation.md)) | 에이전트 쪽에서는 특정 고장 힌트 없이 개선 여지 없음을 확인 | 작은 평균 이동에 민감한 관리도(EWMA·CUSUM)를 더하고, 정상 구간 오경보와의 균형을 측정 |
| **다변량 관리도** | 공개 실제 데이터(SECOM, 센서 448개)에 센서별 규칙을 쓰면 경보가 100건당 205건으로 넘치고 불합격을 가려내지 못함 ([평가 2-9절](evaluation.md)) | 같은 엔진으로 결과만 기록 | Hotelling T²·PCA(SPE) 관리도, 센서 고르기, 기준 구간 갱신을 SECOM 으로 비교 |
| 정비 이력 조회의 경과 일수 | `usp_GetMaintenanceHistory` 의 `days_ago` 가 분석 기준 시점이 아니라 오늘 날짜 기준 | 지난 경보를 다시 볼 때 "며칠 전 정비"가 실제보다 길게 보임 | 기준 시점(@AsOf)을 받아 그 날짜 기준으로 계산 |

## 4. QMS·연계·운영

| 항목 | 이유 | 현재 상태 | 다음 할 일 |
|---|---|---|---|
| **작성·승인 분리** | 초안을 고친 사람이 같은 건을 승인할 수 있음 ([ADR-011](decisions/ADR-011-qms-login.md)) | 시연 환경 검토자가 한 명이라 넣지 않음 | `usp_ReviewNcr` 에서 마지막 수정자와 승인자 비교, 역할 추가(작성자/승인자) |
| **내부망 제한** (`/api/agent/**`, `/mcp`, actuator) | 로컬 평가 스크립트·MCP 클라이언트용으로 인증 없이 열어 둠 ([ADR-011](decisions/ADR-011-qms-login.md), [ADR-013](decisions/ADR-013-mcp-server.md)) | 로컬 전용 | 서비스 토큰 인증 또는 내부망·리버스 프록시 제한 |
| 로그인 보강 | 비밀번호 변경·잠금(실패 횟수)·로그인 감사 기록 없음 | 폼 로그인·역할 2개 | 사내 SSO(OIDC) 연동 또는 위 기능 추가 |
| **MES/ERP 연계를 인터페이스(EAI) 서버로** | 승인은 하루 수십 건이라 Kafka 처리량이 필요 없고, 현장에서는 EAI·인터페이스 서버 연계가 흔함 ([ADR-018](decisions/ADR-018-mes-erp-eai-interface-server.md)) | 아웃박스 → Kafka → 가상 MES/ERP (같은 앱) | [06 문서](06-mes-erp-eai-plan.md) 7장 손코딩 체크리스트 순서대로 |
| MES/ERP **응답 시간 초과** 감시 | 응답이 오지 않으면 화면에 "응답 대기"로만 보임 ([ADR-012](decisions/ADR-012-mes-erp-ack.md)) | 실패 응답과 다시 보내기는 구현 | 일정 시간 응답이 없으면 상태 표시·알림 |
| Kafka Streams **상태 폴더를 `build/` 밖으로** | 개발 중 `gradlew clean` 이 실행 중인 앱의 상태 폴더(`build/kafka-streams-state`)까지 지운 일이 있음 ([장애 분석 08](troubleshooting/08-local-dev-environment.md)) | 재시작하면 changelog 에서 복구되지만 위험 | `spring.kafka.streams.state-dir` 을 `build/` 밖(예: `~/.qualiguard/streams`)으로 |
| 통합 테스트에서 **Kafka 끄기** | `QmsAppApplicationTests` 가 실제 앱 ID 로 Kafka Streams·소비자를 띄워, 실행 중인 앱의 소비자 그룹이 잠깐 재조정됨 | 테스트는 통과 | 테스트 프로필에서 `spring.kafka.streams.auto-startup=false`, `spring.kafka.listener.auto-startup=false` |
