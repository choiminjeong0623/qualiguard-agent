"""원인 평가 지표 (로드맵 E5). scripts/evaluate_agent.py 결과(results.json 의 사례 목록)로 계산한다.

- Top-3 적중: 정답 원인이 후보 원인(candidateCauses) 앞 3개 안에 있는 사례 비율. 후보가 없으면 causeCode 하나로 본다.
  첫 판단(첫 단계)과 마지막 판단(진단 단계 또는 마지막 단계) 두 시점으로 센다.
- 근거 수치 일치율: 근거에 적힌 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 대조, 규칙 6). 대조하지 못한 단계는 뺀다.
- 인용 사례 실재율: similarCases 로 적은 부적합 번호 가운데 DB 에 실제로 있는 번호 비율 (지어낸 번호 찾기).
- 보류 적절성: 판단 보류(사람 검토)로 넘긴 단계 가운데, 그 단계의 원인이 정답이 아니었던 비율.
  보류가 틀린 결론을 막았으면 적절, 맞는 결론을 붙잡아 두었으면 불필요한 보류로 본다.
"""


def candidates(step):
    c = [x for x in (step.get("candidateCauses") or []) if x]
    return c if c else ([step["cause"]] if step.get("cause") else [])


def top3_hit(case, step):
    return step is not None and case["expectedCause"] in candidates(step)[:3]


def final_step(case):
    steps = case["stepDetails"]
    if not steps:
        return None
    if case.get("diagnosed"):
        return next((s for s in steps if s.get("correct")), steps[-1])
    return steps[-1]


def ratio(a, b):
    return round(a / b, 3) if b else None


def compute(results):
    steps = [s for r in results for s in r["stepDetails"]]
    claims = sum(s.get("numberClaims") or 0 for s in steps if s.get("numberClaims") is not None)
    verified = sum(s.get("numberVerified") or 0 for s in steps if s.get("numberClaims") is not None)
    cited = sum(len(s.get("similarCases") or []) for s in steps)
    real = sum(len(s.get("similarCasesReal") or []) for s in steps)
    holds = [(r, s) for r in results for s in r["stepDetails"] if s.get("holdStatus")]
    appropriate = sum(1 for r, s in holds if s.get("cause") != r["expectedCause"])
    return {
        "cases": len(results),
        "top3First": ratio(sum(top3_hit(r, r["stepDetails"][0] if r["stepDetails"] else None) for r in results),
                           len(results)),
        "top3Final": ratio(sum(top3_hit(r, final_step(r)) for r in results), len(results)),
        "numberClaims": claims,
        "numberMatchRate": ratio(verified, claims),
        "citedCases": cited,
        "citedRealRate": ratio(real, cited),
        "holds": len(holds),
        "holdAppropriateRate": ratio(appropriate, len(holds)),
    }


def pct(v):
    return "-" if v is None else f"{round(v * 100)}%"


def markdown(m):
    return "\n".join([
        "### 원인 평가 지표 (E5)",
        "",
        "| Top-3 적중 (첫 판단) | Top-3 적중 (마지막 판단) | 근거 수치 일치율 | 인용 사례 실재율 | 보류 적절성 |",
        "|---|---|---|---|---|",
        f"| {pct(m['top3First'])} | {pct(m['top3Final'])} | {pct(m['numberMatchRate'])} (수치 {m['numberClaims']}개) "
        f"| {pct(m['citedRealRate'])} (인용 {m['citedCases']}건) | {pct(m['holdAppropriateRate'])} (보류 {m['holds']}건) |",
        "",
        "- Top-3: 정답 원인이 후보 원인 앞 3개 안에 있는 사례 비율 (후보가 없으면 원인 코드 하나)",
        "- 근거 수치 일치율: 근거의 수치 가운데 도구 결과와 맞은 비율 (검증 에이전트 규칙 6과 같은 대조)",
        "- 인용 사례 실재율: 인용한 부적합 번호 가운데 DB 에 있는 번호 비율",
        "- 보류 적절성: 판단 보류 단계 가운데 그 단계의 원인이 정답이 아니었던 비율 (보류가 틀린 결론을 막음)",
    ])
