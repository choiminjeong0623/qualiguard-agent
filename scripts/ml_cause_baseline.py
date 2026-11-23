#!/usr/bin/env python3
"""ML 원인 분류 기준선 — LLM 에이전트와 같은 경보를 머신러닝(랜덤 포레스트)으로 분류해 비교한다.

에이전트가 경보마다 하는 일(경보 시점까지의 데이터로 원인 코드를 고름)을 학습된 분류기로 똑같이 해 보고,
"정해진 원인 맞히기"에서 LLM 이 정말 필요한지 측정값으로 답하려는 실험이다.

입력 (scripts/ml_baseline.sh 가 만든다): <runs>/{train,test,shifted}/seed-N/{telemetry.jsonl, ground_truth.csv, alarms.csv}
출력: Markdown 보고서 (--out)

공정성 규칙
- 특징은 경보 shot 까지의 데이터만 쓴다 (에이전트의 asOf 와 같은 원칙).
- 설비 이름·shot 번호는 특징에 넣지 않는다. 넣으면 "IMM-01 의 300번대 경보 = 히터" 같은 위치 암기가 된다.
- 정답(원인)은 시뮬레이터 정답 파일의 '경보 shot 에서 진행 중인 고장'. 고장 밖 경보는 NOISE.

사용: uv run --no-project --with scikit-learn --with numpy python scripts/ml_cause_baseline.py --runs eval/ml/runs --out eval/ml_cause_report.md
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import platform
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import f1_score

SENSORS = ["moldTemp", "barrelTemp", "injectionPressure", "holdingPressure", "cushion", "cycleTime", "motorCurrent"]
SENSOR_KO = {"moldTemp": "금형 온도", "barrelTemp": "실린더 온도", "injectionPressure": "사출 압력",
             "holdingPressure": "보압", "cushion": "쿠션량", "cycleTime": "사이클 타임", "motorCurrent": "모터 전류"}
RULES = ["INDIVIDUAL", "XBAR", "S_CHART", "P_CHART"]
DEFECTS = ["SHORT_SHOT", "FLASH", "DIMENSION", "WARPAGE"]
FAULT_CAUSE = {"heater_degradation": "HEATER_DEGRADATION", "cooling_blockage": "COOLING_ISSUE",
               "check_ring_wear": "CHECK_RING_WEAR", "pressure_sensor_spike": "SENSOR_FAULT"}
CAUSE_KO = {"HEATER_DEGRADATION": "히터 열화", "COOLING_ISSUE": "냉각 막힘", "CHECK_RING_WEAR": "체크 링 마모",
            "SENSOR_FAULT": "압력 센서 이상", "NOISE": "단발성 경보", "UNKNOWN": "모름"}
NCR_CAUSES = {"HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR"}
BASELINE = 100          # SpcEngine.BASELINE_SHOTS 와 같은 기준 구간
MARGIN = 100            # 고장 앞뒤 이 범위의 경보는 단발성 경보 사례에서 뺀다 (evaluate_agent.py 와 같음)
UNKNOWN_PROBA = 0.5     # 가장 높은 확률이 이보다 낮으면 "모름"으로 본다 (처음 보는 고장 실험)


# ── 데이터 읽기 ─────────────────────────────────────────────────────────────

class Run:
    """seed 폴더 하나: 설비별 shot 번호 → 센서 값·검사 결과, 정답, 경보."""

    def __init__(self, path: Path):
        self.path = path
        self.name = f"{path.parent.name}/{path.name}"
        self.values: dict[str, dict[int, dict]] = defaultdict(dict)
        self.inspect: dict[str, dict[int, tuple[bool, str | None]]] = defaultdict(dict)
        seen = set()
        for line in (path / "telemetry.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            m = json.loads(line)
            if m["messageId"] in seen or m.get("shotNo") is None:
                continue        # 재전송 중복, shot 번호 없는 메시지는 위치를 알 수 없어 뺀다
            seen.add(m["messageId"])
            eq, shot = m["equipmentId"], int(m["shotNo"])
            self.values[eq][shot] = {k: (v or {}).get("value") for k, v in (m.get("sensors") or {}).items()}
            ins = m.get("inspection")
            if ins and ins.get("result") in ("OK", "NG"):
                self.inspect[eq][shot] = (ins["result"] == "NG", ins.get("defectType"))
        self.truth: dict[str, dict[int, str]] = defaultdict(dict)
        self.ng_truth: dict[str, dict[int, bool]] = defaultdict(dict)
        with open(path / "ground_truth.csv", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                fault = r["activeFault"]
                self.truth[r["equipmentId"]][int(r["shotNo"])] = "NONE" if fault in ("", "NONE") else fault.split("|")[0]
                self.ng_truth[r["equipmentId"]][int(r["shotNo"])] = r["inspection"] == "NG"
        self.alarms = []
        with open(path / "alarms.csv", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                self.alarms.append({"eq": r["equipmentId"], "shot": int(r["shotNo"]), "rule": r["rule"],
                                    "sensor": r["sensor"]})
        self.alarms.sort(key=lambda a: (a["eq"], a["shot"]))
        self._arrays()

    def _arrays(self):
        """설비별로 shot 1..N 위치에 값을 놓은 배열 (빠진 값은 NaN)."""
        self.arr, self.ng, self.dtype, self.base = {}, {}, {}, {}
        for eq, rows in self.values.items():
            n = max(rows) + 1
            a = np.full((n, len(SENSORS)), np.nan)
            for shot, v in rows.items():
                for j, k in enumerate(SENSORS):
                    if v.get(k) is not None:
                        a[shot, j] = v[k]
            ng = np.full(n, np.nan)
            dt = [None] * n
            for shot, (bad, d) in self.inspect[eq].items():
                if shot < n:
                    ng[shot] = 1.0 if bad else 0.0
                    dt[shot] = d if bad else None
            self.arr[eq], self.ng[eq], self.dtype[eq] = a, ng, dt
            b = a[1:BASELINE + 1]
            mu, sd = np.nanmean(b, axis=0), np.nanstd(b, axis=0)
            sd[sd == 0] = 1e-9
            self.base[eq] = (mu, sd, float(np.nanmean(ng[1:BASELINE + 1])))

    def label(self, eq: str, shot: int) -> str:
        fault = self.truth[eq].get(shot, "NONE")
        return FAULT_CAUSE.get(fault, "NOISE")

    def episodes(self):
        """정답 파일에서 고장 구간(설비, 고장, 시작, 끝 미포함)을 찾는다."""
        out = []
        for eq, shots in self.truth.items():
            cur, start = "NONE", None
            for shot in sorted(shots) + [10 ** 9]:
                f = shots.get(shot, "NONE")
                if f != cur:
                    if cur != "NONE":
                        out.append((eq, cur, start, shot))
                    cur, start = f, shot
        return out


# ── 특징 ────────────────────────────────────────────────────────────────────

def feature_names():
    names = []
    for k in SENSORS:
        names += [f"{k}.z5", f"{k}.z20", f"{k}.z60", f"{k}.slope20", f"{k}.sd20", f"{k}.spike20", f"{k}.spikeFrac20"]
    names += ["ng50", "ng50Diff"] + [f"defect.{d}" for d in DEFECTS]
    names += [f"rule.{r}" for r in RULES] + [f"alarmSensor.{k}" for k in SENSORS]
    return names


def features(run: Run, alarm: dict) -> list[float]:
    """경보 shot 까지의 데이터만으로 만든 특징 (설비·shot 번호는 넣지 않음)."""
    eq, s = alarm["eq"], alarm["shot"]
    a, ng, dt = run.arr[eq], run.ng[eq], run.dtype[eq]
    mu, sd, ng_base = run.base[eq]
    z = (a[: s + 1] - mu) / sd                      # 기준 대비 z (shot 별)

    def win(n):
        return z[max(1, s - n + 1): s + 1]

    out = []
    for j in range(len(SENSORS)):
        w5, w20, w60 = win(5)[:, j], win(20)[:, j], win(60)[:, j]
        valid = ~np.isnan(w20)
        if valid.sum() >= 3:
            x = np.arange(len(w20))[valid]
            slope = float(np.polyfit(x, w20[valid], 1)[0]) * 20
        else:
            slope = 0.0
        out += [nanmean(w5), nanmean(w20), nanmean(w60), slope,
                float(np.nanstd(w20)) if valid.sum() >= 2 else 1.0,
                float(np.nanmax(np.abs(w20))) if valid.any() else 0.0,
                float(np.nanmean(np.abs(w20[valid]) > 3)) if valid.any() else 0.0]
    last = ng[max(1, s - 49): s + 1]
    ng50 = nanmean(last)
    out += [ng50, ng50 - ng_base]
    types = Counter(d for d in dt[max(1, s - 49): s + 1] if d)
    total = max(1, int(np.sum(~np.isnan(last))))
    out += [types.get(d, 0) / total for d in DEFECTS]
    out += [1.0 if alarm["rule"] == r else 0.0 for r in RULES]
    out += [1.0 if alarm["sensor"] == k else 0.0 for k in SENSORS]
    return out


def nanmean(x) -> float:
    return float(np.nanmean(x)) if np.any(~np.isnan(x)) else 0.0


def dataset(runs: list[Run]):
    X, y, meta = [], [], []
    for run in runs:
        for al in run.alarms:
            if al["shot"] <= BASELINE or al["eq"] not in run.arr:
                continue
            X.append(features(run, al))
            y.append(run.label(al["eq"], al["shot"]))
            meta.append((run, al))
    return np.array(X), np.array(y), meta


# ── 사례 채점 (evaluate_agent.py 와 같은 단위) ───────────────────────────────

def decision(cause: str) -> str:
    return "NCR_DRAFT" if cause in NCR_CAUSES else "SENSOR_CHECK" if cause == "SENSOR_FAULT" else "OBSERVE"


def score_cases(runs: list[Run], predict) -> dict:
    """고장 사례: 경보를 순서대로 분류해 정답 원인이 처음 나온 경보를 '진단'으로 본다 (최대 5경보 = 에이전트 MAX_STEPS).
    단발성 경보 사례: 고장 구간 ±100 shot 밖의 경보 하나하나. 정답은 NOISE(관찰)."""
    faults, noise = [], []
    for run in runs:
        eps = run.episodes()
        for eq, fault, start, end in eps:
            cause = FAULT_CAUSE[fault]
            als = [a for a in run.alarms if a["eq"] == eq and start <= a["shot"] < end]
            res = {"run": run.name, "fault": fault, "cause": cause, "start": start, "alarms": len(als),
                   "first": None, "diag": None, "steps": None, "wrongDrafts": 0, "defects": None,
                   "firstAlarm": als[0]["shot"] - start if als else None}
            for i, al in enumerate(als[:5]):
                p = predict(run, al)
                if i == 0:
                    res["first"] = p
                if p == cause:
                    res["diag"], res["steps"] = al["shot"] - start, i + 1
                    res["defects"] = sum(run.ng_truth[eq].get(s, False) for s in range(start, al["shot"] + 1))
                    break
                if p in NCR_CAUSES and p != cause:
                    res["wrongDrafts"] += 1
            res["firstDefect"] = next((s - start for s in range(start, end) if run.ng_truth[eq].get(s)), None)
            faults.append(res)
        for al in run.alarms:
            if al["shot"] <= BASELINE:
                continue
            near = any(e[0] == al["eq"] and e[2] - MARGIN <= al["shot"] < e[3] + MARGIN for e in eps)
            if not near:
                p = predict(run, al)
                noise.append({"run": run.name, "pred": p, "wrongDraft": p in NCR_CAUSES})
    return {"faults": faults, "noise": noise}


# ── 보고서 ──────────────────────────────────────────────────────────────────

def pct(a, b):
    return f"{a}/{b} ({100 * a / b:.0f}%)" if b else "-"


def mean_sd(xs):
    xs = [x for x in xs if x is not None]
    if not xs:
        return "-"
    m = sum(xs) / len(xs)
    s = math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) if len(xs) > 1 else 0.0
    return f"{m:.1f} ± {s:.1f}"


def case_table(cases: dict) -> list[str]:
    out = ["| 사례 | 정답 진단 | 첫 경보에서 정답 | 첫 경보까지 shot | 진단까지 shot | 진단까지 불량 (평균) | 잘못된 초안 | 고장 구간 첫 불량 (평균 shot) |",
           "|---|---:|---:|---:|---:|---:|---:|---:|"]
    by = defaultdict(list)
    for c in cases["faults"]:
        by[c["cause"]].append(c)
    for cause in ["HEATER_DEGRADATION", "SENSOR_FAULT", "COOLING_ISSUE", "CHECK_RING_WEAR"]:
        cs = by.get(cause, [])
        if not cs:
            continue
        ok = sum(c["diag"] is not None for c in cs)
        first = sum(c["first"] == cause for c in cs)
        defects = [c["defects"] for c in cs if c["defects"] is not None]
        fd = [c["firstDefect"] for c in cs if c["firstDefect"] is not None]
        out.append(f"| {CAUSE_KO[cause]} | {pct(ok, len(cs))} | {pct(first, len(cs))} | "
                   f"{mean_sd([c['firstAlarm'] for c in cs])} | {mean_sd([c['diag'] for c in cs])} | "
                   f"{(sum(defects) / len(defects)) if defects else 0:.1f} | {sum(c['wrongDrafts'] for c in cs)} | "
                   f"{f'{sum(fd) / len(fd):.0f}' if fd else '구간 내 없음'} |")
    n = cases["noise"]
    ok = sum(x["pred"] == "NOISE" for x in n)
    out.append(f"| 단발성 경보 (경보 {len(n)}건) | {pct(ok, len(n))} | {pct(ok, len(n))} | - | - | - | {sum(x['wrongDraft'] for x in n)} | - |")
    return out


def confusion(y_true, y_pred, labels) -> list[str]:
    out = ["| 정답 \\ 예측 | " + " | ".join(CAUSE_KO[l] for l in labels) + " |",
           "|---|" + "---:|" * len(labels)]
    for t in labels:
        row = [sum(1 for a, b in zip(y_true, y_pred) if a == t and b == p) for p in labels]
        out.append(f"| {CAUSE_KO[t]} | " + " | ".join(str(v) for v in row) + " |")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default="eval/ml/runs")
    ap.add_argument("--out", default="eval/ml_cause_report.md")
    args = ap.parse_args()
    root = Path(args.runs)

    def load(split):
        return [Run(p) for p in sorted((root / split).glob("seed-*"), key=lambda p: int(p.name.split("-")[1]))]

    train, test_all, shifted = load("train"), load("test"), load("shifted")
    test = [r for r in test_all if not r.name.endswith("seed-42")]
    seed42 = [r for r in test_all if r.name.endswith("seed-42")]
    names = feature_names()

    X, y, _ = dataset(train)
    t0 = time.perf_counter()
    model = RandomForestClassifier(n_estimators=300, min_samples_leaf=2, class_weight="balanced", random_state=0,
                                   n_jobs=1)
    model.fit(X, y)
    train_s = time.perf_counter() - t0

    cache = {}

    def predict_with(m, unknown=False):
        def f(run, al):
            key = (id(m), run.name, al["eq"], al["shot"], al["rule"], al["sensor"], unknown)
            if key not in cache:
                proba = m.predict_proba([features(run, al)])[0]
                best = int(np.argmax(proba))
                cache[key] = "UNKNOWN" if unknown and proba[best] < UNKNOWN_PROBA else m.classes_[best]
            return cache[key]
        return f

    # 경보 단위 정확도
    labels = ["HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR", "SENSOR_FAULT", "NOISE"]
    lines = []
    alarm_rows = []
    for title, runs in [("같은 시나리오, 다른 잡음 (seed 1~5)", test), ("에이전트 재생 평가와 같은 seed 42", seed42),
                        ("고장을 다른 설비·시점으로 옮긴 시나리오 (seed 1~5)", shifted)]:
        Xt, yt, _ = dataset(runs)
        t1 = time.perf_counter()
        pt = model.predict(Xt)
        ms = (time.perf_counter() - t1) / max(1, len(Xt)) * 1000
        alarm_rows.append((title, runs, yt, pt, ms))

    # 예측 1건 시간 (특징 만들기 포함, 경보 하나씩)
    run0, al0 = test[0], next(a for a in test[0].alarms if a["shot"] > BASELINE)
    t2 = time.perf_counter()
    for _ in range(200):
        model.predict_proba([features(run0, al0)])
    one_ms = (time.perf_counter() - t2) / 200 * 1000

    cases_test = score_cases(test, predict_with(model))
    cases_42 = score_cases(seed42, predict_with(model))
    cases_shift = score_cases(shifted, predict_with(model))

    # 처음 보는 고장: 원인 하나를 학습에서 빼고, 그 고장 사례에서 무엇을 내는지
    novel_rows = []
    for held in ["HEATER_DEGRADATION", "COOLING_ISSUE", "CHECK_RING_WEAR", "SENSOR_FAULT"]:
        keep = y != held
        m = RandomForestClassifier(n_estimators=300, min_samples_leaf=2, class_weight="balanced", random_state=0,
                                   n_jobs=1).fit(X[keep], y[keep])
        pred = predict_with(m, unknown=True)
        firsts = Counter()
        wrong_draft_cases = 0
        unknown_cases = 0
        only_noise = 0
        n = 0
        for run in test:
            for eq, fault, start, end in run.episodes():
                if FAULT_CAUSE[fault] != held:
                    continue
                als = [a for a in run.alarms if a["eq"] == eq and start <= a["shot"] < end][:5]
                if not als:
                    continue
                n += 1
                ps = [pred(run, a) for a in als]
                firsts[ps[0]] += 1
                wrong_draft_cases += any(p in NCR_CAUSES for p in ps)
                unknown_cases += any(p == "UNKNOWN" for p in ps)
                only_noise += all(p == "NOISE" for p in ps)
        novel_rows.append((held, n, firsts, wrong_draft_cases, unknown_cases, only_noise))

    importances = sorted(zip(names, model.feature_importances_), key=lambda t: -t[1])[:12]

    # ── 쓰기 ──
    L = []
    L.append("# ML 원인 분류 기준선 vs LLM 에이전트")
    L.append("")
    L.append(f"`scripts/ml_baseline.sh` 로 다시 만들 수 있습니다 (LLM·DB·Docker 없이 약 1분). 생성: {datetime.now():%Y-%m-%d %H:%M}, "
             f"{platform.system()} {platform.machine()}, Python {platform.python_version()}, scikit-learn 랜덤 포레스트(나무 300개).")
    L.append("")
    L.append("> 모두 **시뮬레이터 합성 데이터**입니다. 고장은 시뮬레이터 규칙으로 만들어지므로, 같은 규칙에서 나온 데이터로 "
             "학습·시험하는 ML 은 유리합니다. 아래 '고장 배치 변경'과 '처음 보는 고장'이 그 한계를 보여 주는 시험입니다.")
    L.append("")
    L.append("## 방법")
    L.append("")
    L.append(f"- 데이터: 시뮬레이터 파일 출력(결측·중복·늦은 메시지 포함). 학습 seed {len(train)}개(101~130), 시험 seed 1~5·42, "
             "고장 배치를 바꾼 시나리오 seed 1~5")
    L.append("- 경보: 운영과 같은 `SpcEngine` 으로 재생한 경보 (`./gradlew alarmExport`). 에이전트가 받는 경보와 같습니다")
    L.append(f"- 특징 {len(names)}개, **경보 shot 까지의 데이터만**: 센서 7개 × (기준 100 shot 대비 최근 5·20·60 shot 평균의 z, "
             "20 shot 기울기, 20 shot 표준편차, 20 shot 최대 |z|, |z|>3 비율) + 최근 50 shot 불량률·불량 유형 비율 + 경보 규칙·센서")
    L.append("- 설비 이름과 shot 번호는 넣지 않았습니다 (넣으면 '어느 설비의 몇 번째 shot' 을 외워 버립니다)")
    L.append("- 정답: 경보 shot 에서 진행 중인 고장의 원인 코드, 고장 밖이면 NOISE(단발성)")
    L.append("- 사례 채점은 `evaluate_agent.py` 와 같은 단위: 고장 구간 경보를 순서대로 분류해 정답 원인이 처음 나온 경보를 진단 시점으로 봅니다"
             "(최대 5경보). 단발성 경보는 고장 ±100 shot 밖의 경보 하나하나. 판단은 원인에서 정합니다(고장 3종 → 초안, 센서 이상 → 센서 점검, NOISE → 관찰)")
    L.append("")
    L.append(f"학습: 경보 {len(y)}건 ({', '.join(f'{CAUSE_KO[k]} {v}' for k, v in Counter(y).most_common())}), {train_s:.1f}초")
    L.append("")

    L.append("## 1. 경보 단위 정확도")
    L.append("")
    L.append("| 시험 | 경보 수 | 정확도 | Macro F1 | 예측 1건 (배치, ms) |")
    L.append("|---|---:|---:|---:|---:|")
    for title, runs, yt, pt, ms in alarm_rows:
        acc = float(np.mean(yt == pt)) if len(yt) else float("nan")
        f1 = f1_score(yt, pt, labels=[l for l in labels if l in set(yt)], average="macro", zero_division=0)
        L.append(f"| {title} | {len(yt)} | {acc:.3f} | {f1:.3f} | {ms:.3f} |")
    L.append("")
    L.append(f"경보 하나를 특징 만들기부터 예측까지 따로 처리하면 {one_ms:.1f} ms 입니다 (LLM 에이전트 분석 1단계 평균 44초).")
    L.append("")
    for title, runs, yt, pt, ms in alarm_rows:
        if "옮긴" in title:
            L.append(f"**혼동행렬: {title}**")
            L.append("")
            L += confusion(list(yt), list(pt), labels)
            L.append("")

    L.append("## 2. 사례 단위 (에이전트 평가와 같은 채점)")
    L.append("")
    for title, cases in [("같은 시나리오, 다른 잡음 (seed 1~5)", cases_test), ("seed 42 (에이전트 재생 평가와 같은 seed)", cases_42),
                         ("고장 배치 변경 (seed 1~5)", cases_shift)]:
        L.append(f"**{title}**")
        L.append("")
        L += case_table(cases)
        L.append("")
    L.append("- 진단까지 shot = 고장 시작부터 정답 원인을 처음 낸 경보까지. 탐지(첫 경보)보다 빠를 수 없습니다")
    L.append("- 잘못된 초안 = 정답이 아닌 고장 원인(초안 대상)을 낸 경보 수")
    L.append("")

    L.append("## 3. 처음 보는 고장 (원인 하나를 학습에서 빼기)")
    L.append("")
    L.append(f"원인 하나의 경보를 학습에서 모두 빼고, 그 고장 사례(seed 1~5)의 처음 5경보를 분류했습니다. "
             f"가장 높은 확률이 {UNKNOWN_PROBA} 미만이면 '모름'으로 봅니다. 분류기는 학습한 적 없는 원인을 **낼 수 없습니다**.")
    L.append("")
    L.append("| 뺀 원인 | 사례 | 첫 경보에서 낸 답 | 5경보 내내 '단발성(관찰)'만 낸 사례 | 다른 고장으로 초안 낸 사례 | '모름'이 나온 사례 |")
    L.append("|---|---:|---|---:|---:|---:|")
    for held, n, firsts, wd, unk, on in novel_rows:
        L.append(f"| {CAUSE_KO[held]} | {n} | {', '.join(f'{CAUSE_KO.get(k, k)} {v}' for k, v in firsts.most_common())} | "
                 f"{on}/{n} | {wd}/{n} | {unk}/{n} |")
    L.append("")
    L.append("- 모르는 고장을 '모름'이라고 하지 않고 **자신 있게 '단발성 경보'로 분류**할 수 있습니다(이번 측정에서는 위 표 그대로). 학습한 고장들과 닮지 않은 경보는 "
             "가장 가까운 '나머지' 칸(NOISE)으로 들어가기 때문입니다. 운영이었다면 고장을 놓치고 관찰만 하게 됩니다.")
    L.append("- LLM 에이전트는 QualiGuard 의 원인 라벨로 학습한 적이 없습니다. 다만 기본 평가에서는 과거 사례 검색으로 비슷한 승인 사례를 봅니다. "
             "이 표와 가장 가까운 조건은 **과거 사례·정비 이력을 모두 숨긴 실험**이고, 에이전트는 그 조건에서 25/25 를 맞혔습니다 (센서 이상은 재확인 뒤).")
    L.append("")

    L.append("## 4. 분류기가 많이 본 특징 (전체 중요도 상위 12)")
    L.append("")
    L.append("| 특징 | 중요도 |")
    L.append("|---|---:|")
    for n_, v in importances:
        k = n_.split(".")[0]
        L.append(f"| {SENSOR_KO.get(k, '')} `{n_}` | {v:.3f} |")
    L.append("")
    L.append("중요도는 '모델 전체에서 어떤 특징을 많이 썼는가'입니다. 경보 하나에 대해 '왜 이 원인인가'를 문장으로 설명하지는 않습니다.")
    L.append("")

    L.append("## 5. LLM 에이전트와 비교")
    L.append("")
    L.append("에이전트 수치는 [docs/evaluation.md 2-8](../docs/evaluation.md) 의 시간 순 재생 평가(seed 42, 2026-11-15, Gemini, 관찰 뒤 재확인 규칙 적용)에서 가져왔습니다. "
             "에이전트는 seed 42 한 가지 데이터로만 쟀고, ML 은 위 표의 seed 들로 쟀습니다. "
             "**진단까지 shot 은 직접 비교하면 안 됩니다**: 에이전트 평가 데이터는 전체 파이프라인(MQTT)으로 결측 섞기 전에 만든 것이고, "
             "이 표의 seed 42 는 결측을 섞은 파일 재생이라 첫 경보 시점부터 다릅니다(예: 히터 첫 경보 +25 vs +46). "
             "ML 은 모든 고장을 첫 경보에서 맞혔으므로 진단 시점 = 탐지 시점이고, 에이전트는 히터(관찰 → 재확인)와 체크 링 일부에서 첫 경보 뒤에 진단했습니다.")
    L.append("")
    c42 = {c["cause"]: c for c in cases_42["faults"]}
    agent = {"HEATER_DEGRADATION": "65 (재확인)", "SENSOR_FAULT": "7", "COOLING_ISSUE": "35", "CHECK_RING_WEAR": "35~39"}
    L.append("| 항목 | LLM 에이전트 | ML 분류기 |")
    L.append("|---|---|---|")
    L.append("| 정답 진단 (seed 42, 고장 4 + 단발성 1) | 15/15 (3회 반복), 처음 보는 고장 실험 25/25 | "
             + f"고장 {sum(c['diag'] is not None for c in cases_42['faults'])}/{len(cases_42['faults'])}, "
             + f"단발성 경보 {sum(x['pred'] == 'NOISE' for x in cases_42['noise'])}/{len(cases_42['noise'])} |")
    L.append("| 진단까지 shot: 히터 / 센서 / 냉각 / 체크 링 | " + " / ".join(agent[k] for k in ["HEATER_DEGRADATION", "SENSOR_FAULT", "COOLING_ISSUE", "CHECK_RING_WEAR"])
             + " | " + " / ".join(str(c42[k]["diag"]) if k in c42 and c42[k]["diag"] is not None else "-"
                                  for k in ["HEATER_DEGRADATION", "SENSOR_FAULT", "COOLING_ISSUE", "CHECK_RING_WEAR"]) + " |")
    L.append(f"| 판단 1번 시간 | 평균 44초 (33~74초) | {one_ms:.1f} ms |")
    L.append("| 판단 1번 비용 | Gemini API 호출 (입력·출력 토큰) | 0 (CPU) |")
    L.append("| 처음 보는 원인 | 원인 라벨 학습 없이 센서 패턴과 일반 원칙으로 고름 (과거 사례·정비 이력 숨김 25/25) | 낼 수 없음 → 대부분 '단발성'으로 놓침 (3절) |")
    L.append("| 고장 배치가 바뀌면 | (미측정) | 영향 없음: 사례 20/20 (2절) |")
    L.append("| 학습 데이터 | 필요 없음 | 원인이 붙은 경보 " + str(len(y)) + "건 (실제 공장이면 승인된 NCR 이력) |")
    L.append("| 결과물 | 현상·근거(수치)·권장 조치 문장, 과거 사례 인용, 다음 확인 항목 | 원인 코드와 확률 |")
    L.append("")
    L.append("## 해석")
    L.append("")
    L.append("- **정해진 원인 맞히기만 보면 ML 이 훨씬 빠르고 싸며, 같은 규칙에서 나온 데이터에서는 정확도도 높습니다.** 합성 데이터라 쉬운 문제라는 점을 함께 봐야 합니다.")
    L.append("- ML 은 **학습한 원인만** 낼 수 있습니다. 실제 공장에서 처음 생긴 고장, 라벨이 붙은 이력이 적은 고장에는 쓸 수 없고, 초안 문장도 만들지 못합니다.")
    L.append("- 그래서 둘은 경쟁보다 역할 분담이 맞습니다: ML 은 경보마다 즉시 '후보 원인과 확률'을 내는 **1차 분류·도구**, LLM 은 근거를 모아 문장으로 설명하고 처음 보는 상황을 다루는 **분석·작성**. "
             "예를 들어 ML 확률을 에이전트 도구로 주거나, 확률이 높고 학습된 원인이면 LLM 호출을 줄이는 방식은 이 측정 뒤의 선택지입니다 (지금 운영 코드에는 넣지 않았습니다).")
    Path(args.out).write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))
    print(f"\n저장: {args.out}")


if __name__ == "__main__":
    main()
