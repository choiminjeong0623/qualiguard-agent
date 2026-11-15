#!/usr/bin/env bash
# MQTT 계정 파일(infra/mosquitto/passwd) 만들기 (N-07, ADR-015)
#
#   ./scripts/mqtt_users.sh              # .env 에 비밀번호가 없으면 무작위로 만들어 .env 에 추가한 뒤 계정 파일 생성
#   ./scripts/mqtt_users.sh IMM-01 IMM-02 IMM-03   # 장비 목록을 직접 지정 (기본: IMM-01 IMM-02)
#
# - 계정: 브리지(MQTT_BRIDGE_USERNAME, 기본 qg-bridge) + 장비마다 하나 (사용자명 = 설비 ID)
# - 비밀번호는 .env 에만 있다: MQTT_BRIDGE_PASSWORD, MQTT_DEVICE_PASSWORD_IMM_01 처럼 설비 ID 의 '-' 를 '_' 로
# - 계정 파일에는 해시만 들어가지만, 그래도 커밋하지 않는다 (.gitignore)
# - 바꾼 뒤에는 브로커를 다시 띄운다: docker compose restart mosquitto
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=.env
PASSWD=infra/mosquitto/passwd
[ -f "$ENV_FILE" ] || { echo ".env 가 없습니다. cp .env.example .env 후 다시 실행하세요." >&2; exit 1; }

devices=("$@")
[ ${#devices[@]} -gt 0 ] || devices=(IMM-01 IMM-02)

env_get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }
env_ensure() {   # 값이 비어 있으면 무작위 비밀번호를 만들어 .env 에 넣는다. 값은 화면에 찍지 않는다.
  local key=$1 val
  val=$(env_get "$key")
  if [ -z "$val" ]; then
    val=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)
    if grep -qE "^$key=" "$ENV_FILE"; then
      local tmp; tmp=$(mktemp)
      awk -v k="$key" -v v="$val" -F= 'BEGIN{OFS="="} $1==k{print k, v; next} {print}' "$ENV_FILE" >"$tmp" && cat "$tmp" >"$ENV_FILE" && rm -f "$tmp"
    else
      printf '%s=%s\n' "$key" "$val" >>"$ENV_FILE"
    fi
    echo "  .env 에 $key 를 새로 만들었습니다" >&2
  fi
  printf '%s' "$val"
}

# mosquitto_passwd: 로컬에 있으면 쓰고, 없으면 브로커와 같은 이미지로 실행한다
if command -v mosquitto_passwd >/dev/null 2>&1; then
  mp() { mosquitto_passwd "$@"; }
  target=$PASSWD
else
  mp() { docker run --rm -v "$PWD/infra/mosquitto:/work" eclipse-mosquitto:2 mosquitto_passwd "$@"; }
  target=/work/passwd
fi

bridge_user=$(env_get MQTT_BRIDGE_USERNAME); bridge_user=${bridge_user:-qg-bridge}
# 계정 파일 없이 docker compose up 을 먼저 하면 Docker 가 같은 이름의 빈 폴더를 만든다 → 지우고 파일로 만든다
[ -d "$PASSWD" ] && rmdir "$PASSWD"
rm -f "$PASSWD"; touch "$PASSWD"; chmod 600 "$PASSWD"
mp -b "$target" "$bridge_user" "$(env_ensure MQTT_BRIDGE_PASSWORD)"
for d in "${devices[@]}"; do
  mp -b "$target" "$d" "$(env_ensure "MQTT_DEVICE_PASSWORD_${d//-/_}")"
done
chmod 644 "$PASSWD"   # 컨테이너 안 mosquitto 사용자가 읽을 수 있게 (내용은 해시)
echo "계정 파일: $PASSWD (브리지 $bridge_user, 장비 ${devices[*]})"
echo "적용: docker compose restart mosquitto"
