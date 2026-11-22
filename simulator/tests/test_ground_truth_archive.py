"""정답 파일 보관·메타 기록 시험 (로드맵 E1)."""
import json
from argparse import Namespace

from qg_simulator.__main__ import archive_ground_truth, meta_path, write_ground_truth_meta
from qg_simulator.faults import Fault


def test_기존_정답_파일은_덮어쓰지_않고_보관한다(tmp_path):
    gt = tmp_path / "ground_truth.csv"
    gt.write_text("ts,equipmentId\nx,IMM-01\n", encoding="utf-8")
    meta_path(gt).write_text("{}", encoding="utf-8")

    moved = archive_ground_truth(gt)

    assert moved is not None and moved.exists()
    assert not gt.exists()
    assert moved.read_text(encoding="utf-8").startswith("ts,equipmentId")
    assert len(list((tmp_path / "ground_truth_archive").glob("*.meta.json"))) == 1


def test_정답_파일이_없으면_보관하지_않는다(tmp_path):
    assert archive_ground_truth(tmp_path / "ground_truth.csv") is None


def test_메타에_설정_해시와_고장_시나리오를_남긴다(tmp_path):
    cfg = tmp_path / "scenarios.yaml"
    cfg.write_text("simulation: {}\n", encoding="utf-8")
    gt = tmp_path / "ground_truth.csv"
    faults = [Fault("heater_degradation", "IMM-01", 300, 500)]

    write_ground_truth_meta(gt, Namespace(config=str(cfg)), {"seed": 42, "equipment": ["IMM-01"]}, 2000, faults)

    meta = json.loads(meta_path(gt).read_text(encoding="utf-8"))
    assert meta["seed"] == 42
    assert meta["shotsPerEquipment"] == 2000
    assert len(meta["configSha256"]) == 64
    assert meta["faults"][0] == {"name": "heater_degradation", "equipment": "IMM-01", "startShot": 300, "duration": 500}
