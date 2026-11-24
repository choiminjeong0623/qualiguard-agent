# 10. LLM 이 느리거나 죽었을 때 (시간 제한·재시도, 로드맵 O4)

## 문제

원인 분석은 Gemini 호출 1번 안에서 도구를 여러 번 부른다(평균 약 45초). 그런데 호출에 시간 제한이 없어서,
Gemini 가 응답하지 않으면 분석 스레드가 끝없이 기다렸다. 일시적인 503(과부하)이 한 번 나도 사건이 바로 FAILED 가 됐다.

## 해결: `LlmCallPolicy`

| 상황 | 동작 |
|---|---|
| 호출 1회가 90초를 넘음 | 끊고 재시도 |
| 5xx·429(요청 한도)·네트워크 오류 | 2초 뒤 재시도 (다음은 4초, 8초 … 지수 백오프) |
| 401·403(키 오류)·400·JSON 형식 오류 | 다시 해도 같으므로 바로 실패 |
| 재시도까지 모두 실패 | `LlmUnavailableException` → 오케스트레이터가 FAILED 로 기록하고 경보를 NEW 로 되돌림 (나중에 다시 분석) |

설정은 `application.yml` 의 `qg.agent.llm` (`timeout: 90s`, `max-retries: 1`, `backoff: 2s`).
재시도·시간 초과는 분석 기록(`tool_trace`)에 `llmCall(analysis, 시도 1/2) 실패: …` 로 남는다.

- 재시도는 조사(도구 호출)를 처음부터 다시 하므로 비용이 두 배가 될 수 있다. 그래서 재시도는 1회로 둔다.
- 시간 초과로 끊은 호출의 HTTP 요청은 백그라운드에서 끝날 때까지 남을 수 있다(결과는 버린다).

## 시험

- `LlmCallPolicyTest`: 성공, 503 뒤 재시도 성공, 시간 초과 3회(2초·4초 대기), 401 은 재시도 없음, 429·네트워크 오류 분류,
  가짜 ChatClient 로 장애를 주입해 `IncidentAnalyzer.analyzeOnce` 가 예외와 기록을 남기는지
- `AgentPipelineTest.분석이_실패하면_사건을_실패로_닫는다`: 예외 → FAILED

## 맥에서 실제 장애 주입 (틀린 키)

```bash
cd ~/dev/qualiguard-agent/qms-app
GEMINI_API_KEY=wrong-key ./gradlew bootRun      # .env 의 키 대신 틀린 키로 띄움
# 다른 터미널
TOKEN=$(grep ^QG_AGENT_API_TOKEN= ../.env | cut -d= -f2)
curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/agent/analyze-next
```

기대 결과: 재시도 없이 바로 `decision: FAILED`, `error` 에 `LlmUnavailableException … ClientException` (400 또는 401),
`GET /api/agent/anomalies?status=NEW` 에 그 경보가 다시 NEW 로 보임. 결과는 이 문서 아래에 붙인다.

### 결과 (2026-11-25 새벽, 맥, PR #103 반영된 main)

| 확인할 것 | 결과 |
|---|---|
| 응답 | `POST /api/agent/analyze-next` 가 **9.06초** 만에 HTTP 200, 분석 #2128 (IMM-02 경보 4건 묶음), `decision: FAILED`, `latencyMs` 8548 |
| 오류 | `LlmCallPolicy$LlmUnavailableException … ← com.google.genai.errors.ClientException: 400 INVALID_ARGUMENT (API_KEY_INVALID)` — 401 이 아니라 400 으로 온다 |
| 재시도 | 없음. `tool_trace` 에 `llmCall(analysis, 시도 1/2) 실패` 한 줄뿐이고 90초 시간 제한까지 기다리지 않음 |
| 경보 복구 | 묶였던 경보 4건이 모두 다시 `NEW` (`/api/agent/anomalies?status=NEW` 와 DB 로 확인) → 키를 고치면 다시 분석 대상 |

원본 출력: `eval/overnight-1124/o4-wrong-key.txt` (맥 실행 브랜치에 함께 커밋).

같은 날 개발셋 dev-s7-weak 에서는 반대 경우도 실제로 나왔다: 히터 1·2단계가 **시간 초과 → 재시도 → 실패 → 경보 NEW 복구 → 3단계에서 재분석**으로 이어졌다.
한계는 재시도가 조사를 처음부터 반복해 그 단계에 약 185초를 썼다는 점이다.
