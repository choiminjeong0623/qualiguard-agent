#!/usr/bin/env bash
# MQTT 장비 인증·토픽 권한 시험 (N-07, N-08). 브로커가 떠 있어야 한다 (docker compose up -d mosquitto).
#
#   ./scripts/test_mqtt_acl.sh            # 결과: 항목마다 통과/실패, 하나라도 실패하면 종료 코드 1
#
# 비밀번호는 .env 에서 읽고 화면에 찍지 않는다. 시험 메시지는 shot 데이터 형식이 아니므로
# 앱(브리지)이 떠 있으면 형식 오류로 quality.dlq 에 몇 건 남을 수 있다.
set -uo pipefail
cd "$(dirname "$0")/.."
HOST=${MQTT_HOST:-localhost}; PORT=${MQTT_PORT:-1883}; LINE=${LINE:-LINE1}

env_get() { grep -E "^$1=" .env | tail -1 | cut -d= -f2-; }
BRIDGE_USER=$(env_get MQTT_BRIDGE_USERNAME); BRIDGE_USER=${BRIDGE_USER:-qg-bridge}
BRIDGE_PW=$(env_get MQTT_BRIDGE_PASSWORD)
D1=IMM-01; D1_PW=$(env_get MQTT_DEVICE_PASSWORD_IMM_01)
D2=IMM-02
[ -n "$BRIDGE_PW" ] && [ -n "$D1_PW" ] || { echo ".env 에 MQTT 비밀번호가 없습니다 → ./scripts/mqtt_users.sh" >&2; exit 2; }

# 로컬 mosquitto 클라이언트가 없으면 브로커 컨테이너 안의 것을 쓴다
if command -v mosquitto_pub >/dev/null 2>&1; then
  pub() { mosquitto_pub -h "$HOST" -p "$PORT" -V 5 "$@"; }
  sub() { mosquitto_sub -h "$HOST" -p "$PORT" -V 5 "$@"; }
else
  pub() { docker exec qg-mosquitto mosquitto_pub -h localhost -p 1883 -V 5 "$@"; }
  sub() { docker exec qg-mosquitto mosquitto_sub -h localhost -p 1883 -V 5 "$@"; }
fi

fail=0
check() { if [ "$2" = ok ]; then echo "  통과  $1"; else echo "  실패  $1"; fail=1; fi; }
RUN=$RANDOM
T1="factory/$LINE/$D1/telemetry"; T2="factory/$LINE/$D2/telemetry"

echo "[인증] N-07"
pub -i "acl-anon-$RUN" -t "$T1" -m x -q 1 >/dev/null 2>&1 && r=bad || r=ok
check "계정 없는 접속은 거부" $r
pub -i "acl-badpw-$RUN" -u "$D1" -P "wrong-$RUN" -t "$T1" -m x -q 1 >/dev/null 2>&1 && r=bad || r=ok
check "틀린 비밀번호는 거부" $r

echo "[토픽 권한] N-08"
out=$(mktemp)
# 브리지 계정으로 모든 설비 토픽을 6초 동안 구독하며 받은 메시지를 모은다
sub -i "acl-bridge-$RUN" -u "$BRIDGE_USER" -P "$BRIDGE_PW" -t 'factory/+/+/telemetry' -v -W 6 >"$out" 2>/dev/null &
spid=$!
sleep 1
pub -i "acl-d1-$RUN" -u "$D1" -P "$D1_PW" -t "$T1" -m "own-$RUN" -q 1 >/dev/null 2>&1
pub -i "acl-d1x-$RUN" -u "$D1" -P "$D1_PW" -t "$T2" -m "other-$RUN" -q 1 >/dev/null 2>&1
pub -i "acl-br-$RUN" -u "$BRIDGE_USER" -P "$BRIDGE_PW" -t "$T1" -m "bridgepub-$RUN" -q 1 >/dev/null 2>&1
# 장비 계정으로 구독을 시도하는 동안 자기 토픽에 한 건 더 보낸다 → 장비 계정 구독에는 오면 안 된다
dsub=$(mktemp)
sub -i "acl-d1sub-$RUN" -u "$D1" -P "$D1_PW" -t "$T1" -v -W 3 >"$dsub" 2>/dev/null &
dpid=$!
sleep 1
pub -i "acl-d1b-$RUN" -u "$D1" -P "$D1_PW" -t "$T1" -m "own2-$RUN" -q 1 >/dev/null 2>&1
wait $spid $dpid 2>/dev/null

grep -q "^$T1 own-$RUN$" "$out" && r=ok || r=bad
check "장비는 자기 설비 토픽에 발행 가능, 브리지가 받음" $r
grep -q "other-$RUN" "$out" && r=bad || r=ok
check "장비가 다른 설비 토픽에 발행하면 전달되지 않음" $r
grep -q "bridgepub-$RUN" "$out" && r=bad || r=ok
check "브리지 계정은 발행 불가 (읽기만)" $r
grep -q "own2-$RUN" "$dsub" && r=bad || r=ok
check "장비 계정은 구독 불가" $r
rm -f "$out" "$dsub"

[ $fail = 0 ] && echo "모두 통과" || echo "실패한 항목이 있습니다"
exit $fail
