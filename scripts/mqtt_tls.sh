#!/usr/bin/env bash
# MQTT TLS(8883) 켜기 (로드맵 Q5). 개발용 자체 CA 와 브로커 인증서를 만들고, Mosquitto 에 TLS 리스너를 추가한다.
#
#   ./scripts/mqtt_tls.sh          # 인증서가 없을 때만 만든다 (있으면 설정만 다시 씀)
#   ./scripts/mqtt_tls.sh --renew  # 인증서를 새로 만든다
#
# 만든 뒤: docker compose up -d mosquitto (재시작) → ./scripts/test_mqtt_acl.sh 로 TLS 항목 확인
# 앱·시뮬레이터가 TLS 로 붙게 하려면 .env 에 (README "MQTT TLS" 참고):
#   QG_MQTT_URL=ssl://localhost:8883
#   MQTT_TLS_CA=infra/mosquitto/certs/ca.crt      (저장소 루트 기준 경로)
# 1883(평문)은 그대로 열어 둔다. 실제 현장이라면 8883 만 열고 1883 은 닫는다.
# 개인 키·인증서는 커밋하지 않는다 (.gitignore). CA 개인 키(ca.key)는 다른 곳에 보관하는 것이 원칙이다.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=${CERT_DIR:-infra/mosquitto/certs}          # 시험에서 다른 폴더로 만들 때만 바꾼다
CONF=${TLS_CONF:-infra/mosquitto/conf.d/tls.conf}
DAYS=${DAYS:-825}
mkdir -p "$DIR" "$(dirname "$CONF")"

if [ "${1:-}" = "--renew" ] || [ ! -f "$DIR/server.crt" ]; then
  echo "인증서 만들기 ($DIR)"
  openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -subj "/CN=QualiGuard Dev CA" \
    -keyout "$DIR/ca.key" -out "$DIR/ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
    -keyout "$DIR/server.key" -out "$DIR/server.csr" 2>/dev/null
  printf 'subjectAltName=DNS:localhost,DNS:mosquitto,DNS:qg-mosquitto,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > "$DIR/server.ext"
  openssl x509 -req -in "$DIR/server.csr" -CA "$DIR/ca.crt" -CAkey "$DIR/ca.key" -CAcreateserial \
    -days "$DAYS" -sha256 -extfile "$DIR/server.ext" -out "$DIR/server.crt" 2>/dev/null
  rm -f "$DIR/server.csr" "$DIR/server.ext" "$DIR/ca.srl"
  chmod 644 "$DIR/ca.crt" "$DIR/server.crt"
  chmod 644 "$DIR/server.key"   # 컨테이너의 mosquitto 사용자가 읽어야 한다 (개발용)
  chmod 600 "$DIR/ca.key"
else
  echo "인증서가 이미 있습니다 ($DIR/server.crt). 새로 만들려면 --renew"
fi

cat > "$CONF" <<'CONFEOF'
# scripts/mqtt_tls.sh 가 만든 TLS 리스너 (로드맵 Q5). 인증·ACL 은 mosquitto.conf 의 전역 설정을 그대로 쓴다.
listener 8883
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
tls_version tlsv1.2
CONFEOF
echo "설정: $CONF"
openssl x509 -in "$DIR/server.crt" -noout -subject -enddate
echo "다음: docker compose up -d --force-recreate mosquitto"
