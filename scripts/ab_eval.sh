#!/usr/bin/env bash
# 에이전트 평가 A/B 실험을 한 줄로 (로드맵 A1·F4). 맥에서 실행 (Gemini 키·Docker 필요).
#
#   ./scripts/ab_eval.sh prefetch [회차=1]   # A1: 도구 호출 에이전트(tools) vs 도구 5개 미리 조회 + LLM 1회(prefetch)
#   ./scripts/ab_eval.sh vector   [회차=1]   # F4: 유사 사례 의미 검색 켬 vs 끔(키워드 검색만)
#   ./scripts/ab_eval.sh closed   [회차=1]   # 폐쇄망: Gemini vs Ollama 로컬 모델(프로파일 closed, QG_LOCAL_MODEL 기본 qwen2.5:7b)
#   그 뒤 인자는 evaluate_agent.py 에 그대로 넘어감. 예: ./scripts/ab_eval.sh vector 3 --hide-maintenance
#
# 하는 일: 설정만 바꿔 앱을 두 번 띄우고(./gradlew bootRun), 매번 evaluate_agent.py 를 돌린 뒤 앱을 끈다.
#          끝나면 scripts/ab_compare.py 로 eval/ab_<실험>.md 를 만든다.
# 준비: docker compose up -d, 시뮬레이터 전체 시나리오 1회 실행(data/ground_truth.csv), 8080 에 앱이 떠 있지 않을 것.
# 비용: 평가 2번 = 사례당 LLM 호출이 평소의 2배. 기본 1회차면 대략 20~40회 호출.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

EXP=${1:-}
RUNS=${2:-1}
shift $(( $# >= 2 ? 2 : $# )) || true
case "$EXP" in
  prefetch) A_ENV="QG_AGENT_ANALYSIS_MODE=tools";     A_LABEL=tools;    A_CHECK='"analysisMode":"tools"'
            B_ENV="QG_AGENT_ANALYSIS_MODE=prefetch";  B_LABEL=prefetch; B_CHECK='"analysisMode":"prefetch"'
            TITLE="A1: 도구 호출 에이전트(A) vs 도구 미리 조회 + LLM 1회(B)" ;;
  vector)   A_ENV="QG_KNOWLEDGE_VECTOR_ENABLED=true";  A_LABEL=vector;   A_CHECK='"vectorEnabled":true'
            B_ENV="QG_KNOWLEDGE_VECTOR_ENABLED=false"; B_LABEL=novector; B_CHECK='"vectorEnabled":false'
            TITLE="F4: 유사 사례 의미 검색 켬(A) vs 끔(B)" ;;
  closed)   A_ENV="QG_CHAT_MODEL=google-genai";       A_LABEL=gemini;   A_CHECK='"chatProvider":"google-genai"'
            B_ENV="SPRING_PROFILES_ACTIVE=closed";     B_LABEL=local;    B_CHECK='"chatProvider":"ollama"'
            TITLE="폐쇄망: Gemini(A) vs 로컬 모델 ${QG_LOCAL_MODEL:-qwen2.5:7b}(B, 외부 API 없음)"
            # 로컬 모델이 떠 있고 내려받아져 있는지 먼저 확인 (폐쇄망 프로파일은 실행 중에 내려받지 않음)
            MODEL=${QG_LOCAL_MODEL:-qwen2.5:7b}
            curl -s "${OLLAMA_URL:-http://localhost:11434}/api/tags" | grep -q "\"$MODEL\"" \
              || { echo "Ollama 에 $MODEL 이 없습니다: ollama serve 후 ollama pull $MODEL" >&2; exit 1; }
            export QG_EVAL_HTTP_TIMEOUT=${QG_EVAL_HTTP_TIMEOUT:-3600} ;;
  *) echo "사용법: $0 prefetch|vector|closed [회차] [evaluate_agent.py 인자...]" >&2; exit 2 ;;
esac

TOKEN=${QG_AGENT_API_TOKEN:-$(grep -E '^QG_AGENT_API_TOKEN=' .env 2>/dev/null | cut -d= -f2- | tr -d '"'"'" || true)}
[ -n "$TOKEN" ] || { echo ".env 에 QG_AGENT_API_TOKEN 이 없습니다 (Q2)" >&2; exit 1; }
[ -f data/ground_truth.csv ] || { echo "data/ground_truth.csv 가 없습니다. 시뮬레이터를 먼저 돌리세요." >&2; exit 1; }
if curl -s -o /dev/null http://localhost:8080/; then
  echo "8080 에 이미 앱이 떠 있습니다. 끄고 다시 실행하세요 (설정을 바꿔 새로 띄워야 함)." >&2; exit 1
fi

APP_PID=""
stop_app() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    # bootRun 이 띄운 자식 java 까지 정리
    for p in $(lsof -ti tcp:8080 2>/dev/null); do kill "$p" 2>/dev/null || true; done
    for _ in $(seq 30); do curl -s -o /dev/null http://localhost:8080/ || break; sleep 1; done
    APP_PID=""
  fi
}
trap stop_app EXIT

run_arm() {   # $1=환경 변수 $2=라벨 $3=설정 확인 문자열
  local log="data/ab_${EXP}_$2.log"
  echo "▶ [$2] 앱 시작 ($1), 로그: $log"
  (cd qms-app && env "$1" ./gradlew bootRun -q > "$ROOT/$log" 2>&1) &
  APP_PID=$!
  local cfg=""
  for _ in $(seq 180); do
    cfg=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/agent/config || true)
    [ -n "$cfg" ] && break
    kill -0 "$APP_PID" 2>/dev/null || { echo "앱이 시작하지 못했습니다. $log 를 보세요." >&2; exit 1; }
    sleep 2
  done
  echo "$cfg" | tr -d ' ' | grep -q "$3" || { echo "설정이 기대와 다릅니다: $cfg" >&2; exit 1; }
  python3 scripts/evaluate_agent.py --runs "$RUNS" --label "$2" "${@:4}" | tee "data/ab_${EXP}_$2.out"
  stop_app
}

run_arm "$A_ENV" "$A_LABEL" "$A_CHECK" "$@"
run_arm "$B_ENV" "$B_LABEL" "$B_CHECK" "$@"

A_DIR=$(grep '^버전 기록:' "data/ab_${EXP}_${A_LABEL}.out" | tail -1 | sed 's/^버전 기록: \([^ ]*\).*/\1/')
B_DIR=$(grep '^버전 기록:' "data/ab_${EXP}_${B_LABEL}.out" | tail -1 | sed 's/^버전 기록: \([^ ]*\).*/\1/')
python3 scripts/ab_compare.py "$A_DIR" "$B_DIR" --title "$TITLE" --out "eval/ab_${EXP}.md"
echo "끝. eval/ab_${EXP}.md 와 $A_DIR, $B_DIR 를 커밋하세요."
