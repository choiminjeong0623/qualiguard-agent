#!/bin/bash
# 에이전트 운영 리포트 (로드맵 O3). 읽기 전용 쿼리만 실행한다.
#   ./scripts/ops_report.sh [최근 며칠=7] [실행 태그=ALL|LIVE|<run_tag>]
#   INPUT_USD_PER_M=0.30 OUTPUT_USD_PER_M=2.50 ./scripts/ops_report.sh 30 LIVE
# 단가 기본값은 예시다. 쓰는 모델의 요금표에서 100만 토큰당 입력·출력 단가를 확인해 넣는다 (생각 토큰은 출력 단가).
set -euo pipefail
cd "$(dirname "$0")/.."
DAYS=${1:-7}
TAG=${2:-ALL}
[[ "$DAYS" =~ ^[0-9]+$ ]] || { echo "며칠은 숫자로: $DAYS" >&2; exit 2; }
[[ "$TAG" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "태그에 쓸 수 없는 문자: $TAG" >&2; exit 2; }
IN=${INPUT_USD_PER_M:-0.30}
OUT=${OUTPUT_USD_PER_M:-2.50}
PW=$(grep -E '^MSSQL_SA_PASSWORD=' .env | head -1 | cut -d= -f2-)
docker cp scripts/ops_report.sql qg-mssql:/tmp/ops_report.sql >/dev/null
docker exec qg-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$PW" -C -I -b -W -s " | " \
  -v Days="$DAYS" RunTag="$TAG" InUsd="$IN" OutUsd="$OUT" -i /tmp/ops_report.sql
