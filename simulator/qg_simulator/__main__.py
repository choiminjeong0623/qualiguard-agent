"""센서 시뮬레이터 실행 진입점.

예)
  uv run qg-simulator                       # scenarios.yaml 설정으로 Mosquitto에 발행
  uv run qg-simulator --dry-run --shots 5   # 브로커 없이 화면에 출력만 (정답 파일은 쓰지 않음)
  uv run qg-simulator --out ../eval/detection/runs/seed-1 --seed 1
                                            # 탐지 평가용 파일 출력 (MQTT·대기 없음, 로드맵 E2)
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import random
import shutil
import signal
import sys
import time
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

from .faults import FAULT_CATALOG, Fault, effects_for
from .missing import Delivery, MissingConfig, OutageTracker, SerialIssuer, apply_missing, serial_for
from .process import SENSORS, Machine

KST = ZoneInfo("Asia/Seoul")


def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_dotenv(path: Path) -> dict:
    """프로젝트 루트 .env 를 읽는다 (KEY=VALUE 줄만). 환경 변수가 있으면 그쪽이 우선."""
    out: dict = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    out.update({k: v for k, v in os.environ.items() if k.startswith("MQTT_")})
    return out


def tls_ca_path(env: dict) -> Path | None:
    """MQTT TLS CA 인증서 (로드맵 Q5): .env 의 MQTT_TLS_CA. 상대 경로는 저장소 루트 기준. 없으면 평문(1883)."""
    v = (env.get("MQTT_TLS_CA") or "").strip()
    if not v:
        return None
    p = Path(v)
    if not p.is_absolute():
        p = Path(__file__).resolve().parent.parent.parent / p
    if not p.is_file():
        raise SystemExit(f"MQTT_TLS_CA 파일이 없습니다: {p} (./scripts/mqtt_tls.sh 로 만드세요)")
    return p


def device_password(env: dict, equipment_id: str) -> str | None:
    """장비 계정 비밀번호 (N-07): MQTT_DEVICE_PASSWORD_IMM_01 처럼 설비 ID 의 '-' 를 '_' 로."""
    return env.get("MQTT_DEVICE_PASSWORD_" + equipment_id.replace("-", "_")) or None


LOT_SIZE = 250


def lot_no(equipment_id: str, shot_no: int, day: date, lot_size: int = LOT_SIZE) -> str:
    """생산 로트 번호: 시작일 + 설비 + 순번. shot 번호로만 정해지므로 난수 흐름(정답 재현)에 영향이 없다.
    예) 2026-11-23 시작, IMM-01 의 501번째 shot → L261123-IMM-01-03 (로드맵 P2 영향 범위용)"""
    seq = (max(shot_no, 1) - 1) // lot_size + 1
    return f"L{day:%y%m%d}-{equipment_id}-{seq:02d}"


def build_payload(m: Machine, values: dict, defect: str | None, serial_no: str | None = None,
                  now: datetime | None = None, lot_day: date | None = None,
                  lot_size: int = LOT_SIZE) -> dict:
    """결측이 없는 완전한 페이로드. 결측은 missing.apply_missing 이 나중에 섞는다."""
    now = now or datetime.now(KST)
    return {
        "messageId": str(uuid.uuid4()),          # 중복 수신 시 멱등 처리용 (유일한 추적 키)
        "lineId": m.line_id,
        "equipmentId": m.equipment_id,
        "shotNo": m.shot_no,
        "serialNo": serial_no,                   # 공정 중 임시값(TMP-…)·null·중복 가능, 확정은 출하 때 (ADR-017)
        "lotNo": lot_no(m.equipment_id, m.shot_no, lot_day or now.date(), lot_size),
        "ts": now.isoformat(timespec="milliseconds"),
        "sensors": {k: {"value": v, "unit": SENSORS[k][1]} for k, v in values.items()},
        "inspection": {"result": "NG" if defect else "OK", "defectType": defect},
    }


def missing_fields(payload: dict) -> list[str]:
    """정답 기록용: 이 메시지에서 비었거나 빠진 항목 (센서는 sensors.<키>)."""
    out = [f"sensors.{k}" for k in SENSORS
           if k not in (payload.get("sensors") or {}) or payload["sensors"][k].get("value") is None]
    if payload.get("inspection") is None:
        out.append("inspection")
    if payload.get("shotNo") is None:
        out.append("shotNo")
    if payload.get("serialNo") is None:
        out.append("serialNo")
    return out



def archive_ground_truth(gt_path: Path) -> Path | None:
    """기존 정답 파일(과 메타)을 ground_truth_archive/ 로 옮긴다. 옮긴 경로를 돌려준다 (없으면 None)."""
    if not gt_path.exists() or gt_path.stat().st_size == 0:
        return None
    stamp = datetime.fromtimestamp(gt_path.stat().st_mtime).strftime("%Y%m%d-%H%M%S")
    archive_dir = gt_path.parent / "ground_truth_archive"
    archive_dir.mkdir(parents=True, exist_ok=True)
    target = archive_dir / f"{gt_path.stem}_{stamp}{gt_path.suffix}"
    shutil.move(str(gt_path), target)
    meta = meta_path(gt_path)
    if meta.exists():
        shutil.move(str(meta), archive_dir / f"{gt_path.stem}_{stamp}.meta.json")
    print(f"이전 정답 파일 보관: {target}", flush=True)
    return target


def meta_path(gt_path: Path) -> Path:
    return gt_path.with_name(gt_path.stem + ".meta.json")


def write_ground_truth_meta(gt_path: Path, args, sim: dict, shots: int, faults) -> None:
    """정답 파일을 만든 실행 조건(설정 파일 해시·seed·shot 수·고장 시나리오)을 옆에 남긴다."""
    config_bytes = Path(args.config).read_bytes()
    meta = {
        "createdAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "config": str(Path(args.config).resolve().name),
        "configSha256": hashlib.sha256(config_bytes).hexdigest(),
        "seed": sim.get("seed"),
        "shotsPerEquipment": shots,
        "equipment": sim.get("equipment"),
        "faults": [{"name": f.name, "equipment": f.equipment_id, "startShot": f.start_shot, "duration": f.duration,
                    **({"severity": f.severity} if f.severity != 1.0 else {})}
                   for f in faults],
    }
    meta_path(gt_path).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

def main() -> None:
    '''
    QualiGuard 사출성형 센서 시뮬레이터를 터미널에서 실행 옵션을 읽을 수 있도록
    ArgumentParser 클래스로 정의한다.
    '''
    ap = argparse.ArgumentParser(description="QualiGuard 사출성형 센서 시뮬레이터")
    ap.add_argument("--config", default=str(Path(__file__).resolve().parent.parent / "scenarios.yaml"))
    ap.add_argument("--dry-run", action="store_true", help="MQTT로 보내지 않고 화면에 출력")
    ap.add_argument("--shots", type=int, help="설정의 shots 값을 덮어쓰기 (설비당 shot 수)")
    ap.add_argument("--interval", type=float, help="설정의 interval_sec 값을 덮어쓰기")
    ap.add_argument("--seed", type=int, help="설정의 seed 값을 덮어쓰기 (탐지 평가에서 seed 여러 개로 반복)")
    ap.add_argument("--out", help="MQTT 대신 이 폴더에 telemetry.jsonl(보낸 순서 그대로)과 ground_truth.csv 를 쓴다. "
                                  "대기 없이 바로 끝나고, 시각·messageId 도 seed 로 정해져 다시 돌려도 같은 파일이 나온다 (로드맵 E2)")
    ap.add_argument("--env-file", default=str(Path(__file__).resolve().parent.parent.parent / ".env"),
                    help="장비 비밀번호를 읽을 .env (기본: 프로젝트 루트)")
    args = ap.parse_args()

    # 1. 설정 파일 읽기
    #    scenarios.yaml 파일을 불러와서 딕셔너리 형태로 만든다.
    cfg = load_config(Path(args.config))
    mqtt_cfg = cfg.get("mqtt", {})
    sim = cfg["simulation"]
    if args.seed is not None:
        sim["seed"] = args.seed
    out_dir = Path(args.out).resolve() if args.out else None
    if out_dir and args.dry_run:
        sys.exit("--out 과 --dry-run 은 함께 쓸 수 없습니다")
    # 2. 기본값 우선순위 적용
    #    args.shots가 있으면 그 값을 쓰고 없으면 sim["shots"]를 쓴다.
    shots = args.shots or sim["shots"]
    interval = args.interval if args.interval is not None else sim["interval_sec"]
    if out_dir:
        interval = 0
    rng = random.Random(sim.get("seed"))
    # 결측은 공정 모델과 다른 난수를 써서, 확률 0 이면 예전과 같은 공정 값이 나오게 한다
    missing = MissingConfig.from_dict(cfg.get("missing"))
    miss_rng = random.Random(f'{sim.get("seed")}-missing')

    # 설비 목록을 순회하면서 각 설비마다 Machine 객체를 초기화
    machines = [Machine(sim["line_id"], eq, random.Random(rng.random())) for eq in sim["equipment"]]
    serials = {m.equipment_id: SerialIssuer(m.line_id, m.equipment_id) for m in machines}
    outages = {m.equipment_id: OutageTracker() for m in machines}
    delivery = Delivery(missing, miss_rng)
    # 설정 파일에 적힌 고장 시나리오들을 읽어서, 각각을 Fault 객체로 만든다.
    faults = [Fault(f["name"], f["equipment"], f["start_shot"], f["duration"], float(f.get("severity", 1.0)))
              for f in cfg.get("faults", [])]
    for f in faults:
        if f.name not in FAULT_CATALOG:
            sys.exit(f"알 수 없는 시나리오: {f.name}")

    # 정답 기록 (에이전트·이상 탐지 평가용, MQTT로는 보내지 않음)
    # 시뮬레이션 설정에서 정답 CSV 파일 경로를 읽고, 없으면 기본 경로를 쓴다.
    gt_path = Path(sim.get("ground_truth_csv", "../data/ground_truth.csv"))
    if not gt_path.is_absolute():
        gt_path = (Path(args.config).resolve().parent / gt_path).resolve()
    telemetry_file = None
    if out_dir:
        # 파일 출력: 평가 폴더 안에 정답을 따로 둔다 (data/ground_truth.csv 를 건드리지 않음)
        out_dir.mkdir(parents=True, exist_ok=True)
        gt_path = out_dir / "ground_truth.csv"
        telemetry_file = (out_dir / "telemetry.jsonl").open("w", encoding="utf-8")
    if args.dry_run:
        # dry-run 은 화면 확인용이라 실제 발행 때 만든 정답 파일을 덮어쓰지 않는다
        gt_file = open(os.devnull, "w", newline="", encoding="utf-8")
    else:
        gt_path.parent.mkdir(parents=True, exist_ok=True)
        # 이전 정답 파일을 덮어쓰지 않고 보관한다 (평가 결과가 어떤 정답으로 채점됐는지 잃지 않게, 로드맵 E1)
        if not out_dir:   # 평가 폴더는 seed 로 다시 만들 수 있으니 덮어쓴다
            archive_ground_truth(gt_path)
        write_ground_truth_meta(gt_path, args, sim, shots, faults)
        gt_file = gt_path.open("w", newline="", encoding="utf-8")
    gt = csv.writer(gt_file)
    # shotNo·inspection 은 실제 값(보낸 메시지에서 빠졌어도 기록), missingFields 는 보낼 때 비운 항목
    # delivery: sent / dropped(통째 누락) / delayed(늦게 보냄) 에 +resent(같은 messageId 한 번 더)
    gt.writerow(["ts", "equipmentId", "shotNo", "activeFault", "inspection", "defectType",
                 "messageId", "serialNo", "missingFields", "delivery"])

    clients: dict = {}      # 설비 ID → MQTT 클라이언트. 장비마다 자기 계정으로 따로 접속한다 (N-07, N-08)
    shutting_down = False   # 우리가 일부러 끊는 중인지 (정상 종료 메시지 구분용)
    pending = []            # 브로커 확인(PUBACK)을 아직 못 받은 QoS 1 메시지
    if not args.dry_run and not out_dir:
        import paho.mqtt.client as mqtt

        def on_connect(c, userdata, flags, reason_code, properties):
            print(f"[MQTT] {userdata} 연결: {reason_code}", flush=True)

        def on_disconnect(c, userdata, flags, reason_code, properties):
            if shutting_down:
                print(f"[MQTT] {userdata} 정상 종료", flush=True)
            else:
                print(f"[MQTT] {userdata} 연결 끊김: {reason_code} — 자동 재연결 시도", flush=True)

        env = load_dotenv(Path(args.env_file))
        for m in machines:
            # 사용자명 = 설비 ID. 브로커 ACL 이 이 계정에 factory/<라인>/<설비 ID>/telemetry 쓰기만 허용한다
            c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                            client_id=f'{mqtt_cfg.get("client_id", "qg-simulator")}-{m.equipment_id}',
                            protocol=mqtt.MQTTv5)
            c.user_data_set(m.equipment_id)
            pw = device_password(env, m.equipment_id)
            if pw:
                c.username_pw_set(m.equipment_id, pw)
            else:
                print(f"[MQTT] {m.equipment_id}: .env 에 장비 비밀번호가 없어 익명으로 접속합니다 "
                      f"(인증을 켠 브로커는 거부 → ./scripts/mqtt_users.sh)", flush=True)
            c.on_connect = on_connect
            c.on_disconnect = on_disconnect
            c.reconnect_delay_set(min_delay=1, max_delay=30)
            port = mqtt_cfg.get("port", 1883)
            ca = tls_ca_path(env)
            if ca:   # TLS (로드맵 Q5): scripts/mqtt_tls.sh 가 만든 CA 만 믿고 8883 으로
                c.tls_set(ca_certs=str(ca))
                port = mqtt_cfg.get("tls_port", 8883)
            c.connect(mqtt_cfg.get("host", "localhost"), port, keepalive=30)
            c.loop_start()
            clients[m.equipment_id] = c

    stop = False

    def handle_sigint(*_):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGINT, handle_sigint)

    # MQTT 발행 옵션을 준비하고, 시뮬레이터 시작 정보를 출력한다.
    qos = mqtt_cfg.get("qos", 1)
    topic_tpl = mqtt_cfg.get("topic", "factory/{line}/{equipment}/telemetry")
    sent = ng = 0
    print(f"시작: 설비 {len(machines)}대 × {shots} shot, 간격 {interval}s, 고장 시나리오 {len(faults)}개"
          f"{' (dry-run)' if args.dry_run else ''}", flush=True)
    if missing.any_active():
        print("결측 섞기: " + ", ".join(f"{k}={v:g}" for k, v in vars(missing).items()
                                     if v and k.endswith("_rate")), flush=True)
    missed = published = 0

    # 파일 출력은 가상 시계(고정 시작 시각 + 30초 사이클)와 seed 로 만든 messageId 를 써서 재현 가능하게 한다
    clock_start = datetime(2026, 1, 1, 8, 0, tzinfo=KST)
    id_rng = random.Random(f'{sim.get("seed")}-ids')

    def clock(step: int) -> datetime:
        return clock_start + timedelta(seconds=30 * step) if out_dir else datetime.now(KST)

    # 로트는 실행 시작일 기준으로 lot_size shot 마다 바뀐다 (자정을 넘겨도 같은 로트는 같은 번호)
    lot_day = clock(0).date()
    lot_size = int(sim.get("lot_size", LOT_SIZE))

    def publish(equipment_id: str, body: str) -> None:
        nonlocal pending, published
        published += 1
        if telemetry_file:
            telemetry_file.write(body + "\n")
        elif clients:
            pending.append(clients[equipment_id].publish(
                topic_tpl.format(line=sim["line_id"], equipment=equipment_id), body, qos=qos))
            if len(pending) > 1000:
                pending = [i for i in pending if not i.is_published()]
        else:
            print(topic_tpl.format(line=sim["line_id"], equipment=equipment_id), body)

    for step in range(shots):
        if stop:
            break
        for m in machines:
            add: dict = {}
            scale: dict = {}
            active = []
            for f in faults:
                if f.equipment_id == m.equipment_id and f.active(m.shot_no + 1):
                    a, s = effects_for(f, m.shot_no + 1, rng)
                    for k, v in a.items():
                        if k == "_sensorOnlyKeys":
                            add[k] = add.get(k, []) + v
                        elif k == "_defectP":
                            merged = dict(add.get(k, {}))
                            for d, q in v.items():
                                merged[d] = merged.get(d, 0.0) + q
                            add[k] = merged
                        else:
                            add[k] = add.get(k, 0.0) + v
                    for k, v in s.items():
                        scale[k] = scale.get(k, 1.0) * v
                    active.append(f.name)
            values, defect = m.next_shot(add, scale)
            now = clock(step)
            payload = build_payload(m, values, defect, serial_for(serials[m.equipment_id], missing, miss_rng, now), now,
                                    lot_day, lot_size)
            if out_dir:
                payload["messageId"] = str(uuid.UUID(int=id_rng.getrandbits(128), version=4))
            apply_missing(payload, missing, miss_rng,
                          outages[m.equipment_id].step(list(SENSORS), missing, miss_rng))
            gaps = missing_fields(payload)
            missed += any(g != "serialNo" for g in gaps)
            how = delivery.plan(step, m.equipment_id, json.dumps(payload, ensure_ascii=False))
            gt.writerow([payload["ts"], m.equipment_id, m.shot_no, "|".join(active) or "NONE",
                         "NG" if defect else "OK", defect or "",
                         payload["messageId"], payload["serialNo"] or "", "|".join(gaps), how])
            sent += 1
            ng += defect is not None
        for eq, body in delivery.due(step):
            publish(eq, body)
        if sent and sent % (len(machines) * 50) == 0:
            print(f"  진행: shot {sent}건, 전송 {published}건, 불량 {ng}건 ({ng / sent:.1%})", flush=True)
            gt_file.flush()
        if interval > 0:
            time.sleep(interval)

    for eq, body in delivery.due(None):       # 늦춰 둔 메시지를 마저 보낸다
        publish(eq, body)
    gt_file.close()
    if telemetry_file:
        telemetry_file.close()
    if clients:
        # 끊기 전에 아직 브로커 확인을 못 받은 메시지를 최대 10초까지 기다린다 (유실 방지)
        deadline = time.time() + 10
        for info in pending:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            try:
                info.wait_for_publish(timeout=remaining)
            except (RuntimeError, ValueError):
                pass
        unacked = sum(1 for i in pending if not i.is_published())
        if unacked:
            print(f"[MQTT] 경고: {unacked}건은 브로커 확인을 받지 못했습니다", flush=True)
        shutting_down = True
        for c in clients.values():
            c.disconnect()
            c.loop_stop()
    print(f"종료: shot {sent}건, 불량 {ng}건 ({(ng / sent if sent else 0):.1%}), 결측 있는 메시지 {missed}건, "
          f"센서 끊김 구간 {sum(o.started for o in outages.values())}개", flush=True)
    print(f"      전송 {published}건 = shot {sent} - 통째 누락 {delivery.dropped} + 재전송 {delivery.resent}, "
          f"늦게 보냄 {delivery.delayed}건. "
          f"정답 기록: {'(dry-run 이라 쓰지 않음)' if args.dry_run else gt_path}", flush=True)
    if out_dir:
        print(f"      메시지 기록: {out_dir / 'telemetry.jsonl'}", flush=True)


if __name__ == "__main__":
    main()
