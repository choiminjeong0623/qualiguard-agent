"""실제 설비 데이터처럼 결측·미할당·전송 이상을 섞는다 (ADR-017).

현장 PLC·게이트웨이는 센서 하나가 끊기면 값만 비우거나(null) 항목 자체를 빼고 보내고,
비전 검사기가 멈추면 검사 결과 없이 공정 값만 올라온다. 시리얼 번호는 출하 확정 전까지
없거나(null) 임시값(TMP-…)이고, 재작업·라벨 재출력으로 같은 임시값이 다시 쓰이기도 한다.
확정 시리얼은 출하 때 붙는다. 센서가 끊기면 몇~수십 shot 동안 같은 센서가 계속 비고,
네트워크 문제로 메시지가 통째로 빠지거나(누락) 같은 메시지가 다시 오거나(재전송) 늦게 온다(순서 어긋남).

결측은 '측정·전송' 문제다. 불량 판정(Machine._inspect)과 정답 기록(ground_truth.csv)은
실제 공정 값 기준으로 이미 끝난 뒤에 적용하므로 정답은 바뀌지 않는다.

난수는 공정 모델과 다른 생성기를 쓴다. 그래서 결측 확률을 0으로 두면 결측 기능이 없던 때와
같은 seed 에서 같은 공정 값·같은 불량이 나온다.
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import datetime


@dataclass(frozen=True)
class MissingConfig:
    sensor_null_rate: float = 0.0        # 센서 하나가 "value": null 로 오는 확률 (센서마다)
    sensor_drop_rate: float = 0.0        # 센서 키 자체가 빠지는 확률 (센서마다)
    inspection_missing_rate: float = 0.0  # inspection 블록이 null 인 확률 (shot 마다)
    shotno_missing_rate: float = 0.0     # shotNo 가 빠지는 확률 (shot 마다)
    serial_unassigned_rate: float = 0.0  # serialNo 가 null(미할당)인 확률
    serial_duplicate_rate: float = 0.0   # 직전 임시 시리얼을 다시 쓰는 확률
    sensor_outage_rate: float = 0.0      # shot 마다 센서 하나가 끊기기 시작할 확률 (끊긴 동안 계속 null)
    sensor_outage_min: float = 5         # 끊김 길이(shot) 최소
    sensor_outage_max: float = 30        # 끊김 길이(shot) 최대
    message_drop_rate: float = 0.0       # shot 메시지를 통째로 보내지 않을 확률
    resend_rate: float = 0.0             # 같은 messageId 메시지를 한 번 더 보낼 확률
    delay_rate: float = 0.0              # 메시지를 늦춰 뒤 shot 보다 늦게 보낼 확률
    delay_max_shots: float = 5           # 늦출 때 최대 몇 shot 뒤에 보낼지

    @classmethod
    def from_dict(cls, d: dict | None) -> "MissingConfig":
        d = d or {}
        unknown = set(d) - set(cls.__dataclass_fields__)
        if unknown:
            raise ValueError(f"missing 설정에 알 수 없는 항목: {sorted(unknown)}")
        cfg = cls(**{k: float(v) for k, v in d.items()})
        for k, v in vars(cfg).items():
            if k.endswith("_rate") and not 0.0 <= v <= 1.0:
                raise ValueError(f"missing.{k} 는 0~1 사이여야 합니다: {v}")
        if not 1 <= cfg.sensor_outage_min <= cfg.sensor_outage_max:
            raise ValueError("missing.sensor_outage_min 은 1 이상, sensor_outage_max 이하여야 합니다")
        if cfg.delay_max_shots < 1:
            raise ValueError("missing.delay_max_shots 는 1 이상이어야 합니다")
        return cfg

    def any_active(self) -> bool:
        return any(v for k, v in vars(self).items() if k.endswith("_rate"))


@dataclass
class SerialIssuer:
    """설비별 공정 중 임시 시리얼 발급기: TMP-<라인>-<설비>-<YYMMDD>-<5자리 순번>.

    확정 시리얼이 아니다. 출하 때 확정 시리얼과 shot 을 잇는 일은 shipment_serial_link 가 맡는다(ADR-017, 향후).
    """
    line_id: str
    equipment_id: str
    seq: int = 0
    last: str | None = None
    _day: str = field(default="", repr=False)

    def issue(self, now: datetime) -> str:
        day = now.strftime("%y%m%d")
        if day != self._day:
            self._day, self.seq = day, 0
        self.seq += 1
        self.last = f"TMP-{self.line_id}-{self.equipment_id}-{day}-{self.seq:05d}"
        return self.last


def serial_for(issuer: SerialIssuer, cfg: MissingConfig, rng: random.Random, now: datetime) -> str | None:
    """이번 shot 의 시리얼. 미할당이면 None, 중복이면 직전 번호."""
    r = rng.random()
    if r < cfg.serial_unassigned_rate:
        return None
    if r < cfg.serial_unassigned_rate + cfg.serial_duplicate_rate and issuer.last is not None:
        return issuer.last
    return issuer.issue(now)


@dataclass
class OutageTracker:
    """설비 1대의 센서 끊김 구간: 센서 키 → 남은 shot 수. 끊긴 동안 그 센서 값은 계속 null."""
    remaining: dict[str, int] = field(default_factory=dict)
    started: int = 0            # 지금까지 시작된 끊김 구간 수

    def step(self, keys: list[str], cfg: MissingConfig, rng: random.Random) -> set[str]:
        """이번 shot 에서 끊긴 센서 목록. 새 끊김 시작도 여기서 정한다."""
        if cfg.sensor_outage_rate and rng.random() < cfg.sensor_outage_rate:
            free = [k for k in keys if k not in self.remaining]
            if free:
                key = rng.choice(free)
                self.remaining[key] = rng.randint(int(cfg.sensor_outage_min), int(cfg.sensor_outage_max))
                self.started += 1
        out = set(self.remaining)
        for k in list(self.remaining):
            self.remaining[k] -= 1
            if self.remaining[k] <= 0:
                del self.remaining[k]
        return out


def apply_missing(payload: dict, cfg: MissingConfig, rng: random.Random,
                  outage: set[str] | None = None) -> dict:
    """완성된 페이로드에 결측을 섞는다 (payload 를 직접 고치고 돌려준다).

    outage: 끊긴 센서 (값을 null 로). OutageTracker.step 결과.
    """
    sensors = payload.get("sensors") or {}
    for key in list(sensors):
        if outage and key in outage:
            sensors[key]["value"] = None
            continue
        r = rng.random()
        if r < cfg.sensor_drop_rate:
            del sensors[key]                      # 키 자체가 없음
        elif r < cfg.sensor_drop_rate + cfg.sensor_null_rate:
            sensors[key]["value"] = None          # 키는 있고 값만 비어 있음
    if rng.random() < cfg.inspection_missing_rate:
        payload["inspection"] = None
    if rng.random() < cfg.shotno_missing_rate:
        del payload["shotNo"]
    return payload


class Delivery:
    """전송 이상 흉내: 통째 누락·재전송·지연(순서 어긋남).

    plan() 이 이번 shot 메시지를 어떻게 보낼지 정하고, due() 가 지금 보낼 메시지 목록을 돌려준다.
    지연·재전송 메시지는 shot 단계(step)를 기준으로 몇 단계 뒤에 나간다.
    """

    def __init__(self, cfg: MissingConfig, rng: random.Random):
        self.cfg = cfg
        self.rng = rng
        self.queue: list[tuple[int, int, str, str]] = []   # (보낼 단계, 넣은 순서, 설비 ID, 본문)
        self.order = 0
        self.dropped = self.resent = self.delayed = 0

    def plan(self, step: int, equipment_id: str, body: str) -> str:
        """이번 메시지의 처리를 정한다. 'dropped' / 'sent' / 'delayed' (+ '+resent') — 정답 기록용."""
        cfg, rng = self.cfg, self.rng
        if rng.random() < cfg.message_drop_rate:
            self.dropped += 1
            return "dropped"
        how = "sent"
        at = step
        if rng.random() < cfg.delay_rate:
            at = step + rng.randint(1, int(cfg.delay_max_shots))
            how = "delayed"
            self.delayed += 1
        self._push(at, equipment_id, body)
        if rng.random() < cfg.resend_rate:
            # 같은 messageId 를 한 번 더: 바로 또는 몇 shot 뒤 (QoS 1 재전송·게이트웨이 재시도 흉내)
            self._push(at + rng.randint(0, 3), equipment_id, body)
            self.resent += 1
            how += "+resent"
        return how

    def _push(self, at: int, equipment_id: str, body: str) -> None:
        self.order += 1
        self.queue.append((at, self.order, equipment_id, body))

    def due(self, step: int | None) -> list[tuple[str, str]]:
        """step 까지 보낼 (설비 ID, 본문) 목록, 넣은 순서대로. None 이면 남은 것 모두."""
        ready = sorted(i for i in self.queue if step is None or i[0] <= step)
        self.queue = [i for i in self.queue if not (step is None or i[0] <= step)]
        return [(eq, b) for _, _, eq, b in ready]
