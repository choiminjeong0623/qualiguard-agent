"""센서 시뮬레이터 실행 진입점.

예)
  uv run qg-simulator                       # scenarios.yaml 설정으로 Mosquitto에 발행
  uv run qg-simulator --dry-run --shots 5   # 브로커 없이 화면에 출력만
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import signal
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

from .faults import FAULT_CATALOG, Fault, effects_for
from .process import SENSORS, Machine

KST = ZoneInfo("Asia/Seoul")


def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def build_payload(m: Machine, values: dict, defect: str | None) -> dict:
    return {
        "messageId": str(uuid.uuid4()),          # 중복 수신 시 멱등 처리용
        "lineId": m.line_id,
        "equipmentId": m.equipment_id,
        "shotNo": m.shot_no,
        "ts": datetime.now(KST).isoformat(timespec="milliseconds"),
        "sensors": {k: {"value": v, "unit": SENSORS[k][1]} for k, v in values.items()},
        "inspection": {"result": "NG" if defect else "OK", "defectType": defect},
    }


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
    args = ap.parse_args()

    # 1. 설정 파일 읽기
    #    scenarios.yaml 파일을 불러와서 딕셔너리 형태로 만든다.
    cfg = load_config(Path(args.config))
    mqtt_cfg = cfg.get("mqtt", {})
    sim = cfg["simulation"]
    # 2. 기본값 우선순위 적용
    #    args.shots가 있으면 그 값을 쓰고 없으면 sim["shots"]를 쓴다.
    shots = args.shots or sim["shots"]
    interval = args.interval if args.interval is not None else sim["interval_sec"]
    rng = random.Random(sim.get("seed"))

    # 설비 목록을 순회하면서 각 설비마다 Machine 객체를 초기화
    machines = [Machine(sim["line_id"], eq, random.Random(rng.random())) for eq in sim["equipment"]]
    # 설정 파일에 적힌 고장 시나리오들을 읽어서, 각각을 Fault 객체로 만든다.
    faults = [Fault(f["name"], f["equipment"], f["start_shot"], f["duration"]) for f in cfg.get("faults", [])]
    for f in faults:
        if f.name not in FAULT_CATALOG:
            sys.exit(f"알 수 없는 시나리오: {f.name}")

    # 정답 기록 (에이전트·이상 탐지 평가용, MQTT로는 보내지 않음)
    # 시뮬레이션 설정에서 정답 CSV 파일 경로를 읽고, 없으면 기본 경로를 쓴다.
    gt_path = Path(sim.get("ground_truth_csv", "../data/ground_truth.csv"))
    if not gt_path.is_absolute():
        gt_path = (Path(args.config).resolve().parent / gt_path).resolve()
    gt_path.parent.mkdir(parents=True, exist_ok=True)
    gt_file = gt_path.open("w", newline="", encoding="utf-8")
    gt = csv.writer(gt_file)
    gt.writerow(["ts", "equipmentId", "shotNo", "activeFault", "inspection", "defectType"])

    client = None
    shutting_down = False   # 우리가 일부러 끊는 중인지 (정상 종료 메시지 구분용)
    pending = []            # 브로커 확인(PUBACK)을 아직 못 받은 QoS 1 메시지
    if not args.dry_run:
        import paho.mqtt.client as mqtt

        def on_connect(c, userdata, flags, reason_code, properties):
            print(f"[MQTT] 연결: {reason_code}", flush=True)

        def on_disconnect(c, userdata, flags, reason_code, properties):
            if shutting_down:
                print("[MQTT] 정상 종료", flush=True)
            else:
                print(f"[MQTT] 연결 끊김: {reason_code} — 자동 재연결 시도", flush=True)

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=mqtt_cfg.get("client_id", "qg-simulator"))
        client.on_connect = on_connect
        client.on_disconnect = on_disconnect
        client.reconnect_delay_set(min_delay=1, max_delay=30)
        client.connect(mqtt_cfg.get("host", "localhost"), mqtt_cfg.get("port", 1883), keepalive=30)
        client.loop_start()

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

    for _ in range(shots):
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
                        add[k] = add.get(k, []) + v if k == "_sensorOnlyKeys" else add.get(k, 0.0) + v
                    for k, v in s.items():
                        scale[k] = scale.get(k, 1.0) * v
                    active.append(f.name)
            values, defect = m.next_shot(add, scale)
            payload = build_payload(m, values, defect)
            topic = topic_tpl.format(line=m.line_id, equipment=m.equipment_id)
            body = json.dumps(payload, ensure_ascii=False)
            if client:
                pending.append(client.publish(topic, body, qos=qos))
                if len(pending) > 1000:
                    pending = [i for i in pending if not i.is_published()]
            else:
                print(topic, body)
            gt.writerow([payload["ts"], m.equipment_id, m.shot_no, "|".join(active) or "NONE",
                         payload["inspection"]["result"], defect or ""])
            sent += 1
            ng += defect is not None
        if sent and sent % (len(machines) * 50) == 0:
            print(f"  진행: {sent}건 발행, 불량 {ng}건 ({ng / sent:.1%})", flush=True)
            gt_file.flush()
        if interval > 0:
            time.sleep(interval)

    gt_file.close()
    if client:
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
        client.disconnect()
        client.loop_stop()
    print(f"종료: {sent}건 발행, 불량 {ng}건 ({(ng / sent if sent else 0):.1%}). 정답 기록: {gt_path}", flush=True)


if __name__ == "__main__":
    main()
