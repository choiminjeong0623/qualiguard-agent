#!/usr/bin/env python3
"""확신도 구간별 실제 적중률 (로드맵 E7).

에이전트가 낸 확신도(confidence)가 실제 정답률과 맞는지 본다.
"확신도 85 라고 하면 85% 정도 맞는가?" — 맞지 않으면 사람이 확신도를 믿고 검토를 건너뛰면 안 된다.

    python3 scripts/calibration_report.py                       # eval/results 전부, 프롬프트 버전·데이터 구분별로
    python3 scripts/calibration_report.py eval/results/2026...  # 특정 결과 폴더만
    python3 scripts/calibration_report.py --out eval/calibration_report.md

채점 기준 (분석 단계 하나 = 표본 하나, FAILED 단계는 확신도가 없어 뺀다):
  - 원인 적중: 그 단계의 causeCode 가 정답 원인과 같은가 (단발성 경보는 NOISE, 센서 이상은 SENSOR_FAULT)
  - 모호 사례(정답 원인이 없는 사례)는 원인 적중에서 뺀다
  - 확정 판단(NCR_DRAFT·SENSOR_CHECK)만 따로 본 표도 만든다 — 사람에게 넘어가는 초안의 확신도가 믿을 만한지
같은 결과 폴더를 여러 번 넣어도 한 번만 센다. 외부 라이브러리 없이 표준 라이브러리만 쓴다.
"""
import argparse
import glob
import json
import os
import sys

BINS = [(0, 49), (50, 69), (70, 79), (80, 89), (90, 100)]


def bin_label(lo, hi):
    return f"{lo}~{hi}"


def load_dir(path):
    """결과 폴더 하나 → (묶음 키, 표본 목록). 표본 = (확신도, 원인 적중, 판단)."""
    rfile = os.path.join(path, "results.json")
    mfile = os.path.join(path, "meta.json")
    if not os.path.exists(rfile):
        return None
    with open(rfile, encoding="utf-8") as f:
        cases = json.load(f)
    meta = {}
    if os.path.exists(mfile):
        with open(mfile, encoding="utf-8") as f:
            meta = json.load(f)
    prompt = (meta.get("agentConfig") or {}).get("promptVersion") or "?"
    sim = ((meta.get("groundTruth") or {}).get("simulatorMeta") or {})
    split = (sim.get("evalRun") or {}).get("split") or "기존 평가(seed 42)"
    samples = []
    for case in cases:
        expected = case.get("expectedCause")
        for st in case.get("stepDetails") or []:
            conf = st.get("confidence")
            if st.get("decision") == "FAILED" or conf is None:
                continue
            hit = None if not expected else (st.get("cause") == expected)
            samples.append((int(conf), hit, st.get("decision")))
    return (prompt, split), samples


def table(samples, title):
    rows = [f"#### {title}", "",
            "| 확신도 구간 | 단계 수 | 평균 확신도 | 원인 적중률 | 차이 (적중률 − 확신도) |",
            "|---|---|---|---|---|"]
    total_n = 0
    gap_sum = 0.0
    for lo, hi in BINS:
        inbin = [(c, h) for c, h, _ in samples if lo <= c <= hi and h is not None]
        if not inbin:
            rows.append(f"| {bin_label(lo, hi)} | 0 | - | - | - |")
            continue
        n = len(inbin)
        mean_conf = sum(c for c, _ in inbin) / n
        acc = 100.0 * sum(1 for _, h in inbin if h) / n
        gap = acc - mean_conf
        total_n += n
        gap_sum += n * abs(gap)
        rows.append(f"| {bin_label(lo, hi)} | {n} | {mean_conf:.0f} | {acc:.0f}% ({sum(1 for _, h in inbin if h)}/{n}) "
                    f"| {gap:+.0f} |")
    ece = gap_sum / total_n if total_n else 0.0
    rows += ["", f"- 평균 보정 오차(구간 크기로 가중한 |적중률 − 확신도| 평균): **{ece:.1f}%p** (표본 {total_n}단계)", ""]
    return rows


def main():
    ap = argparse.ArgumentParser(description="확신도 구간별 실제 적중률 (E7)")
    ap.add_argument("dirs", nargs="*", help="결과 폴더 (기본: eval/results/*)")
    ap.add_argument("--out", help="마크다운으로 저장할 경로 (기본: 화면에 출력)")
    args = ap.parse_args()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dirs = args.dirs or sorted(glob.glob(os.path.join(root, "eval", "results", "*")))
    groups = {}
    used = {}
    for d in dict.fromkeys(os.path.normpath(x) for x in dirs):
        loaded = load_dir(d)
        if loaded is None:
            continue
        key, samples = loaded
        groups.setdefault(key, []).extend(samples)
        used.setdefault(key, []).append(os.path.basename(d))
    if not groups:
        print("결과 폴더가 없습니다 (results.json 필요)", file=sys.stderr)
        return 1

    out = ["## 확신도 구간별 실제 적중률 (로드맵 E7)", "",
           "에이전트가 적은 확신도와 실제 원인 적중률을 구간별로 비교합니다. "
           "차이가 음수면 **실제보다 자신 있게** 말한 것(과신), 양수면 덜 자신 있게 말한 것입니다.", "",
           "- 표본: 분석 단계 하나. FAILED 단계와 모호 사례는 뺍니다.",
           "- 원인 적중: 그 단계의 원인 코드가 정답 원인과 같은가 (관찰 단계의 '가장 유력한 후보'도 포함)",
           "- 프롬프트 버전과 데이터 구분(개발셋·홀드아웃)이 다르면 섞지 않고 따로 봅니다.", ""]
    for (prompt, split), samples in sorted(groups.items()):
        out.append(f"### 프롬프트 `{prompt}` · {split}")
        out.append("")
        out.append("결과 폴더: " + ", ".join(f"`{x}`" for x in used[(prompt, split)]))
        out.append("")
        out += table(samples, "모든 단계")
        committed = [s for s in samples if s[2] in ("NCR_DRAFT", "SENSOR_CHECK")]
        out += table(committed, "확정 판단만 (NCR_DRAFT·SENSOR_CHECK — 사람에게 초안으로 넘어가는 단계)")
    text = "\n".join(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print(f"저장: {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
