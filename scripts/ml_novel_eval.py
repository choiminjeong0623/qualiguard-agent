#!/usr/bin/env python3
"""학습에 없는 '새 고장'을 섞은 무작위 시나리오로 "ML 먼저, 애매하면 LLM" 규칙을 시험한다.

  scenarios : 무작위 시나리오 설정 파일을 만든다 (scripts/ml_novel.sh 가 부른다)
  evaluate  : 앱과 같은 모델(나무 100개, seed 101~130 학습)로 규칙을 적용해 채점 → eval/ml_novel_report.md

규칙 (앱 MlTriage 와 같음): ML 이 학습한 고장 원인을 기준 확률 이상으로 고르면 LLM 생략, 단발성·낮은 확률은 LLM.
여기서 재는 위험: 새 고장 경보를 ML 이 '아는 고장'으로 자신 있게 골라 LLM 없이 잘못 처리하는 경우.
"""
from __future__ import annotations

import argparse
import random
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

KNOWN = ["heater_degradation", "cooling_blockage", "check_ring_wear", "pressure_sensor_spike"]
NOVEL = ["material_moisture", "nozzle_clog", "mold_wear"]
NOVEL_KO = {"material_moisture": "원료 수분 과다", "nozzle_clog": "노즐 막힘", "mold_wear": "금형 파팅면 마모"}
MIXES = [(5, 1), (4, 2), (3, 3)]       # (아는 고장 수, 새 고장 수), 설비 2대 × 3개
SEEDS = [1, 2, 3, 4, 5]
SHOTS = 2000
FIRST = 150                             # 기준 구간(100 shot) 뒤부터
GAP = 150                               # 고장 사이 정상 구간 (단발성 경보 사례가 생기도록)
THRESHOLDS = [0.5, 0.7, 0.9, 0.95]
DEFAULT = 0.9


# ── 시나리오 만들기 ─────────────────────────────────────────────────────────

def make_faults(rng: random.Random, known: int, novel: int, equipment: list[str]) -> list[dict]:
    names = rng.sample(KNOWN * 2, known) + [NOVEL[i % len(NOVEL)] for i in rng.sample(range(6), novel)]
    rng.shuffle(names)
    out = []
    for e, eq in enumerate(equipment):
        mine = names[e * 3:(e + 1) * 3]
        free = SHOTS - FIRST - GAP * len(mine)
        durations = [rng.randint(280, 420) for _ in mine]
        slack = max(0, free - sum(durations))
        cuts = sorted(rng.randint(0, slack) for _ in mine)
        shot, prev = FIRST, 0
        for name, dur, cut in zip(mine, durations, cuts):
            shot += cut - prev
            prev = cut
            out.append({"name": name, "equipment": eq, "start_shot": shot, "duration": dur,
                        "severity": round(rng.uniform(0.6, 1.4), 2)})
            shot += dur + GAP
    return out


def scenarios(args):
    import yaml
    base = yaml.safe_load(open(args.base, encoding="utf-8"))
    equipment = base["simulation"]["equipment"]
    for known, novel in MIXES:
        for seed in SEEDS:
            rng = random.Random(f"novel-{known}-{novel}-{seed}")
            cfg = dict(base)
            cfg["simulation"] = dict(base["simulation"], shots=SHOTS)
            cfg["faults"] = make_faults(rng, known, novel, equipment)
            d = Path(args.out) / f"mix-{known}-{novel}" / f"seed-{seed}"
            d.mkdir(parents=True, exist_ok=True)
            yaml.safe_dump(cfg, open(d / "scenario.yaml", "w", encoding="utf-8"), allow_unicode=True, sort_keys=False)
    print(f"시나리오: 섞음 {len(MIXES)}가지 × seed {len(SEEDS)}개")


# ── 채점 ────────────────────────────────────────────────────────────────────

