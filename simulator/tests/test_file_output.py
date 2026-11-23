"""탐지 평가용 파일 출력 시험 (로드맵 E2): MQTT 없이 파일로 쓰고, 같은 seed 면 같은 파일이 나온다."""
import json
import sys

from qg_simulator.__main__ import main


def run(monkeypatch, out, seed):
    monkeypatch.setattr(sys, "argv", ["qg-simulator", "--out", str(out), "--seed", str(seed), "--shots", "120"])
    main()
    return (out / "telemetry.jsonl").read_text(encoding="utf-8"), (out / "ground_truth.csv").read_text(encoding="utf-8")


def test_같은_seed_면_같은_파일(tmp_path, monkeypatch):
    a = run(monkeypatch, tmp_path / "a", 7)
    b = run(monkeypatch, tmp_path / "b", 7)
    assert a == b


def test_seed_가_다르면_다른_데이터(tmp_path, monkeypatch):
    assert run(monkeypatch, tmp_path / "a", 1)[0] != run(monkeypatch, tmp_path / "b", 2)[0]


def test_메시지는_설비_형식이고_정답은_shot_마다_한_줄(tmp_path, monkeypatch):
    telemetry, gt = run(monkeypatch, tmp_path / "a", 3)
    first = json.loads(telemetry.splitlines()[0])
    assert {"messageId", "lineId", "equipmentId", "ts"} <= first.keys()
    assert first["ts"].startswith("2026-01-01T08:00:00")   # 가상 시계: 실행 시각과 무관
    assert len(gt.splitlines()) == 1 + 120 * 2              # 머리글 + 설비 2대 × 120 shot
    assert not (tmp_path / "a" / "ground_truth_archive").exists()
