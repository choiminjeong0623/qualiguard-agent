# ADR-013. 에이전트 도구를 MCP 서버로도 노출한다 — 기준 시점은 기준 경보로만

- 상태: 채택
- 날짜: 2026-11-15
- 관련: [ADR-008](ADR-008-agent-design.md) "다음에 검토할 것", [ADR-007](ADR-007-sqlserver-qms-schema.md) 결정 4(기준 시점)

## 배경
원인 분석 에이전트의 도구(저장 프로시저 조회)는 앱 안의 에이전트만 쓸 수 있었습니다. 품질 담당자가 Claude Desktop 같은 다른 AI 클라이언트에서 "1호기 지난주 경보 원인 같이 보자"처럼 대화하며 같은 데이터를 보려면, 도구를 표준 방식으로 열어야 합니다.

## 결정 1. Spring AI MCP 서버(streamable HTTP, `/mcp`)로 같은 도구를 연다
| 선택지 | 장단점 |
|---|---|
| REST API 를 더 만들기 | 클라이언트마다 연동 코드를 따로 짜야 함 |
| 별도 MCP 서버 프로세스 | 8GB 환경에서 JVM 이 하나 더 필요 |
| **같은 앱에 Spring AI MCP 서버** | `spring-ai-starter-mcp-server-webmvc` 하나로, 기존 `QualityTools`를 그대로 재사용 |

도구 6개: `listAnomalies`, `getSpcSummary`, `getSensorTrend`, `getDefectSummary`, `findSimilarNcr`, `getMaintenanceHistory`. 내부 구현은 에이전트의 `QualityTools`를 그대로 씁니다(같은 저장 프로시저, 같은 결과 모양).

## 결정 2. 기준 시점은 시각이 아니라 기준 경보(anomalyId)로만
에이전트는 기준 시점을 모델이 바꿀 수 없게 도구 인자에서 숨겼습니다(ADR-007). MCP 도구는 사람이 지난 경보를 다시 볼 때도 쓰므로 기준 시점이 필요하지만, **임의의 시각을 받지 않고 기준 경보 ID 를 받아 서버가 그 경보 시각을 기준 시점으로 정합니다.**
- 기준 경보를 주면 데이터·지식저장소 모두 그 경보 시각까지만 봅니다 (미래 정보 차단 원칙 유지).
- 주지 않으면 지금까지의 데이터(운영 조회)입니다.
- 서버 안내문(instructions)에 "지난 경보를 분석할 때는 기준 경보를 지정하라"고 적었습니다.

## 결정 3. 읽기 전용, 인증은 에이전트 API 와 같이 로컬용
MCP 도구는 모두 조회만 합니다. 승인·반려 같은 쓰기는 사람이 로그인한 QMS 화면에서만 합니다(ADR-011). `/mcp`는 에이전트 API 처럼 로컬 도구용으로 열어 두고 CSRF 검사에서 뺐습니다. **운영에서는 내부망 제한이나 토큰 인증이 필요합니다.**

> 2026-11-23 갱신 (로드맵 Q2): `/mcp` 는 API 토큰이 있어야 쓸 수 있습니다. 헤더 `Authorization: Bearer <QG_AGENT_API_TOKEN>` (ApiTokenFilter). 토큰이 없거나 틀리면 401.

## 결과
- 연결: MCP 클라이언트에 `http://localhost:8080/mcp` (streamable HTTP) 를 등록하고, 헤더 `Authorization: Bearer <QG_AGENT_API_TOKEN>` 을 함께 설정
- 에이전트(`IncidentAnalyzer`)는 분석마다 `QualityTools`를 직접 넘기므로 MCP 빈의 영향을 받지 않습니다.

## 한계
- MCP 클라이언트별 사용자 구분·권한이 없습니다.
- 도구 결과의 크기 제한이 없어, `lastShots`를 크게 주면 응답이 길어질 수 있습니다(프로시저가 구간으로 묶어 돌려주므로 행 수는 제한적).
