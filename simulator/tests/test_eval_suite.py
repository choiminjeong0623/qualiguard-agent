"""평가 묶음 scenarios_eval.yaml (로드맵 E4)."""
import random
from pathlib import Path

import pytest
import yaml

from qg_simulator.__main__ import apply_suite_run
from qg_simulator.faults import FAULT_CATALOG, Fault, effects_for
from qg_simulator.process import Machine

ROOT = Path(__file__).resolve().parent.parent
BASE = yaml.safe_load((ROOT / "scenarios.yaml").read_text(encoding="utf-8"))
SUITE = yaml.safe_load((ROOT / "scenarios_eval.yaml").read_text(encoding="utf-8"))


def test_묶음은_44사례_개발셋_17_홀드아웃_27():
    counts = {"dev": 0, "holdout": 0}
    for run in SUITE["runs"]:
        cfg = apply_suite_run(BASE, SUITE, run["id"])
        for f in cfg["faults"]:
            assert f["name"] in FAULT_CATALOG
        counts[run["split"]] += len(cfg["faults"])
    assert counts == {"dev": 17, "holdout": 27}


def test_이미_쓴_seed_는_쓰지_않고_홀드아웃_seed_는_개발셋에_없다():
    seeds = {r["split"]: set() for r in SUITE["runs"]}
    for r in SUITE["runs"]:
        seeds[r["split"]].add(r["seed"])
    used = {42, *range(1, 6), *range(101, 131)}
    assert not (seeds["dev"] | seeds["holdout"]) & used
    assert not seeds["dev"] & seeds["holdout"]


def test_실행을_고르면_seed_세기_split_이_바뀌고_기본_설정은_그대로():
    cfg = apply_suite_run(BASE, SUITE, "dev-s7-weak")
    assert cfg["simulation"]["seed"] == 7
    assert cfg["simulation"]["evalRun"]["split"] == "dev"
    assert {f["severity"] for f in cfg["faults"]} == {0.6}
    assert BASE["simulation"]["seed"] == 42 and "evalRun" not in BASE["simulation"]
    amb = apply_suite_run(BASE, SUITE, "holdout-ambiguous")["simulation"]["evalRun"]
    assert amb["ambiguous"] == ["material_moisture", "mold_wear", "nozzle_clog"]


def test_없는_실행은_멈춘다():
    with pytest.raises(SystemExit):
        apply_suite_run(BASE, SUITE, "nope")


def test_센서_드리프트는_측정값만_내려가고_불량은_늘지_않는다():
    fault = Fault("sensor_drift", "IMM-02", 0, 400)
    add, _ = effects_for(fault, 300, random.Random(0))
    assert add["barrelTemp"] < -8 and add["_sensorOnlyKeys"] == ["barrelTemp"]
    assert "injectionPressure" not in add
    rng = random.Random(1)
    m_ok, m_drift = Machine("L", "IMM-02", random.Random(2)), Machine("L", "IMM-02", random.Random(2))
    ok = bad = 0
    for shot in range(1, 401):
        a, s = effects_for(fault, shot, rng)
        _, d1 = m_ok.next_shot({}, {})
        _, d2 = m_drift.next_shot(a, s)
        ok += d1 is not None
        bad += d2 is not None
    assert bad == ok
