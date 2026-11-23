"""생산 로트 번호 (로드맵 P2): shot 번호로만 정해지고 lot_size 마다 바뀐다."""
import random
from datetime import date, datetime

from qg_simulator.__main__ import KST, build_payload, lot_no
from qg_simulator.process import Machine


def test_lot_changes_every_lot_size_shots():
    d = date(2026, 11, 23)
    assert lot_no("IMM-01", 1, d) == "L261123-IMM-01-01"
    assert lot_no("IMM-01", 250, d) == "L261123-IMM-01-01"
    assert lot_no("IMM-01", 251, d) == "L261123-IMM-01-02"
    assert lot_no("IMM-01", 501, d) == "L261123-IMM-01-03"
    assert lot_no("IMM-02", 10, d, lot_size=5) == "L261123-IMM-02-02"


def test_payload_carries_lot_without_touching_rng():
    m = Machine("LINE1", "IMM-01", random.Random(1))
    m.shot_no = 251
    state = m.rng.getstate()
    p = build_payload(m, {}, None, None, datetime(2026, 11, 24, 0, 5, tzinfo=KST), date(2026, 11, 23))
    assert p["lotNo"] == "L261123-IMM-01-02"
    assert m.rng.getstate() == state
