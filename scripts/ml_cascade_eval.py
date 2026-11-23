#!/usr/bin/env python3
"""ML 먼저, 애매하면 LLM (실험 기능) — 오프라인 측정과 앱용 모델 내보내기.

규칙 (앱의 qg.agent.ml-triage 와 같음)
- ML 이 '학습한 고장 원인'(히터·냉각·체크 링·압력 센서)을 확률 threshold 이상으로 고르면 LLM 을 부르지 않는다.
- '단발성(NOISE)'이거나 확률이 낮으면 LLM 에이전트가 분석한다. 단발성은 ML 이 확신해도 넘긴다:
  학습하지 않은 고장이 '단발성'으로 들어가 놓치는 것을 막기 위해서다 (ml_cause_report.md 3절).
- 낯선 경보(고른 원인의 학습 경보들과 특징이 많이 다름, Novelty)도 확률과 관계없이 LLM 으로 넘긴다.

측정
1. 경보 단위: LLM 을 건너뛴 비율(= 줄어드는 LLM 호출), 건너뛴 경보의 정확도
2. 처음 보는 고장: 원인 하나를 학습에서 빼면, 그 고장 경보가 '다른 고장'으로 확신돼 LLM 없이 잘못 처리되는가
3. 모델 내보내기(--export-model): 앱이 읽는 JSON (나무 구조). --fixture: 자바 특징·확률이 같은지 보는 시험 데이터

사용: uv run --no-project --with scikit-learn --with numpy python scripts/ml_cascade_eval.py \
        --runs eval/ml/runs --out eval/ml_cascade_report.md \
        --export-model qms-app/src/main/resources/ml/cause-forest.json \
        --fixture qms-app/src/test/resources/ml/parity.json
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestClassifier

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ml_cause_baseline import (BASELINE, CAUSE_KO, FAULT_CAUSE, NCR_CAUSES, SENSORS, RULES, DEFECTS,  # noqa: E402
                               Run, dataset, feature_names, features, pct)

KNOWN_FAULTS = ["HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR", "SENSOR_FAULT"]
THRESHOLDS = [0.7, 0.8, 0.9, 0.95]
DEFAULT_THRESHOLD = 0.9
# 앱에 넣는 모델은 작게: 나무 100개 (300개와 정확도 차이 없음, 파일 크기 1/3)
APP_TREES = 100


def forest(n_trees=300):
    return RandomForestClassifier(n_estimators=n_trees, min_samples_leaf=2, class_weight="balanced",
                                  random_state=0, n_jobs=1)


NOVELTY_PERCENTILE = 99.5   # 학습 경보의 이 백분위보다 멀면 '낯선 경보'
NOVELTY_CLIP = 100.0        # 특징 하나의 z² 상한 (한 특징이 거리를 다 차지하지 않게)


class Novelty:
    """낯선 경보 검사: ML 이 고른 원인의 학습 경보들과 얼마나 다른가.

    원인마다 학습 경보 특징의 평균·표준편차를 두고, 새 경보의 표준화 거리 sqrt(mean(min(z², 100)))를 잰다.
    그 원인 학습 경보들의 거리 99.5 백분위보다 멀면 낯설다고 보고, 확률이 높아도 LLM 으로 넘긴다.
    (학습에 없는 고장이 비슷한 아는 고장으로 분류될 때를 막는 장치, eval/ml_novel_report.md)
    """

    def __init__(self, X, y):
        floor = np.maximum(0.1 * X.std(axis=0), 1e-3)   # 거의 변하지 않는 특징(경보 규칙 등)에서 거리가 터지지 않게
        self.stats = {}
        for c in sorted(set(y)):
            Xc = X[y == c]
            mean, scale = Xc.mean(axis=0), np.maximum(Xc.std(axis=0), floor)
            d = self._dist(Xc, mean, scale)
            self.stats[c] = (mean, scale, float(np.percentile(d, NOVELTY_PERCENTILE)))

    @staticmethod
    def _dist(X, mean, scale):
        z = (np.atleast_2d(X) - mean) / scale
        return np.sqrt(np.mean(np.minimum(z * z, NOVELTY_CLIP), axis=1))

    def distance(self, X, cls):
        return np.array([self._dist(x, *self.stats[c][:2])[0] for x, c in zip(np.atleast_2d(X), cls)])

    def unfamiliar(self, X, cls):
        return np.array([d > self.stats[c][2] for d, c in zip(self.distance(X, cls), cls)])


def skips(cls: str, p: float, t: float, unfamiliar: bool = False) -> bool:
    return cls in KNOWN_FAULTS and p >= t and not unfamiliar


def fit(X, y, n_trees=APP_TREES):
    m = forest(n_trees).fit(X, y)
    m.novelty_ = Novelty(X, y)
    return m


def predict_all(model, X):
    proba = model.predict_proba(X)
    best = proba.argmax(axis=1)
    return model.classes_[best], proba[np.arange(len(X)), best]


def decide(model, X, use_novelty=True):
    """원인, 확률, 낯선 경보 여부."""
    cls, p = predict_all(model, X)
    nov = getattr(model, "novelty_", None)
    unf = nov.unfamiliar(X, cls) if nov is not None and use_novelty else np.zeros(len(cls), dtype=bool)
    return cls, p, unf


def alarm_table(model, splits) -> list[str]:
    out = ["| 시험 | 기준 확률 | 경보 | LLM 건너뜀 (호출 감소) | 건너뛴 경보 정답 | 건너뛴 경보 오답 | LLM 으로 넘김 | 그중 낯선 경보라 넘김 |",
           "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for title, runs in splits:
        X, y, _ = dataset(runs)
        cls, p, unf = decide(model, X)
        for t in THRESHOLDS:
            sk = np.array([skips(c, q, t, u) for c, q, u in zip(cls, p, unf)])
            by_novelty = int(sum(skips(c, q, t) and u for c, q, u in zip(cls, p, unf)))
            right = int(np.sum(sk & (cls == y)))
            wrong = int(np.sum(sk & (cls != y)))
            mark = " **(기본)**" if t == DEFAULT_THRESHOLD else ""
            out.append(f"| {title} | {t}{mark} | {len(y)} | {pct(int(sk.sum()), len(y))} | {right} | {wrong} | {int((~sk).sum())} | {by_novelty} |")
    return out


def case_rows(model, runs, t) -> dict:
    """고장 사례마다 '첫 경보'를 ML 이 건너뛰었는가 (운영에서 사건의 첫 분석이 ML 로 끝나는가)."""
    by = {}
    for run in runs:
        for eq, fault, start, end in run.episodes():
            als = [a for a in run.alarms if a["eq"] == eq and start <= a["shot"] < end and a["shot"] > BASELINE]
            if not als:
                continue
            x = np.array([features(run, als[0])])
            cls, p, unf = decide(model, x)
            cause = FAULT_CAUSE[fault]
            r = by.setdefault(cause, Counter())
            r["n"] += 1
            if skips(cls[0], p[0], t, unf[0]):
                r["skip_ok" if cls[0] == cause else "skip_wrong"] += 1
            else:
                r["llm"] += 1
    return by


def novel_table(X, y, runs, t) -> list[str]:
    out = ["| 뺀 원인 (처음 보는 고장) | 처음 5경보 | ML 이 다른 고장으로 확신 → LLM 없이 오답 | 단발성 등 → LLM 으로 넘김 | 사례 중 첫 분석이 ML 오답으로 끝난 사례 |",
           "|---|---:|---:|---:|---:|"]
    harm_total = 0
    for held in KNOWN_FAULTS:
        keep = y != held
        m = fit(X[keep], y[keep], 300)
        n = wrong = passed = 0
        cases = first_wrong = 0
        for run in runs:
            for eq, fault, start, end in run.episodes():
                if FAULT_CAUSE[fault] != held:
                    continue
                als = [a for a in run.alarms if a["eq"] == eq and start <= a["shot"] < end and a["shot"] > BASELINE][:5]
                if not als:
                    continue
                cases += 1
                cls, p, unf = decide(m, np.array([features(run, a) for a in als]))
                for i, (c, q, u) in enumerate(zip(cls, p, unf)):
                    n += 1
                    if skips(c, q, t, u):
                        wrong += 1
                        if i == 0:
                            first_wrong += 1
                    else:
                        passed += 1
        harm_total += wrong
        out.append(f"| {CAUSE_KO[held]} | {n} | {wrong} | {passed} | {first_wrong}/{cases} |")
    return out, harm_total


def export_model(model, path: Path):
    trees = []
    for est in model.estimators_:
        tr = est.tree_
        value = tr.value[:, 0, :]
        value = value / value.sum(axis=1, keepdims=True)    # 잎의 클래스 비율 (sklearn predict_proba 와 같은 정규화)
        trees.append({
            "left": tr.children_left.tolist(),
            "right": tr.children_right.tolist(),
            "feature": tr.feature.tolist(),
            "threshold": [float(v) if f >= 0 else 0 for v, f in zip(tr.threshold, tr.feature)],   # 반올림하면 경계 값에서 갈림이 바뀐다
            "value": [[round(float(v), 6) for v in row] if f < 0 else [] for row, f in zip(value, tr.feature)],
        })
    doc = {
        "kind": "random-forest",
        "createdAt": f"{datetime.now():%Y-%m-%d}",
        "trainedOn": "simulator seeds 101-130 (scripts/ml_baseline.sh)",
        "baselineShots": BASELINE,
        "sensors": SENSORS, "rules": RULES, "defects": DEFECTS,
        "features": feature_names(),
        "classes": [str(c) for c in model.classes_],
        "trees": trees,
        # 낯선 경보 검사 (Novelty): 원인별 학습 경보 특징의 평균·척도와 거리 한계
        "novelty": {"percentile": NOVELTY_PERCENTILE, "clip": NOVELTY_CLIP,
                    "classes": {str(c): {"mean": [float(v) for v in st[0]], "scale": [float(v) for v in st[1]],
                                         "limit": st[2]} for c, st in model.novelty_.stats.items()}},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")
    return path.stat().st_size


def export_fixture(model, run: Run, path: Path, per_class=6):
    """자바 시험용: 한 seed 의 경보(원인별 최대 per_class 개)와 그 설비의 shot 데이터, 파이썬이 낸 특징·확률.
    자바는 같은 shot 데이터로 특징·확률을 다시 계산해 비교한다 (CauseFeaturesParityTest)."""
    picked, seen = [], Counter()
    for al in [a for a in run.alarms if a["shot"] > BASELINE]:
        lab = run.label(al["eq"], al["shot"])
        if seen[lab] < per_class:
            seen[lab] += 1
            picked.append(al)
    rows = []
    for al in picked:
        f = features(run, al)
        proba = model.predict_proba(np.array([f]))[0]
        best = str(model.classes_[int(np.argmax(proba))])
        rows.append({"eq": al["eq"], "shot": al["shot"], "rule": al["rule"], "sensor": al["sensor"],
                     "label": run.label(al["eq"], al["shot"]),
                     "features": [float(v) for v in f], "proba": [float(v) for v in proba],
                     "novelty": float(model.novelty_.distance(np.array([f]), [best])[0])})
    shots = {}
    for eq in sorted({a["eq"] for a in picked}):
        upto = max(a["shot"] for a in picked if a["eq"] == eq)
        arr = []
        for shot in range(1, upto + 1):
            v = run.values[eq].get(shot)
            if v is None:
                continue
            ins = run.inspect[eq].get(shot)
            arr.append([shot, [v.get(k) for k in SENSORS], None if ins is None else ("NG" if ins[0] else "OK"),
                        None if ins is None else ins[1]])
        shots[eq] = arr
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"run": run.name, "classes": [str(c) for c in model.classes_], "alarms": rows,
                                "shots": shots}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default="eval/ml/runs")
    ap.add_argument("--out", default="eval/ml_cascade_report.md")
    ap.add_argument("--export-model")
    ap.add_argument("--fixture")
    args = ap.parse_args()
    root = Path(args.runs)

    def load(split):
        return [Run(p) for p in sorted((root / split).glob("seed-*"), key=lambda p: int(p.name.split("-")[1]))]

    train, test_all, shifted = load("train"), load("test"), load("shifted")
    test = [r for r in test_all if not r.name.endswith("seed-42")]
    seed42 = [r for r in test_all if r.name.endswith("seed-42")]
    X, y, _ = dataset(train)
    model = fit(X, y)     # 앱에 넣는 것과 같은 모델로 잰다 (낯선 경보 검사 포함)

    splits = [("같은 시나리오 (seed 1~5)", test), ("seed 42", seed42), ("고장 배치 변경 (seed 1~5)", shifted)]
    L = ["# ML 먼저, 애매하면 LLM — 실험 측정", ""]
    L.append(f"`./scripts/ml_cascade.sh` 로 다시 만들 수 있습니다 (LLM 없이). 생성: {datetime.now():%Y-%m-%d %H:%M}. "
             f"모델: 랜덤 포레스트 나무 {APP_TREES}개, 학습 경보 {len(y)}건 (seed 101~130). 앱에 넣은 모델과 같습니다.")
    L.append("")
    L.append("> 시뮬레이터 합성 데이터입니다. LLM 을 건너뛴 경보의 정답률은 ML 만의 정답률이고, LLM 으로 넘긴 경보는 기존 에이전트 평가 수치를 따릅니다.")
    L.append("")
    L.append("## 규칙")
    L.append("")
    L.append(f"- ML 이 **학습한 고장 원인**(히터 열화·냉각 막힘·체크 링 마모·압력 센서 이상)을 확률 **{DEFAULT_THRESHOLD} 이상**으로 고르면 LLM 을 부르지 않습니다. "
             "이때도 같은 조회 도구(SPC 요약·센서 추이·불량 요약)로 실제 수치를 근거로 붙이고, 검증 에이전트와 사람 승인을 그대로 거칩니다.")
    L.append("- **단발성(NOISE)** 은 ML 이 확신해도 LLM 으로 넘깁니다. 학습하지 않은 고장은 '단발성'으로 들어가기 때문입니다 (ml_cause_report.md 3절).")
    L.append("- 확률이 낮으면 LLM 으로 넘깁니다. 검증 에이전트가 되돌리면 두 번째 시도는 LLM 이 합니다.")
    L.append(f"- **낯선 경보 검사**: ML 이 고른 원인의 학습 경보들과 특징이 많이 다르면(그 원인 학습 경보 거리의 {NOVELTY_PERCENTILE} 백분위 초과) 확률이 높아도 LLM 으로 넘깁니다.")
    L.append("- 표본 점검(앱 설정 audit-rate): LLM 을 건너뛸 사건 중 일부는 LLM 이 분석하고 ML 답과 비교해 기록합니다 (이 보고서의 수치에는 넣지 않음).")
    L.append("")
    L.append("## 1. 경보 단위: LLM 호출이 얼마나 줄고, 건너뛴 경보는 맞았나")
    L.append("")
    L += alarm_table(model, splits)
    L.append("")
    L.append("## 2. 고장 사례의 첫 분석 (기준 확률 " + str(DEFAULT_THRESHOLD) + ")")
    L.append("")
    L.append("| 시험 | 원인 | 사례 | 첫 분석을 ML 이 끝냄 (정답) | ML 오답으로 끝냄 | LLM 이 분석 |")
    L.append("|---|---|---:|---:|---:|---:|")
    for title, runs in splits:
        by = case_rows(model, runs, DEFAULT_THRESHOLD)
        for cause in KNOWN_FAULTS:
            r = by.get(cause)
            if r:
                L.append(f"| {title} | {CAUSE_KO[cause]} | {r['n']} | {r['skip_ok']} | {r['skip_wrong']} | {r['llm']} |")
    L.append("")
    L.append("## 3. 처음 보는 고장: 놓치거나 잘못 처리되는가")
    L.append("")
    L.append("원인 하나를 학습에서 빼고(seed 1~5), 그 고장의 처음 5경보를 규칙대로 처리했습니다. "
             "'LLM 없이 오답'이 이 방식의 위험입니다. 단발성으로 분류된 경보는 LLM 으로 넘어가므로 놓치지 않습니다.")
    L.append("")
    harms = {}
    for t in [DEFAULT_THRESHOLD, 0.7]:
        tbl, harm = novel_table(X, y, test, t)
        harms[t] = harm
        L.append(f"**기준 확률 {t}**")
        L.append("")
        L += tbl
        L.append("")
    L.append("## 해석")
    L.append("")
    L.append("- 학습한 고장이 대부분인 공장이면 LLM 호출이 크게 줄어듭니다 (1절). 줄어드는 만큼 분석 시간(평균 44초 → 수십 ms + 도구 조회)과 Gemini 비용이 줄어듭니다.")
    L.append("- 단발성 경보와 확신이 낮은 경보는 LLM 이 그대로 봅니다. 처음 보는 고장은 대부분 이쪽으로 넘어갑니다 (3절).")
    L.append(f"- 위험은 '처음 보는 고장을 다른 학습된 고장으로 확신'하는 경우입니다. 기준 확률 {DEFAULT_THRESHOLD} 에서 {harms[DEFAULT_THRESHOLD]}건, 0.7 에서 {harms[0.7]}건이었습니다. "
             "이 경우에도 결과는 초안일 뿐이고 사람이 승인하기 전에는 확정되지 않습니다.")
    L.append("- 그래서 앱에서는 **기본 꺼짐**(`qg.agent.ml-triage.mode=off`)으로 두고, 먼저 `shadow`(ML 판단을 기록만, LLM 은 그대로)로 실제 경보에서 일치율을 본 뒤 `skip` 을 켜는 순서를 권합니다.")
    Path(args.out).write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))

    if args.export_model:
        size = export_model(model, Path(args.export_model))
        print(f"\n모델 저장: {args.export_model} ({size / 1024:.0f} KB)")
    if args.fixture:
        export_fixture(model, seed42[0], Path(args.fixture))
        print(f"시험 데이터 저장: {args.fixture}")


if __name__ == "__main__":
    main()
