#!/usr/bin/env python3
"""에이전트 평가 두 개를 나란히 비교한다 (로드맵 A1·F4).

    python3 scripts/ab_compare.py <기준 결과 폴더> <비교 결과 폴더> [--title 제목] [--out eval/ab_xxx.md]

결과 폴더는 scripts/evaluate_agent.py 가 만든 eval/results/<날짜>_<커밋>_<모드>/ 이다.
비교 항목: 정답률(사례별 진단 성공), 진단까지 shot, 잘못된 초안, LLM·도구 호출 수, 분석 1단계 지연(평균·P95),
          1단계 토큰(입력·출력·생각), 사례당 예상 비용(INPUT_USD_PER_M·OUTPUT_USD_PER_M, 예시 단가)
"""
import argparse
import json
import os
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cause_metrics  # noqa: E402  원인 평가 지표 (E5)

IN_USD = float(os.environ.get("INPUT_USD_PER_M", "0.30"))    # 예시 단가 (ops_report.sh 와 같은 값)
OUT_USD = float(os.environ.get("OUTPUT_USD_PER_M", "2.50"))


def p95(xs):
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * 0.95
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def metrics(folder):
    folder = Path(folder)
    results = json.loads((folder / "results.json").read_text(encoding="utf-8"))
    meta = json.loads((folder / "meta.json").read_text(encoding="utf-8"))
    steps = [s for r in results for s in r["stepDetails"]]
    lat = [s["latencyMs"] for s in steps if s.get("latencyMs")]
    toks = [s["tokens"] for s in steps if s.get("tokens") and s["tokens"].get("prompt") is not None]

    def tok(k):
        xs = [t.get(k) or 0 for t in toks]
        return sum(xs) / len(xs) if xs else None

    shots = [r["shotsToDiagnosis"] for r in results if r["diagnosed"] and r["shotsToDiagnosis"] is not None]
    cfg = meta.get("agentConfig", {})
    # 로컬 모델(폐쇄망 분석 모드)은 API 요금이 없다 (전기·장비 비용은 따로)
    local = cfg.get("chatProvider") == "ollama"
    cost_total = 0.0 if local else sum(
        ((t.get("prompt") or 0) * IN_USD + ((t.get("output") or 0) + (t.get("thought") or 0)) * OUT_USD)
        / 1_000_000 for t in toks)
    return {
        "folder": folder.name,
        "setting": f"model={cfg.get('model')}, analysisMode={cfg.get('analysisMode', 'tools')}, "
                   f"vectorEnabled={cfg.get('vectorEnabled')}",
        "cases": len(results),
        "correct": sum(1 for r in results if r["diagnosed"]),
        "shotsMedian": statistics.median(shots) if shots else None,
        "wrongDrafts": sum(r["wrongDrafts"] for r in results),
        "failed": sum(r["failed"] for r in results),
        "llmCalls": sum(r["llmCalls"] for r in results),
        "toolCalls": sum(r["toolCalls"] for r in results),
        "steps": len(steps),
        "latAvg": statistics.mean(lat) / 1000 if lat else None,
        "latP95": p95(lat) / 1000 if lat else None,
        "tokIn": tok("prompt"), "tokOut": tok("output"), "tokThought": tok("thought"),
        "costPerCase": cost_total / len(results) if results else None,
        "perCase": {(r["case"], r["run"]): r for r in results},
        "e5": cause_metrics.compute(results),
    }


def f(v, digits=1):
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:,.{digits}f}"
    return f"{v:,}"


def render(a, b, title):
    rows = [
        ("설정", a["setting"], b["setting"]),
        ("정답 (진단 성공 / 사례·회차)", f"{a['correct']}/{a['cases']}", f"{b['correct']}/{b['cases']}"),
        ("진단까지 shot (중앙값)", f(a["shotsMedian"]), f(b["shotsMedian"])),
        ("잘못된 초안", f(a["wrongDrafts"]), f(b["wrongDrafts"])),
        ("실패한 단계", f(a["failed"]), f(b["failed"])),
        ("분석 단계 수", f(a["steps"]), f(b["steps"])),
        ("LLM 호출 합계", f(a["llmCalls"]), f(b["llmCalls"])),
        ("도구 호출 합계", f(a["toolCalls"]), f(b["toolCalls"])),
        ("1단계 지연 평균 (초)", f(a["latAvg"]), f(b["latAvg"])),
        ("1단계 지연 P95 (초)", f(a["latP95"]), f(b["latP95"])),
        ("1단계 입력 토큰 평균", f(a["tokIn"], 0), f(b["tokIn"], 0)),
        ("1단계 출력 토큰 평균", f(a["tokOut"], 0), f(b["tokOut"], 0)),
        ("1단계 생각 토큰 평균", f(a["tokThought"], 0), f(b["tokThought"], 0)),
        (f"사례당 예상 비용 (USD, 예시 단가 {IN_USD}/{OUT_USD})", f(a["costPerCase"], 4), f(b["costPerCase"], 4)),
    ]
    for key, label in (("top3Final", "Top-3 적중 (마지막 판단, E5)"), ("numberMatchRate", "근거 수치 일치율 (E5)"),
                       ("citedRealRate", "인용 사례 실재율 (E5)")):
        rows.append((label, cause_metrics.pct(a["e5"][key]), cause_metrics.pct(b["e5"][key])))
    out = [f"# {title}", "", f"- 기준(A): `{a['folder']}`", f"- 비교(B): `{b['folder']}`", "",
           "| 항목 | A | B |", "|---|---|---|"]
    out += [f"| {k} | {x} | {y} |" for k, x, y in rows]
    out += ["", "## 사례별", "", "| 사례 | 회차 | A 판단 | B 판단 |", "|---|---|---|---|"]

    def brief(r):
        if r is None:
            return "-"
        if r["diagnosed"]:
            return f"✅ shot +{r['shotsToDiagnosis']}" if r["shotsToDiagnosis"] is not None else "✅"
        last = r["stepDetails"][-1] if r["stepDetails"] else {}
        return f"❌ {last.get('decision', '-')}/{last.get('cause') or '-'}"

    for key in sorted(set(a["perCase"]) | set(b["perCase"])):
        out.append(f"| {key[0]} | {key[1]} | {brief(a['perCase'].get(key))} | {brief(b['perCase'].get(key))} |")
    out += ["", "- 비용은 예시 단가입니다. 쓰는 모델의 요금표로 INPUT_USD_PER_M·OUTPUT_USD_PER_M 를 바꿔 다시 만드세요.",
            "- 사례 수가 적어(5사례) 1~2건 차이는 우연일 수 있습니다. 회차를 늘려(--runs 3) 분포로 보세요."]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--title", default="에이전트 평가 A/B 비교")
    ap.add_argument("--out")
    args = ap.parse_args()
    text = render(metrics(args.a), metrics(args.b), args.title)
    print(text)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"저장: {args.out}")


if __name__ == "__main__":
    main()
