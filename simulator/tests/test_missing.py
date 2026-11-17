"""결측·시리얼 섞기 시험: uv run --with pytest pytest"""
import random
from datetime import datetime

import pytest

from qg_simulator.__main__ import build_payload, missing_fields
from qg_simulator.missing import (Delivery, MissingConfig, OutageTracker, SerialIssuer, apply_missing,
                                  serial_for)
from qg_simulator.process import Machine

NOW = datetime(2026, 11, 17, 9, 0)


def payload():
    m = Machine("LINE1", "IMM-01", random.Random(1))
    values, defect = m.next_shot({}, {})
    return build_payload(m, values, defect, "S-1", NOW)


def test_확률_0이면_그대로():
    p = payload()
    before = repr(p)
    apply_missing(p, MissingConfig(), random.Random(0))
    assert repr(p) == before
    assert missing_fields(p) == []


def test_확률_1이면_모두_빠짐():
    p = apply_missing(payload(), MissingConfig(sensor_drop_rate=1, inspection_missing_rate=1,
                                               shotno_missing_rate=1), random.Random(0))
    assert p["sensors"] == {} and p["inspection"] is None and "shotNo" not in p
    assert "shotNo" in missing_fields(p) and "sensors.moldTemp" in missing_fields(p)


def test_센서_null():
    p = apply_missing(payload(), MissingConfig(sensor_null_rate=1), random.Random(0))
    assert all(v["value"] is None and v["unit"] for v in p["sensors"].values())


def test_시리얼_미할당과_중복():
    issuer = SerialIssuer("LINE1", "IMM-01")
    assert serial_for(issuer, MissingConfig(serial_unassigned_rate=1), random.Random(0), NOW) is None
    first = serial_for(issuer, MissingConfig(), random.Random(0), NOW)
    assert first == "TMP-LINE1-IMM-01-261117-00001"   # 공정 중 임시 시리얼
    assert serial_for(issuer, MissingConfig(serial_duplicate_rate=1), random.Random(0), NOW) == first


def test_잘못된_설정은_거절():
    with pytest.raises(ValueError):
        MissingConfig.from_dict({"sensor_null_rate": 2})
    with pytest.raises(ValueError):
        MissingConfig.from_dict({"typo_rate": 0.1})


def test_센서_끊김은_정한_길이만큼_이어진다():
    cfg = MissingConfig(sensor_outage_rate=1, sensor_outage_min=5, sensor_outage_max=5)
    t = OutageTracker()
    rng = random.Random(3)
    first = t.step(["a"], cfg, rng)
    assert first == {"a"}
    runs = [t.step(["a"], MissingConfig(), rng) for _ in range(5)]
    assert runs[:4] == [{"a"}] * 4 and runs[4] == set()      # 5 shot 연속 끊김 뒤 회복
    p = apply_missing(payload(), MissingConfig(), random.Random(0), {"moldTemp"})
    assert p["sensors"]["moldTemp"]["value"] is None


def test_통째_누락은_보내지_않는다():
    d = Delivery(MissingConfig(message_drop_rate=1), random.Random(0))
    assert d.plan(0, "E", "m1") == "dropped"
    assert d.due(0) == [] and d.dropped == 1


def test_재전송은_같은_본문을_두_번_보낸다():
    d = Delivery(MissingConfig(resend_rate=1), random.Random(0))
    assert d.plan(0, "E", "m1") == "sent+resent"
    assert [b for _, b in d.due(None)] == ["m1", "m1"]


def test_지연은_뒤_shot_보다_늦게_보낸다():
    d = Delivery(MissingConfig(delay_rate=1, delay_max_shots=3), random.Random(0))
    assert d.plan(0, "E", "late") == "delayed"
    d.cfg = MissingConfig()
    d.plan(1, "E", "on-time")
    sent = [b for _, b in d.due(1)] + [b for _, b in d.due(None)]
    assert sent.index("on-time") < sent.index("late")