def evaluate(args):
    import numpy as np
    import yaml
    from ml_cause_baseline import BASELINE, CAUSE_KO, FAULT_CAUSE, Run, dataset, features, pct
    from ml_cascade_eval import APP_TREES, KNOWN_FAULTS, forest, predict_all

    def load(root):
        return [Run(p) for p in sorted(Path(root).glob("seed-*"), key=lambda p: int(p.name.split("-")[1]))]

    train = load(Path(args.train) / "train")
    X, y, _ = dataset(train)
    model = forest(APP_TREES).fit(X, y)

    def truth(run, eq, shot):
        f = run.truth[eq].get(shot, "NONE")
        return "NOVEL:" + f if f in NOVEL else FAULT_CAUSE.get(f, "NOISE")

    mixes = {}
    for known, novel in MIXES:
        runs = load(Path(args.novel) / f"mix-{known}-{novel}")
        rows = []          # 경보마다 (run, alarm, 정답, ML 원인, 확률)
        for run in runs:
            als = [a for a in run.alarms if a["shot"] > BASELINE and a["eq"] in run.arr]
            if not als:
                continue
            cls, p = predict_all(model, np.array([features(run, a) for a in als]))
            for a, c, q in zip(als, cls, p):
                rows.append((run, a, truth(run, a["eq"], a["shot"]), str(c), float(q)))
        sev = {}
        for run in runs:
            cfg = yaml.safe_load(open(run.path / "scenario.yaml", encoding="utf-8"))
            for f in cfg["faults"]:
                sev[(run.name, f["equipment"], f["start_shot"])] = f["severity"]
        mixes[(known, novel)] = (runs, rows, sev)

    def skip(c, q, t):
        return c in KNOWN_FAULTS and q >= t

    L = ["# 새 고장을 섞은 무작위 시나리오 — ML 먼저, 애매하면 LLM", ""]
    L.append(f"`./scripts/ml_novel.sh` 로 다시 만들 수 있습니다 (LLM 없이). 생성: {datetime.now():%Y-%m-%d %H:%M}. "
             f"모델은 앱에 넣은 것과 같은 학습(나무 {APP_TREES}개, 기본 시나리오 seed 101~130, 경보 {len(y)}건)입니다.")
    L.append("")
    L.append("> 시뮬레이터 합성 데이터입니다. 새 고장의 모양은 제가 일반적인 사출 공정 지식으로 정했고, 실제 설비 데이터로 검증한 것이 아닙니다.")
    L.append("")
    L.append("## 시험 조건")
    L.append("")
    L.append("- 새 고장 3종 (ML 학습에 없음, `simulator/qg_simulator/faults.py`). **아는 고장과 일부만 닮게** 만들었습니다:")
    L.append("  - 원료 수분 과다: 사출 압력·보압이 내려가고 흔들림, 버 증가 (실린더 온도 정상)")
    L.append("  - 노즐 막힘: 사출 압력·모터 전류 상승, 쿠션량 증가, 미성형 증가 (히터 열화와 닮았지만 실린더 온도 정상)")
    L.append("  - 금형 파팅면 마모: 센서는 거의 그대로, 보압만 조금 흔들리고 버 증가")
    L.append(f"- 무작위 시나리오: 설비 2대 × 고장 3개, 시점·길이(280~420 shot)·세기(0.6~1.4배) 무작위. 아는 고장도 세기가 바뀌어 학습 때와 다릅니다")
    L.append(f"- 아는 고장 : 새 고장 = 5:1, 4:2, 3:3 × seed {len(SEEDS)}개. 앱 규칙 그대로: 학습한 고장 원인을 기준 확률 이상 → LLM 생략, 그 밖은 LLM")
    L.append("")

    L.append("## 1. 경보 단위")
    L.append("")
    L.append("| 섞음 (아는:새) | 경보 | 새 고장 경보 비율 | 기준 확률 | LLM 생략 (호출 감소) | 생략 중 정답 | 생략 중 오답: 아는 고장 혼동 | **생략 중 오답: 새 고장을 아는 고장으로** |")
    L.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    summary = {}
    for (known, novel), (runs, rows, _) in mixes.items():
        n = len(rows)
        nov = sum(r[2].startswith("NOVEL") for r in rows)
        for t in THRESHOLDS:
            sk = [r for r in rows if skip(r[3], r[4], t)]
            ok = sum(r[3] == r[2] for r in sk)
            nov_wrong = sum(r[2].startswith("NOVEL") for r in sk)
            other_wrong = len(sk) - ok - nov_wrong
            mark = " **(기본)**" if t == DEFAULT else ""
            L.append(f"| {known}:{novel} | {n} | {pct(nov, n)} | {t}{mark} | {pct(len(sk), n)} | {ok} | {other_wrong} | {nov_wrong} |")
            if t == DEFAULT:
                summary[(known, novel)] = (n, nov, len(sk), ok, other_wrong, nov_wrong)
    L.append("")
    L.append("- 새 고장 경보 비율 = 경보 시점에 새 고장이 진행 중인 경보의 비율. 그 밖은 아는 고장 경보와 고장 밖 경보(단발성)")
    L.append("")

    # 새 고장 사례: 처음 5경보
    L.append(f"## 2. 새 고장 사례 (기준 확률 {DEFAULT}, 고장 구간의 처음 5경보)")
    L.append("")
    L.append("| 새 고장 | 사례 | 경보가 난 사례 | 처음 5경보 | LLM 으로 넘김 | ML 이 아는 고장으로 확신 (LLM 생략) | 첫 경보가 ML 오답으로 끝난 사례 |")
    L.append("|---|---:|---:|---:|---:|---:|---:|")
    per = defaultdict(lambda: Counter())
    picks = defaultdict(Counter)
    probs = defaultdict(list)
    for (known, novel), (runs, rows, _) in mixes.items():
        by_ep = defaultdict(list)
        for r in rows:
            run, a = r[0], r[1]
            for eq, fault, start, end in run.episodes():
                if eq == a["eq"] and start <= a["shot"] < end and fault in NOVEL:
                    by_ep[(run.name, eq, fault, start)].append(r)
        for run in runs:
            for eq, fault, start, end in run.episodes():
                if fault in NOVEL:
                    per[fault]["cases"] += 1
                    rs = sorted(by_ep.get((run.name, eq, fault, start), []), key=lambda r: r[1]["shot"])[:5]
                    if not rs:
                        continue
                    per[fault]["alarmed"] += 1
                    for i, r in enumerate(rs):
                        per[fault]["alarms"] += 1
                        probs[fault].append(r[4])
                        if skip(r[3], r[4], DEFAULT):
                            per[fault]["skipped"] += 1
                            picks[fault][r[3]] += 1
                            if i == 0:
                                per[fault]["firstWrong"] += 1
                        else:
                            per[fault]["llm"] += 1
    harm_total = 0
    for f in NOVEL:
        c = per[f]
        harm_total += c["skipped"]
        L.append(f"| {NOVEL_KO[f]} | {c['cases']} | {c['alarmed']} | {c['alarms']} | {c['llm']} | {c['skipped']} | "
                 f"{c['firstWrong']}/{c['alarmed']} |")
    L.append("")
    L.append("**ML 이 새 고장 경보에 낸 1순위 답 (모든 경보, 확률과 무관)**")
    L.append("")
    L.append("| 새 고장 | 경보 | ML 1순위 답 | 1순위가 아는 고장(오답)인 경보 | 그중 가장 높은 확률 |")
    L.append("|---|---:|---|---:|---:|")
    worst = 0.0
    for f in NOVEL:
        top = Counter()
        wrong_known = 0
        best = 0.0
        for (known, novel), (runs, rows, _) in mixes.items():
            for r in rows:
                if r[2] == "NOVEL:" + f:
                    top[r[3]] += 1
                    if r[3] in KNOWN_FAULTS:
                        wrong_known += 1
                        best = max(best, r[4])
        worst = max(worst, best)
        n = sum(top.values())
        L.append(f"| {NOVEL_KO[f]} | {n} | {', '.join(f'{CAUSE_KO[k]} {v}' for k, v in top.most_common())} | "
                 f"{pct(wrong_known, n)} | {best:.2f} |")
    L.append("")
    L.append(f"- ML 은 새 고장을 자주 **비슷한 아는 고장으로 1순위에 올립니다** (예: 노즐 막힘 → 체크 링 마모). 다만 그때 확률이 낮아(가장 높아도 {worst:.2f}) "
             f"기준 {DEFAULT} 규칙에서는 모두 LLM 으로 넘어갔습니다. 기준을 {worst:.2f} 아래로 낮추면 이 경보들이 LLM 없이 틀린 원인으로 처리됩니다 (1절의 0.5 행).")
    L.append("")
    L.append("- '경보가 난 사례'가 사례보다 적으면 그 고장은 SPC 경보가 늦거나 없었다는 뜻입니다 (탐지 단계의 한계, 이 실험의 대상 아님)")
    L.append("")

    # 아는 고장 (세기 무작위): 첫 분석
    L.append(f"## 3. 아는 고장, 세기만 무작위 (기준 확률 {DEFAULT})")
    L.append("")
    L.append("| 원인 | 사례 | 경보가 난 사례 | 첫 경보를 ML 이 정답으로 끝냄 | 첫 경보 ML 오답 | 첫 경보 LLM | 세기 < 0.8 사례의 첫 경보 LLM 비율 |")
    L.append("|---|---:|---:|---:|---:|---:|---:|")
    kc = defaultdict(Counter)
    for (known, novel), (runs, rows, sev) in mixes.items():
        for run in runs:
            for eq, fault, start, end in run.episodes():
                if fault not in FAULT_CAUSE:
                    continue
                cause = FAULT_CAUSE[fault]
                kc[cause]["cases"] += 1
                rs = sorted([r for r in rows if r[0] is run and r[1]["eq"] == eq and start <= r[1]["shot"] < end],
                            key=lambda r: r[1]["shot"])
                if not rs:
                    continue
                kc[cause]["alarmed"] += 1
                r = rs[0]
                weak = sev.get((run.name, eq, start), 1.0) < 0.8
                kc[cause]["weak"] += weak
                if skip(r[3], r[4], DEFAULT):
                    kc[cause]["ok" if r[3] == cause else "wrong"] += 1
                else:
                    kc[cause]["llm"] += 1
                    kc[cause]["weakLlm"] += weak
    for cause in KNOWN_FAULTS:
        c = kc[cause]
        if c["cases"]:
            L.append(f"| {CAUSE_KO[cause]} | {c['cases']} | {c['alarmed']} | {c['ok']} | {c['wrong']} | {c['llm']} | "
                     f"{pct(c['weakLlm'], c['weak'])} |")
    L.append("")

    L.append("## 해석")
    L.append("")
    lines = []
    for (known, novel), (n, nov, sk, ok, ow, nw) in summary.items():
        lines.append(f"{known}:{novel} 에서 새 고장 경보 {nov / n:.0%} → LLM 생략 {sk / n:.0%}, 새 고장 오답 {nw}건")
    L.append("- 기준 확률 " + str(DEFAULT) + ": " + "; ".join(lines))
    L.append("- 새 고장 경보가 늘수록 LLM 생략률이 떨어집니다. 실제 공장의 감소율은 '경보 중 아는 고장 비율'에 달려 있고, 이 비율은 시뮬레이터로 정할 수 없어 운영 중 `shadow` 로 재야 합니다.")
    if harm_total:
        L.append(f"- **새 고장을 아는 고장으로 확신한 경보가 {harm_total}건** 있었습니다 (2절). 이 경보들은 LLM 없이 틀린 원인의 초안이 됩니다. "
                 "'원인 하나 빼기' 실험(eval/ml_cascade_report.md 3절, 0건)보다 위험이 크게 나온 이유는, 새 고장이 아는 고장과 일부만 닮았기 때문입니다. "
                 "사람 승인 전에는 확정되지 않지만, `skip` 을 켜기 전에 보완이 필요하다는 근거입니다 (아래 보완 후보).")
    else:
        L.append("- 새 고장 경보가 ML 에 의해 아는 고장으로 확신된 경우는 없었습니다 (2절).")
    L.append("- 보완 후보: (1) 기준 확률을 높이기 (1절의 0.95 행), (2) 학습 데이터와 얼마나 다른지 보는 이상치 검사를 추가해 낯선 경보는 LLM 으로, "
             "(3) ML 이 생략한 사건도 일부는 LLM 으로 다시 보는 표본 점검.")
    Path(args.out).write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scenarios")
    s.add_argument("--base", required=True)
    s.add_argument("--out", required=True)
    e = sub.add_parser("evaluate")
    e.add_argument("--train", default="eval/ml/runs")
    e.add_argument("--novel", default="eval/ml/novel")
    e.add_argument("--out", default="eval/ml_novel_report.md")
    args = ap.parse_args()
    scenarios(args) if args.cmd == "scenarios" else evaluate(args)


if __name__ == "__main__":
    main()
