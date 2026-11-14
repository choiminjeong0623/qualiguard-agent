#!/usr/bin/env python3
"""에이전트 원인 추정 평가 — 시간 순 재생(replay) 방식.

시뮬레이터 고장 시나리오(simulator/scenarios.yaml)를 정답으로 삼아, 사례(고장 4종 + 단발성 경보)마다
경보를 발생 순서대로 에이전트에게 분석시키고 "정확한 진단이 언제 나오는가"를 잰다.

재생 규칙 (장애 분석 04):
- 사례를 시작하기 전에 그 사례 경보들의 상태를 NEW 로 되돌린다 → 실행마다 같은 조건
- 아직 처리되지 않은 가장 이른 경보를 시작점으로 analyze(replay=true) 를 부른다.
  에이전트는 그 경보 시각까지 발생한 경보·데이터만 보고, 같은 실행(run_tag)의 이전 판단을 참고한다.
- 정답 판단이 나오면 멈춘다 (최대 MAX_STEPS 회)
  · 고장: NCR_DRAFT + 정답 원인 / 센서 이상: SENSOR_CHECK / 단발성 경보: OBSERVE

사용법 (앱 실행 중, 시뮬레이터 전체 시나리오를 한 번 돌린 뒤):
    python3 scripts/evaluate_agent.py                         # 기본 평가 1회
    python3 scripts/evaluate_agent.py --runs 3                # 반복 안정성: 같은 사례를 3번
    python3 scripts/evaluate_agent.py --runs 3 --hide-matching-ncr
        # 지식저장소 제거 실험: 정답과 같은 원인의 과거 부적합 4건을 잠시 숨기고 실행 (끝나면 자동 복구)

결과: 화면에 표 출력 + data/agent_eval_<모드>.json / .md 저장
주의: 분석 1단계마다 LLM 을 1회 이상 호출한다 (사례당 최대 MAX_STEPS 단계).
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = os.environ.get("QG_AGENT_API", "http://localhost:8080/api/agent")  # 다른 포트로 띄웠을 때 바꿔 쓴다
MAX_STEPS = 5

# simulator/scenarios.yaml 의 고장 시나리오와 기대 결과
FAULTS = [
    # 설비,    시나리오,                 시작, 끝(미포함), 기대 원인 코드,      기대 판단
    ("IMM-01", "heater_degradation",     300,  800,  "HEATER_DEGRADATION", "NCR_DRAFT"),
    ("IMM-02", "pressure_sensor_spike",  400,  600,  "SENSOR_FAULT",       "SENSOR_CHECK"),
    ("IMM-02", "cooling_blockage",       900,  1400, "COOLING_ISSUE",      "NCR_DRAFT"),
    ("IMM-01", "check_ring_wear",        1200, 1800, "CHECK_RING_WEAR",    "NCR_DRAFT"),
]
MARGIN = 100  # 고장 구간 앞뒤 이 범위의 경보는 단발성 경보 사례에서 제외

# 지식저장소 제거 실험에서 숨길 과거 사례: 시뮬레이터 고장 4종과 원인이 같은 사례
# (원료 건조 불량 사례 NCR-2025-0030 은 '헷갈리게 하는 사례'라 남겨 둔다)
MATCHING_NCR = ["NCR-2025-0012", "NCR-2025-0021", "NCR-2024-0033", "NCR-2026-0004"]


def http(method, path):
    req = urllib.request.Request(API + path, method=method)
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode("utf-8"))


def sql(query):
    """scripts/sql.sh 로 SQL Server 에 쿼리 실행 (비밀번호는 .env 에서 읽음)."""
    subprocess.run(["bash", str(ROOT / "scripts" / "sql.sh"), query], check=True, capture_output=True, text=True)


def parse_ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def load_ground_truth():
    """data/ground_truth.csv → {(설비, shot): 불량 여부}"""
    path = ROOT / "data" / "ground_truth.csv"
    if not path.exists():
        sys.exit("data/ground_truth.csv 가 없습니다. 시뮬레이터 전체 시나리오를 먼저 실행하세요.")
    gt = {}
    with path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            gt[(row["equipmentId"], int(row["shotNo"]))] = row["inspection"] != "OK"
    return gt


def build_cases():
    anomalies = http("GET", "/anomalies?top=5000")
    if not anomalies:
        sys.exit("경보가 없습니다. 시뮬레이터 전체 시나리오를 먼저 실행하세요.")
    # 가장 최근 실행분만 사용 (마지막 경보로부터 15분 이내)
    last = max(parse_ts(a["detected_at"]) for a in anomalies)
    anomalies = [a for a in anomalies if parse_ts(a["detected_at"]) >= last - timedelta(minutes=15)]

    def order(a):
        return parse_ts(a["detected_at"]), a["anomaly_id"]

    cases = []
    for eq, name, start, end, cause, decision in FAULTS:
        inside = sorted((a for a in anomalies if a["equipment_id"] == eq and start <= a["shot_no"] < end), key=order)
        if inside:
            cases.append({"case": name, "key": name, "equipment": eq, "faultStart": start, "faultEnd": end, "alarms": inside,
                          "expected_cause": cause, "expected_decision": decision})
    for a in sorted(anomalies, key=lambda a: (a["equipment_id"], a["shot_no"])):
        if a["rule_code"] != "INDIVIDUAL":
            continue
        near_fault = any(a["equipment_id"] == eq and start - MARGIN <= a["shot_no"] < end + MARGIN
                         for eq, _, start, end, _, _ in FAULTS)
        if not near_fault:
            cases.append({"case": f"단발성 경보 (shot {a['shot_no']} {a['sensor_name']})",
                          "key": f"noise-{a['equipment_id']}-{a['shot_no']}", "equipment": a["equipment_id"],
                          "faultStart": None, "faultEnd": None, "alarms": [a], "expected_cause": "NOISE", "expected_decision": "OBSERVE"})
    return cases


def is_correct(c, out):
    decision, cause = out.get("decision"), out.get("causeCode")
    if c["expected_decision"] == "NCR_DRAFT":
        return decision == "NCR_DRAFT" and cause == c["expected_cause"]
    return decision == c["expected_decision"]


def reset_case(c):
    ids = ",".join(f"'{a['anomaly_id']}'" for a in c["alarms"])
    sql(f"UPDATE dbo.anomaly_event SET status = 'NEW' WHERE anomaly_id IN ({ids})")


def next_seed(c):
    """사례 경보 중 아직 처리되지 않은(NEW) 가장 이른 경보."""
    new_ids = {a["anomaly_id"] for a in http("GET", f"/anomalies?equipmentId={c['equipment']}&status=NEW&top=5000")}
    for a in c["alarms"]:  # 이미 발생 순서로 정렬됨
        if a["anomaly_id"] in new_ids:
            return a
    return None


def run_case(c, run, stamp, gt):
    reset_case(c)
    tag = f"replay-{stamp}-r{run}-{c['key']}"[:60]
    steps = []
    diagnosed = None
    for step in range(1, MAX_STEPS + 1):
        seed = next_seed(c)
        if seed is None:
            break
        print(f"  [{run}회차 {step}단계] {c['case']} — {c['equipment']} shot {seed['shot_no']} "
              f"{seed['rule_code']} {seed['sensor_key']} ...", flush=True)
        out = http("POST", f"/analyze/{seed['anomaly_id']}?replay=true&tag={tag}")
        trace = out.get("toolTrace") or []
        result = out.get("result") or {}
        ok = is_correct(c, out)
        s = {
            "step": step, "seedShot": seed["shot_no"], "seedRule": seed["rule_code"], "seedSensor": seed["sensor_key"],
            "alarmsInIncident": len(out.get("anomalyIds") or []),
            "decision": out.get("decision"), "cause": out.get("causeCode"), "confidence": out.get("confidence"),
            "correct": ok, "ncrNo": out.get("ncrNo"), "mergedIntoExistingNcr": out.get("mergedIntoExistingNcr"),
            "watchItems": result.get("watchItems"), "reasoningSummary": result.get("reasoningSummary"),
            "similarCases": result.get("similarCases"),
            "toolCalls": len([t for t in trace if not t.startswith(" ") and not t.startswith("repairJsonFormat")]),
            "jsonRepaired": any(t.startswith("repairJsonFormat") for t in trace),
            "toolTrace": trace, "latencyMs": out.get("latencyMs"), "error": out.get("error"),
        }
        steps.append(s)
        print(f"      → {s['decision']} / {s['cause']} (확신도 {s['confidence']}){' ✅' if ok else ''}", flush=True)
        if ok:
            diagnosed = s
            break

    start = c["faultStart"]
    last_shot = (diagnosed or (steps[-1] if steps else {"seedShot": None}))["seedShot"]

    def defects_until(shot):
        if start is None or shot is None:
            return None
        return sum(1 for n in range(start, shot + 1) if gt.get((c["equipment"], n)))

    # 고장 구간 안의 첫 불량 (구간 밖의 산발 불량은 그 고장과 무관하므로 보지 않는다)
    first_defect = None
    if start is not None:
        first_defect = next((n for n in range(start, c["faultEnd"]) if gt.get((c["equipment"], n))), None)
    return {
        "run": run, "case": c["case"], "equipment": c["equipment"], "runTag": tag,
        "expectedCause": c["expected_cause"], "expectedDecision": c["expected_decision"],
        "faultStart": start, "firstDefectShot": first_defect,
        "diagnosed": diagnosed is not None,
        "diagnosisShot": diagnosed["seedShot"] if diagnosed else None,
        "shotsToDiagnosis": (diagnosed["seedShot"] - start) if diagnosed and start is not None else None,
        "defectsUntilDiagnosis": defects_until(diagnosed["seedShot"]) if diagnosed else None,
        "lastAnalyzedShot": last_shot, "defectsUntilLastStep": defects_until(last_shot),
        "wrongDrafts": sum(1 for s in steps if s["decision"] == "NCR_DRAFT" and not s["correct"]),
        "steps": len(steps),
        "llmCalls": len(steps) + sum(s["jsonRepaired"] for s in steps),
        "toolCalls": sum(s["toolCalls"] for s in steps),
        "failed": sum(s["decision"] == "FAILED" for s in steps),
        "latencyMs": sum(s["latencyMs"] or 0 for s in steps),
        "stepDetails": steps,
    }


def fmt(v):
    return "-" if v is None else str(v)


def summarize(results, runs, mode):
    title = "지식저장소 제거 실험 (시간 순 재생)" if mode == "hidden" else "시간 순 재생 평가"
    by_case = {}
    for r in results:
        by_case.setdefault(r["case"], []).append(r)
    lines = [f"## {title} — 사례 {len(by_case)}건 × {runs}회 (사례당 최대 {MAX_STEPS}단계)", ""]
    lines.append("| 사례 | 정답 | 진단까지 shot (고장 시작 대비) | 진단 시점까지 불량 | 첫 불량 shot | "
                 "잘못된 초안 | LLM 호출 | 평균 소요/단계(초) |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for case, rs in by_case.items():
        lat = [r["latencyMs"] / r["steps"] for r in rs if r["steps"]]
        first = rs[0]["firstDefectShot"]
        start = rs[0]["faultStart"]
        lines.append(
            f"| {case} | {sum(r['diagnosed'] for r in rs)}/{len(rs)} "
            f"| {', '.join(fmt(r['shotsToDiagnosis']) if r['diagnosed'] else '미진단' for r in rs)} "
            f"| {', '.join(fmt(r['defectsUntilDiagnosis']) if r['diagnosed'] else '-' for r in rs)} "
            f"| {fmt(first - start) if first is not None else ('구간 내 없음' if start is not None else '-')} "
            f"| {sum(r['wrongDrafts'] for r in rs)} "
            f"| {', '.join(str(r['llmCalls']) for r in rs)} "
            f"| {round(sum(lat) / len(lat) / 1000, 1) if lat else '-'} |")
    n = len(results)
    lines.append("")
    lines.append(f"- 정답 진단: **{sum(r['diagnosed'] for r in results)}/{n}** "
                 f"(고장: NCR_DRAFT + 정답 원인, 센서 이상: SENSOR_CHECK, 단발성: OBSERVE)")
    lines.append(f"- 잘못 만든 초안: {sum(r['wrongDrafts'] for r in results)}건, "
                 f"실패(FAILED): {sum(r['failed'] for r in results)}건, "
                 f"JSON 형식 재요청: {sum(s['jsonRepaired'] for r in results for s in r['stepDetails'])}회")
    lines.append(f"- LLM 호출 합계: {sum(r['llmCalls'] for r in results)}회 "
                 f"(분석 {sum(r['steps'] for r in results)}단계 + JSON 재요청), "
                 f"도구 호출 합계: {sum(r['toolCalls'] for r in results)}회")
    lines.append("- '진단까지 shot', '첫 불량 shot'은 고장 시작 shot 기준 차이. 단발성 경보는 고장 구간이 없어 '-'")
    lines.append("")
    lines.append("### 단계별 판단")
    for r in results:
        lines.append("")
        lines.append(f"**{r['case']} — {r['run']}회차** (`{r['runTag']}`)")
        lines.append("")
        lines.append("| 단계 | 시작 경보 | 판단 | 원인 | 확신도 | watchItems |")
        lines.append("|---|---|---|---|---|---|")
        for s in r["stepDetails"]:
            watch = "; ".join(s["watchItems"] or []).replace("|", "/")
            lines.append(f"| {s['step']} | shot {s['seedShot']} {s['seedRule']} {s['seedSensor']} "
                         f"| {s['decision']}{' ✅' if s['correct'] else ''} | {s['cause']} | {fmt(s['confidence'])} "
                         f"| {watch or '-'} |")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="QualiGuard 에이전트 시간 순 재생 평가")
    ap.add_argument("--runs", type=int, default=1, help="같은 사례를 반복 실행할 횟수")
    ap.add_argument("--hide-matching-ncr", action="store_true",
                    help="정답과 같은 원인의 과거 부적합 사례를 숨기고 실행 (끝나면 복구)")
    args = ap.parse_args()
    mode = "hidden" if args.hide_matching_ncr else "base"

    gt = load_ground_truth()
    cases = build_cases()
    stamp = datetime.now().strftime("%m%d%H%M%S")
    ncr_list = ",".join(f"'{n}'" for n in MATCHING_NCR)
    results = []
    try:
        if args.hide_matching_ncr:
            # 유사 사례 검색(usp_FindSimilarNcr)은 APPROVED/CLOSED 만 보므로 상태를 잠시 바꿔 숨긴다
            sql(f"UPDATE dbo.ncr SET status = 'REJECTED' WHERE ncr_no IN ({ncr_list}) AND status = 'CLOSED'")
            print(f"과거 사례 {len(MATCHING_NCR)}건을 숨겼습니다: {', '.join(MATCHING_NCR)}")
        for run in range(1, args.runs + 1):
            for c in cases:
                print(f"[{run}회차] {c['case']} (경보 {len(c['alarms'])}건)", flush=True)
                results.append(run_case(c, run, stamp, gt))
    finally:
        if args.hide_matching_ncr:
            sql(f"UPDATE dbo.ncr SET status = 'CLOSED' WHERE ncr_no IN ({ncr_list}) AND status = 'REJECTED'")
            print("숨긴 과거 사례를 복구했습니다.")

    summary = summarize(results, args.runs, mode)
    print("\n" + summary)
    data_dir = ROOT / "data"
    data_dir.mkdir(exist_ok=True)
    (data_dir / f"agent_eval_{mode}.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    (data_dir / f"agent_eval_{mode}.md").write_text(summary + "\n", encoding="utf-8")
    print(f"\n저장: data/agent_eval_{mode}.json, data/agent_eval_{mode}.md")


if __name__ == "__main__":
    main()
