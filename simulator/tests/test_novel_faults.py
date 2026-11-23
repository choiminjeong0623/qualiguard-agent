"""새 고장(ML 학습에 없음)과 세기 배율 (eval/ml_novel_report.md)."""
import random

from qg_simulator.faults import FAULT_CATALOG, NOVEL_FAULTS, Fault, effects_for
from qg_simulator.process import Machine


def test_새_고장은_목록에_있고_기본_시나리오와_겹치지_않는다():
    for name in NOVEL_FAULTS:
        assert name in FAULT_CATALOG
    assert {"heater_degradation", "cooling_blockage", "check_ring_wear", "pressure_sensor_spike"}.isdisjoint(NOVEL_FAULTS)


def test_세기_1_은_예전_값과_같고_세기가_크면_더_빨리_변한다():
    base = Fault("heater_degradation", "IMM-01", 0, 500)
    strong = Fault("heater_degradation", "IMM-01", 0, 500, severity=1.4)
    a, _ = effects_for(base, 100, random.Random(0))
    b, _ = effects_for(strong, 100, random.Random(0))
    assert a["barrelTemp"] == -0.04 * 100
    assert b["barrelTemp"] < a["barrelTemp"]


def test_노즐_막힘은_압력이_오르지만_실린더_온도는_그대로():
    add, _ = effects_for(Fault("nozzle_clog", "IMM-01", 0, 400), 300, random.Random(0))
    assert add["injectionPressure"] > 0
    assert "barrelTemp" not in add
    assert add["_defectP"]["SHORT_SHOT"] > 0


def test_금형_마모는_센서보다_불량을_늘린다():
    fault = Fault("mold_wear", "IMM-01", 0, 400)
    rng = random.Random(1)
    m_ok, m_bad = Machine("L", "IMM-01", random.Random(2)), Machine("L", "IMM-01", random.Random(2))
    ok = bad = 0
    for shot in range(1, 401):
        add, scale = effects_for(fault, shot, rng)
        _, d1 = m_ok.next_shot({}, {})
        _, d2 = m_bad.next_shot(add, scale)
        ok += d1 is not None
        bad += d2 == "FLASH"
    assert bad > ok + 20
