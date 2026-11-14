#!/usr/bin/env python3
"""에이전트 원인 추정 평가.

시뮬레이터 고장 시나리오(simulator/scenarios.yaml)를 정답으로 삼아,
각 고장 구간의 첫 경보와 정상 구간의 단발성 경보를 에이전트에게 분석시키고 채점한다.

사용법 (앱 실행 중, 시뮬레이터 전체 시나리오를 한 번 돌린 뒤):
    python3 scripts/evaluate_agent.py
결과: 화면에 표 출력 + data/agent_eval.json 저장

주의: 사례마다 LLM 을 1회 호출한다 (약 5~6회).
"""
import json
import sys
import time
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

API = "http://localhost:8080/api/agent"

# simulator/scenarios.yaml 의 고장 시나리오와 기대 결과
FAULTS = [
    # 설비,    시나리오,                 시작, 끝(미포함), 기대 원인 코드,      기대 판단
    ("IMM-01", "heater_degradation",     300,  800,  "HEATER_DEGRADATION", "NCR_DRAFT"),
    ("IMM-02", "pressure_sensor_spike",  400,  600,  "SENSOR_FAULT",       "SENSOR_CHECK"),
    ("IMM-02", "cooling_blockage",       900,  1400, "COOLING_ISSUE",      "NCR_DRAFT"),
    ("IMM-01", "check_ring_wear",        1200, 1800, "CHECK_RING_WEAR",    "NCR_DRAFT"),
]
MARGIN = 100  # 고장 구간 앞뒤 이 범위의 경보는 단발성 경보 평가에서 제외 (고장과 한 사건으로 묶이기 때문)


def http(method, path):
    req = urllib.request.Request(API + path, method=method)
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode("utf-8"))


def parse_ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def main():
    anomalies = http("GET", "/anomalies?top=5000")
    if not anomalies:
        sys.exit("경보가 없습니다. 시뮬레이터 전체 시나리오를 먼저 실행하세요.")
    # 가장 최근 실행분만 사용 (마지막 경보로부터 15분 이내)
    last = max(parse_ts(a["detected_at"]) for a in anomalies)
    anomalies = [a for a in anomalies if parse_ts(a["detected_at"]) >= last - timedelta(minutes=15)]

    cases = []
    for eq, name, start, end, cause, decision in FAULTS:
        inside = sorted((a for a in anomalies if a["equipment_id"] == eq and start <= a["shot_no"] < end),
                        key=lambda a: a["shot_no"])
        if inside:
            cases.append({"case": name, "equipment": eq, "seed": inside[0],
                          "expected_cause": cause, "expected_decision": decision})
    for a in sorted(anomalies, key=lambda a: (a["equipment_id"], a["shot_no"])):
        if a["rule_code"] != "INDIVIDUAL":
            continue
        near_fault = any(a["equipment_id"] == eq and start - MARGIN <= a["shot_no"] < end + MARGIN
                         for eq, _, start, end, _, _ in FAULTS)
        if not near_fault:
            cases.append({"case": f"단발성 경보 (shot {a['shot_no']} {a['sensor_name']})", "equipment": a["equipment_id"],
                          "seed": a, "expected_cause": "NOISE", "expected_decision": "OBSERVE"})

    results = []
    for c in cases:
        print(f"분석 중: {c['case']} ({c['equipment']} shot {c['seed']['shot_no']}) ...", flush=True)
        t0 = time.time()
        out = http("POST", f"/analyze/{c['seed']['anomaly_id']}")
        cause_ok = out.get("causeCode") == c["expected_cause"]
        decision_ok = out.get("decision") == c["expected_decision"]
        results.append({
            "case": c["case"], "equipment": c["equipment"], "seedShot": c["seed"]["shot_no"],
            "expectedCause": c["expected_cause"], "cause": out.get("causeCode"), "causeCorrect": cause_ok,
            "expectedDecision": c["expected_decision"], "decision": out.get("decision"), "decisionCorrect": decision_ok,
            "confidence": out.get("confidence"), "ncrNo": out.get("ncrNo"), "alarmsInIncident": len(out.get("anomalyIds") or []),
            "toolCalls": len(out.get("toolTrace") or []), "latencyMs": out.get("latencyMs"),
            "similarCases": (out.get("result") or {}).get("similarCases"), "error": out.get("error"),
            "wallSec": round(time.time() - t0, 1),
        })

    print("\n| 사례 | 기대 원인 | 에이전트 원인 | 판단 | 확신도 | 묶인 경보 | 도구 호출 | 소요(초) |")
    print("|---|---|---|---|---|---|---|---|")
    for r in results:
        mark = "✅" if r["causeCorrect"] and r["decisionCorrect"] else ("△" if r["causeCorrect"] or r["decisionCorrect"] else "❌")
        print(f"| {r['case']} | {r['expectedCause']} | {mark} {r['cause']} | {r['decision']} | {r['confidence']} "
              f"| {r['alarmsInIncident']} | {r['toolCalls']} | {round((r['latencyMs'] or 0) / 1000, 1)} |")
    n = len(results)
    print(f"\n원인 정답: {sum(r['causeCorrect'] for r in results)}/{n}, "
          f"판단 정답: {sum(r['decisionCorrect'] for r in results)}/{n}")

    out_path = Path(__file__).resolve().parent.parent / "data" / "agent_eval.json"
    out_path.parent.mkdir(exist_ok=True)
    out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"저장: {out_path}")


if __name__ == "__main__":
    main()
