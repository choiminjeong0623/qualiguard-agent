#!/usr/bin/env bash
# 탐지 평가(로드맵 E2)용 시뮬레이터 파일을 seed 여러 개로 만든다. ./gradlew detectionEval 이 먼저 부른다.
#   사용: scripts/detection_data.sh [seed 개수, 기본 5]
# 결과: eval/detection/runs/seed-N/{telemetry.jsonl, ground_truth.csv, ground_truth.meta.json} (커밋하지 않음)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SEEDS="${1:-5}"
OUT="$ROOT/eval/detection/runs"
rm -rf "$OUT"
mkdir -p "$OUT"
cd "$ROOT/simulator"
for seed in $(seq 1 "$SEEDS"); do
  if command -v uv >/dev/null 2>&1; then
    uv run -q qg-simulator --out "$OUT/seed-$seed" --seed "$seed" | tail -2
  else
    python3 -m qg_simulator --out "$OUT/seed-$seed" --seed "$seed" | tail -2
  fi
done
