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
    severity: float = 1.0  # 진행 속도·최대 크기 배율 (1.0 = 기본 시나리오, 무작위 시나리오에서 0.6~1.4)

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
    # ── 아래는 ML 학습에 쓰지 않는 '새 고장' (기본 시나리오에 없음, eval/ml_novel_report.md) ──
    # 아는 고장과 일부만 닮게 만들었다: ML 이 비슷한 고장으로 자신 있게 착각하는지 보기 위해서다.
    "material_moisture": "원료 수분 과다(건조 불량) — 가수분해로 점도가 떨어져 사출 압력이 내려가고 흔들린다. 실린더 온도는 정상. 버 증가.",
    "nozzle_clog": "노즐 막힘 — 흐름이 막혀 사출 압력·모터 전류가 오르고 쿠션량이 늘어난다(히터 열화와 닮았지만 실린더 온도는 정상). 미성형 증가.",
    "mold_wear": "금형 파팅면 마모 — 센서 값은 거의 그대로인데 틈으로 수지가 새어 버가 늘어난다. 보압이 조금 흔들린다.",
    # ── E4 평가 묶음(scenarios_eval.yaml)에서만 쓰는 센서 고장 ──
    "sensor_drift": "실린더 온도 센서 드리프트 — 온도 측정값만 서서히 내려간다(히터 열화와 닮음). 실제 온도·압력·전류는 정상이라 품질 영향 없음.",
}

# ML 학습·기본 평가에 쓰지 않는 새 고장
NOVEL_FAULTS = ("material_moisture", "nozzle_clog", "mold_wear")


def effects_for(fault: Fault, shot_no: int, rng: random.Random) -> tuple[dict[str, float], dict[str, float]]:
    """고장이 이번 shot에 주는 영향: (더할 값, 변동 배율)."""
    t = fault.progress(shot_no)
    k = fault.severity
    add: dict[str, float] = {}
    scale: dict[str, float] = {}

    if fault.name == "heater_degradation":
        drop = min(16.0 * k, 0.04 * k * t)      # shot당 0.04℃씩, 최대 16℃ 하락
        add["barrelTemp"] = -drop
        add["injectionPressure"] = drop * 3.5   # 온도 1℃ 하락당 압력 약 3.5bar 상승
        add["motorCurrent"] = drop * 0.15
    elif fault.name == "cooling_blockage":
        rise = min(10.0 * k, 0.025 * k * t)            # shot당 0.025℃씩, 최대 10℃ 상승
        add["moldTemp"] = rise
        add["cycleTime"] = rise * 0.25          # 냉각이 늦어져 사이클이 길어진다
    elif fault.name == "check_ring_wear":
        add["cushion"] = -min(2.2 * k, 0.005 * k * t)   # 쿠션량 감소
        scale["injectionPressure"] = 1.0 + min(3.0 * k, 0.01 * k * t)
        scale["cushion"] = 1.0 + min(2.0 * k, 0.006 * k * t)
    elif fault.name == "pressure_sensor_spike":
        if rng.random() < 0.06 * k:
            add["injectionPressure"] = rng.choice([1, -1]) * rng.uniform(150, 350)
            add["_sensorOnlyKeys"] = ["injectionPressure"]  # type: ignore[assignment]
    elif fault.name == "sensor_drift":
        drift = min(12.0 * k, 0.03 * k * t)     # 측정값만 shot당 0.03℃씩, 최대 12℃ 내려간다
        add["barrelTemp"] = -drift
        add["_sensorOnlyKeys"] = ["barrelTemp"]  # type: ignore[assignment]  # 품질 판정은 실제 온도로
    elif fault.name == "material_moisture":
        fall = min(70.0 * k, 0.2 * k * t)       # 점도 저하 → 사출 압력 하락
        add["injectionPressure"] = -fall
        add["holdingPressure"] = -fall * 0.3
        scale["injectionPressure"] = 1.0 + min(1.5 * k, 0.004 * k * t)
        add["_defectP"] = {"FLASH": min(0.25, fall / 70.0 * 0.25)}  # type: ignore[assignment]
    elif fault.name == "nozzle_clog":
        rise = min(60.0 * k, 0.15 * k * t)      # 막힘 → 압력 상승 (실린더 온도는 그대로)
        add["injectionPressure"] = rise
        add["motorCurrent"] = rise * 0.04
        add["cushion"] = min(0.9 * k, 0.0022 * k * t)
        add["cycleTime"] = rise * 0.004
        add["_defectP"] = {"SHORT_SHOT": min(0.3, rise / 60.0 * 0.3)}  # type: ignore[assignment]
    elif fault.name == "mold_wear":
        level = min(1.0, 0.003 * k * t)         # 센서 변화는 작고 불량만 는다
        scale["holdingPressure"] = 1.0 + level * 0.8
        add["_defectP"] = {"FLASH": 0.2 * level}  # type: ignore[assignment]
    else:
        raise ValueError(f"알 수 없는 시나리오: {fault.name} (가능: {', '.join(FAULT_CATALOG)})")
    return add, scale
