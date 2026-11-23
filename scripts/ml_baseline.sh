#!/usr/bin/env bash
# ML 원인 분류 기준선 vs LLM 에이전트 비교 데이터 만들기 + 학습·채점 → eval/ml_cause_report.md
#
#   scripts/ml_baseline.sh            # 학습 seed 30개, 시험 seed 1~5·42, 고장 위치를 바꾼 시나리오 seed 1~5
#
# 1) 시뮬레이터를 파일 출력으로 여러 번 돌린다 (eval/ml/runs/, 커밋하지 않음)
#    - train/seed-101..130 : 학습용 (scenarios.yaml 그대로)
#    - test/seed-1..5, 42  : 시험용 (같은 시나리오, 다른 잡음). 42 는 에이전트 재생 평가와 같은 seed
#    - shifted/seed-1..5   : 고장을 다른 설비·다른 시점으로 옮긴 시나리오 (학습 때 본 적 없는 배치)
# 2) 운영과 같은 SpcEngine 으로 경보를 뽑는다 (./gradlew alarmExport → seed 폴더마다 alarms.csv)
# 3) scripts/ml_cause_baseline.py 가 경보 시점까지의 데이터로 특징을 만들고 랜덤 포레스트를 학습·채점한다
# LLM·DB·Docker 는 쓰지 않는다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/eval/ml/runs"
rm -rf "$OUT"
mkdir -p "$OUT"/{train,test,shifted}

# 고장 배치를 바꾼 설정 파일 (설비를 맞바꾸고 시작 시점도 옮김)
SHIFTED="$OUT/scenarios_shifted.yaml"
uv run -q --no-project --with pyyaml python - "$ROOT/simulator/scenarios.yaml" "$SHIFTED" <<'PY'
import sys, yaml
cfg = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
cfg["faults"] = [
    {"name": "cooling_blockage",      "equipment": "IMM-01", "start_shot": 200,  "duration": 500},
    {"name": "heater_degradation",    "equipment": "IMM-02", "start_shot": 500,  "duration": 500},
    {"name": "pressure_sensor_spike", "equipment": "IMM-01", "start_shot": 1000, "duration": 200},
    {"name": "check_ring_wear",       "equipment": "IMM-02", "start_shot": 1300, "duration": 600},
]
yaml.safe_dump(cfg, open(sys.argv[2], "w", encoding="utf-8"), allow_unicode=True, sort_keys=False)
PY

sim() {  # sim <폴더> <seed> [설정 파일]
  (cd "$ROOT/simulator" && uv run -q qg-simulator --out "$1" --seed "$2" ${3:+--config "$3"} >/dev/null)
}
DIRS=()
for s in $(seq 101 130); do sim "$OUT/train/seed-$s" "$s"; DIRS+=("$OUT/train/seed-$s"); done
for s in 1 2 3 4 5 42; do sim "$OUT/test/seed-$s" "$s"; DIRS+=("$OUT/test/seed-$s"); done
for s in 1 2 3 4 5; do sim "$OUT/shifted/seed-$s" "$s" "$SHIFTED"; DIRS+=("$OUT/shifted/seed-$s"); done
echo "시뮬레이터: ${#DIRS[@]}개 실행 완료"

(cd "$ROOT/qms-app" && ./gradlew -q alarmExport --args="${DIRS[*]}" | tail -3)

uv run -q --no-project --with scikit-learn --with numpy python "$ROOT/scripts/ml_cause_baseline.py" \
  --runs "$OUT" --out "$ROOT/eval/ml_cause_report.md"
