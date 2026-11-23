#!/usr/bin/env bash
# 학습에 없는 '새 고장'을 섞은 무작위 시나리오로 "ML 먼저, 애매하면 LLM" 을 시험한다 → eval/ml_novel_report.md
#
# 1) 무작위 시나리오: 설비 2대 × 고장 3개씩 = 고장 6개, 시점·길이·세기(0.6~1.4배) 무작위
#    아는 고장 : 새 고장 = 5:1, 4:2, 3:3 세 가지 섞음 × seed 1~5
#    새 고장 = 원료 수분 과다, 노즐 막힘, 금형 파팅면 마모 (simulator/qg_simulator/faults.py, ML 학습에 없음)
# 2) 운영과 같은 SpcEngine 으로 경보를 뽑는다 (./gradlew alarmExport)
# 3) 앱과 같은 모델(scripts/ml_cascade_eval.py 와 같은 학습)로 규칙을 적용해 LLM 생략률·자신 있게 틀린 경보를 잰다
# LLM·DB·Docker 는 쓰지 않는다. 학습 데이터(eval/ml/runs/train)가 없으면 scripts/ml_baseline.sh 를 먼저 돌린다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/eval/ml/novel"
if [ ! -d "$ROOT/eval/ml/runs/train" ]; then
  "$ROOT/scripts/ml_baseline.sh"
fi
rm -rf "$OUT"
mkdir -p "$OUT"

uv run -q --no-project --with pyyaml python "$ROOT/scripts/ml_novel_eval.py" scenarios \
  --base "$ROOT/simulator/scenarios.yaml" --out "$OUT"

DIRS=()
for cfg in "$OUT"/*/seed-*/scenario.yaml; do
  dir="$(dirname "$cfg")"
  seed="${dir##*seed-}"
  (cd "$ROOT/simulator" && uv run -q qg-simulator --out "$dir" --seed "$seed" --config "$cfg" >/dev/null)
  DIRS+=("$dir")
done
echo "시뮬레이터: ${#DIRS[@]}개 실행 완료"

(cd "$ROOT/qms-app" && ./gradlew -q alarmExport --args="${DIRS[*]}" | tail -3)

uv run -q --no-project --with scikit-learn --with numpy --with pyyaml python "$ROOT/scripts/ml_novel_eval.py" evaluate \
  --train "$ROOT/eval/ml/runs" --novel "$OUT" --out "$ROOT/eval/ml_novel_report.md"
