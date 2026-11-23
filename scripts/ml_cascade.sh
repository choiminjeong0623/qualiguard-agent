#!/usr/bin/env bash
# ML 먼저, 애매하면 LLM (실험 기능) — 측정과 앱용 모델 만들기.
#   1) eval/ml/runs 가 없으면 scripts/ml_baseline.sh 로 시뮬레이터 데이터·경보를 만든다
#   2) LLM 을 건너뛰는 비율·정확도·처음 보는 고장 처리를 재서 eval/ml_cascade_report.md 에 쓴다
#   3) 앱이 읽는 모델(qms-app/src/main/resources/ml/cause-forest.json)과 자바 비교 시험 데이터를 다시 만든다
# 사용: ./scripts/ml_cascade.sh   (LLM·DB 불필요, 약 1~2분)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -d "$ROOT/eval/ml/runs/train" ]; then
  "$ROOT/scripts/ml_baseline.sh"
fi
uv run -q --no-project --with scikit-learn --with numpy python "$ROOT/scripts/ml_cascade_eval.py" \
  --runs "$ROOT/eval/ml/runs" --out "$ROOT/eval/ml_cascade_report.md" \
  --export-model "$ROOT/qms-app/src/main/resources/ml/cause-forest.json" \
  --fixture "$ROOT/qms-app/src/test/resources/ml/parity.json"
echo "자바 계산이 같은지: (cd qms-app && ./gradlew test --tests '*CauseFeaturesParityTest')"
