"""고장 시나리오.

각 시나리오는 시작 shot부터 서서히(또는 갑자기) 센서 값을 바꾼다.
에이전트의 원인 추정을 평가할 수 있도록 '정답(어떤 고장이 진행 중인지)'을
별도 CSV(ground truth)로 남긴다. 이 정답은 MQTT 메시지에는 넣지 않는다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass


@dataclass
class Fault:
    name: str          # 시나리오 이름
    equipment_id: str  # 대상 설비
    start_shot: int    # 시작 shot 번호
    duration: int      # 지속 shot 수 (끝나면 정비 완료로 보고 정상 복귀)

    def active(self, shot_no: int) -> bool:
        return self.start_shot <= shot_no < self.start_shot + self.duration

    def progress(self, shot_no: int) -> int:
        return shot_no - self.start_shot


# 시나리오 설명 (README·에이전트 평가용)
FAULT_CATALOG = {
    "heater_degradation": "실린더 히터 열화 — 실린더 온도가 서서히 떨어지고, 점도가 높아진 수지를 밀어내느라 사출 압력이 오른다. 결국 미성형 증가.",
    "cooling_blockage": "금형 냉각수 유로 막힘 — 금형 온도가 서서히 오르고 냉각 시간이 늘어 사이클 타임이 길어진다. 치수 불량·휨 증가.",
    "check_ring_wear": "체크 링 마모 — 역류로 쿠션량이 줄고 사출 압력 변동이 커진다. 미성형 증가.",
    "pressure_sensor_spike": "사출 압력 센서 이상 — 압력 값이 가끔 튀지만 실제 공정은 정상. 품질 영향 없음(오탐 판별 시험용).",
}


def effects_for(fault: Fault, shot_no: int, rng: random.Random) -> tuple[dict[str, float], dict[str, float]]:
    """고장이 이번 shot에 주는 영향: (더할 값, 변동 배율)."""
    t = fault.progress(shot_no)
    add: dict[str, float] = {}
    scale: dict[str, float] = {}

    if fault.name == "heater_degradation":
        drop = min(16.0, 0.04 * t)              # shot당 0.04℃씩, 최대 16℃ 하락
        add["barrelTemp"] = -drop
        add["injectionPressure"] = drop * 3.5   # 온도 1℃ 하락당 압력 약 3.5bar 상승
        add["motorCurrent"] = drop * 0.15
    elif fault.name == "cooling_blockage":
        rise = min(10.0, 0.025 * t)             # shot당 0.025℃씩, 최대 10℃ 상승
        add["moldTemp"] = rise
        add["cycleTime"] = rise * 0.25          # 냉각이 늦어져 사이클이 길어진다
    elif fault.name == "check_ring_wear":
        add["cushion"] = -min(2.2, 0.005 * t)   # 쿠션량 감소
        scale["injectionPressure"] = 1.0 + min(3.0, 0.01 * t)
        scale["cushion"] = 1.0 + min(2.0, 0.006 * t)
    elif fault.name == "pressure_sensor_spike":
        if rng.random() < 0.06:
            add["injectionPressure"] = rng.choice([1, -1]) * rng.uniform(150, 350)
            add["_sensorOnlyKeys"] = ["injectionPressure"]  # type: ignore[assignment]
    else:
        raise ValueError(f"알 수 없는 시나리오: {fault.name} (가능: {', '.join(FAULT_CATALOG)})")
    return add, scale
