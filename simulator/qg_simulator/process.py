"""사출성형기 1대의 공정 모델.

한 번 사출(shot)할 때마다 센서 값 묶음 1개와 인라인 비전 검사 결과 1개를 만든다.
실제 사출성형기도 사이클(shot) 단위로 공정 데이터를 보고하므로(Euromap 63/77 등),
센서별로 따로 보내지 않고 한 shot의 값을 한 메시지에 묶는다.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass, field

# 센서 정의: 키 -> (한글 이름, 단위, 기준값, 정상 변동 표준편차)
SENSORS: dict[str, tuple[str, str, float, float]] = {
    "moldTemp":          ("금형 온도",   "C",   60.0,   0.6),
    "barrelTemp":        ("실린더 온도", "C",   230.0,  0.8),
    "injectionPressure": ("사출 압력",   "bar", 1200.0, 8.0),
    "holdingPressure":   ("보압",        "bar", 600.0,  4.0),
    "cushion":           ("쿠션량",      "mm",  5.0,    0.08),
    "cycleTime":         ("사이클 타임", "s",   30.0,   0.15),
    "motorCurrent":      ("모터 전류",   "A",   45.0,   0.6),
}

DEFECT_TYPES = {
    "SHORT_SHOT": "미성형",
    "FLASH": "버(Flash)",
    "DIMENSION": "치수 불량",
    "WARPAGE": "휨",
}


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


@dataclass
class Machine:
    line_id: str
    equipment_id: str
    rng: random.Random
    shot_no: int = 0
    # 기준값을 설비마다 살짝 다르게 (같은 기종이라도 개체 차이가 있다)
    offsets: dict[str, float] = field(default_factory=dict)

    def __post_init__(self) -> None:
        for key, (_, _, _, std) in SENSORS.items():
            self.offsets.setdefault(key, self.rng.gauss(0, std * 0.5))

    def next_shot(self, effects: dict[str, float], noise_scale: dict[str, float]) -> tuple[dict, str | None]:
        """다음 shot의 센서 값과 불량 유형(없으면 None)을 돌려준다.

        effects: 고장 시나리오가 더하는 값 (센서 키 -> 더할 값)
        noise_scale: 고장 시나리오가 키우는 변동 배율 (센서 키 -> 배율)
        """
        self.shot_no += 1
        values: dict[str, float] = {}
        for key, (_, _, nominal, std) in SENSORS.items():
            v = nominal + self.offsets[key]
            v += self.rng.gauss(0, std * noise_scale.get(key, 1.0))
            v += effects.get(key, 0.0)
            values[key] = round(v, 2)
        return values, self._inspect(values, effects)

    def _inspect(self, v: dict[str, float], effects: dict[str, float]) -> str | None:
        """인라인 비전 검사 흉내: 공정 조건에서 불량 확률을 계산해 판정한다.

        센서 이상(spike)처럼 실제 공정이 아닌 측정값 이상은 품질에 영향을 주지 않도록
        effects에 '_sensorOnly' 표시가 있으면 해당 센서 효과를 빼고 판정한다.
        """
        real = dict(v)
        for key in effects.get("_sensorOnlyKeys", []):
            real[key] = round(v[key] - effects.get(key, 0.0), 2)

        # 공정 조건별 불량 확률 (기준 근처에서는 거의 0, 벗어날수록 급격히 증가)
        p = {
            # 용융 수지 온도가 낮거나 쿠션이 부족하면 금형이 덜 채워진다
            "SHORT_SHOT": _sigmoid((222.0 - real["barrelTemp"]) * 0.9) * 0.6
                          + _sigmoid((3.9 - real["cushion"]) * 6.0) * 0.6,
            # 압력이 과하면 금형 틈으로 수지가 새어 버가 생긴다
            "FLASH": _sigmoid((real["injectionPressure"] - 1275.0) * 0.12) * 0.5,
            # 금형이 뜨거우면 수축이 커져 치수가 벗어난다
            "DIMENSION": _sigmoid((real["moldTemp"] - 65.0) * 1.2) * 0.5,
            # 냉각이 고르지 않으면 휨이 생긴다
            "WARPAGE": _sigmoid((real["moldTemp"] - 67.0) * 1.0) * 0.3,
        }
        # 센서로 바로 설명되지 않는 고장 영향 (원료 수분·노즐 막힘·금형 마모 같은 새 고장, faults.py)
        for kind, extra in (effects.get("_defectP") or {}).items():
            p[kind] = p[kind] + extra
        base_rate = 0.004  # 정상 상태에서도 생기는 우발 불량 (약 0.4%)
        total = min(0.95, base_rate + sum(p.values()))
        if self.rng.random() >= total:
            return None
        # 확률 비중에 따라 불량 유형 선택 (우발 불량은 무작위 유형)
        weights = [p[k] + base_rate / len(p) for k in DEFECT_TYPES]
        return self.rng.choices(list(DEFECT_TYPES), weights=weights, k=1)[0]
