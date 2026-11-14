#!/bin/bash
# Gemini API 키와 모델이 동작하는지 점검한다. 키 값은 화면에 출력하지 않는다.
#   ./scripts/check_gemini.sh                # .env 의 GEMINI_MODEL (없으면 gemini-2.5-flash)
#   ./scripts/check_gemini.sh gemini-3.5-flash
set -uo pipefail
cd "$(dirname "$0")/.."
KEY=$(grep -E '^GEMINI_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]')
ENV_MODEL=$(grep -E '^GEMINI_MODEL=' .env | head -1 | cut -d= -f2- | tr -d '[:space:]')
MODEL=${1:-${ENV_MODEL:-gemini-3.8-flash}}
BASE=https://generativelanguage.googleapis.com/v1beta

echo "== 키 형식: 앞 4자리 '${KEY:0:4}', 길이 ${#KEY}자 (Gemini Developer API 키는 보통 'AIza' 로 시작)"
echo
echo "== 1) 이 키로 쓸 수 있는 모델 목록 (flash 계열)"
code=$(curl -s -o /tmp/qg_models.json -w '%{http_code}' -H "x-goog-api-key: $KEY" "$BASE/models?pageSize=200")
echo "HTTP $code"
if [ "$code" = "200" ]; then
  python3 -c "
import json; d=json.load(open('/tmp/qg_models.json'))
names=[m['name'].split('/')[-1] for m in d.get('models',[]) if 'generateContent' in m.get('supportedGenerationMethods',[])]
print('\n'.join(n for n in names if 'flash' in n) or '(flash 모델 없음)')"
else
  python3 -c "import json; d=json.load(open('/tmp/qg_models.json')); print(d.get('error',{}).get('status'), '-', d.get('error',{}).get('message'))" 2>/dev/null || cat /tmp/qg_models.json
fi
echo
echo "== 2) '$MODEL' 모델로 간단한 생성 요청"
code=$(curl -s -o /tmp/qg_gen.json -w '%{http_code}' -H "x-goog-api-key: $KEY" -H 'Content-Type: application/json' \
  -d '{"contents":[{"parts":[{"text":"한 단어로 대답: 안녕"}]}]}' "$BASE/models/$MODEL:generateContent")
echo "HTTP $code"
python3 -c "
import json; d=json.load(open('/tmp/qg_gen.json'))
if 'error' in d: print('오류:', d['error'].get('status'), '-', d['error'].get('message'))
else: print('응답:', d['candidates'][0]['content']['parts'][0]['text'].strip())" 2>/dev/null || cat /tmp/qg_gen.json
rm -f /tmp/qg_models.json /tmp/qg_gen.json
