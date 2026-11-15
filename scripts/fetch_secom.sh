#!/bin/bash
# UCI SECOM 데이터(반도체 공정 센서 590개 × 1,567건, 합격/불합격 라벨)를 data/secom/ 에 받는다.
# 데이터는 커밋하지 않는다 (data/ 는 .gitignore). 출처: https://archive.ics.uci.edu/dataset/179/secom (CC BY 4.0)
#   ./scripts/fetch_secom.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/secom
BASE=https://archive.ics.uci.edu/ml/machine-learning-databases/secom
for f in secom.data secom_labels.data secom.names; do
  if [ ! -s "data/secom/$f" ]; then
    curl -fsSL --retry 3 -o "data/secom/$f" "$BASE/$f"
  fi
done
wc -l data/secom/secom.data data/secom/secom_labels.data
