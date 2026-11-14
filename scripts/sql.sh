#!/bin/bash
# SQL Server 에 쿼리를 실행하는 도우미. 비밀번호는 프로젝트 루트의 .env 에서 읽는다.
#   ./scripts/sql.sh "EXEC dbo.usp_GetSpcSummary 'IMM-01', 50"
set -euo pipefail
cd "$(dirname "$0")/.."
PW=$(grep -E '^MSSQL_SA_PASSWORD=' .env | head -1 | cut -d= -f2-)
docker exec qg-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$PW" -C -I \
  -d QUALIGUARD -W -s " | " -Q "SET NOCOUNT ON; $1"
