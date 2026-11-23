# 09. 빈 DB 에서 처음 띄우면 DB 초기화가 04 에서 멈춤

- 발생일: 2026-11-23
- 영향: 저장소를 처음 받아 `docker compose up -d` 하면 `qg-mssql-init` 이 `Exited (1)`, 05~19 미실행 → 에이전트 표(`agent_analysis` 등) 없음
- 상태: **조치 완료**
- 관련: 로드맵 F1

## 현상
빈 DB(`docker compose down -v` 뒤 `up -d`)에서 DB 초기화 컨테이너 `qg-mssql-init` 이 `Exited (1)` 로 끝났다.

```
실행: /init/04_procedures.sql
Msg 207, Level 16, State 1, Procedure usp_FindSimilarNcr, Line 27
Invalid column name 'cause_code'.
```

## 원인
- `mssql-init` 은 `infra/mssql/init/*.sql` 을 **파일 이름 순서대로** 실행하고, 하나라도 실패하면 멈춘다(`sqlcmd -b ... || exit 1`).
- `04_procedures.sql` 의 `usp_FindSimilarNcr` 가 `ncr.cause_code` 를 읽는데, 이 칸은 **뒤 파일** `05_agent.sql` 이 추가한다.
- SQL Server 는 프로시저를 만들 때 없는 **표**는 나중으로 미루지만(지연 이름 확인), **이미 있는 표의 없는 칸**은 바로 오류로 본다.
- 개발 DB 는 파일을 하나씩 추가하며 키워 와서 칸이 이미 있었기 때문에 이 순서 문제가 드러나지 않았다.

## 조치
`02_schema.sql` 의 `ncr` 표 정의에 `cause_code VARCHAR(30) NULL` 을 넣었다.

- `05_agent.sql` 의 `IF COL_LENGTH(N'dbo.ncr', N'cause_code') IS NULL` 추가 문은 그대로 둔다. 새 DB 에서는 건너뛰고,
  02 를 고치기 전에 만든 DB 에서는 지금처럼 칸을 추가한다.
- `02_schema.sql` 의 `CREATE TABLE dbo.ncr` 는 `IF OBJECT_ID(...) IS NULL` 안에 있어 기존 DB 에는 영향이 없다.

## 확인
- 빈 SQL Server 2025 컨테이너에 01~19 를 이름 순서로 실행: **수정 전 04 에서 Msg 207, 수정 후 전부 성공**
- 같은 스크립트를 한 번 더 실행해도 오류 없음 (여러 번 실행 안전)
- `usp_FindSimilarNcr 'IMM-01', 'barrelTemp'` 가 과거 사례(NCR-2025-0012, `HEATER_DEGRADATION`)를 돌려줌
- 맥: `docker compose down -v && docker compose up -d` → `docker compose ps -a` 에서 `mssql-init` `Exited (0)`,
  `docker logs qg-mssql-init` 마지막 `실행:` 줄이 맨 마지막 SQL 파일

```bash
docker compose down -v                     # ⚠️ DB·Kafka·MQTT 데이터 전부 삭제
rm -rf qms-app/build/kafka-streams-state   # Kafka 를 새로 만들면 예전 Streams 상태도 지운다
docker compose up -d
docker compose ps -a                       # mssql-init, kafka-init: Exited (0)
docker logs qg-mssql-init 2>&1 | grep "실행:" | tail -1
```

## 재발 방지
- 새 SQL 파일은 **앞 번호 파일에 있는 표·칸만** 참조한다. 칸을 추가하면 그 칸을 읽는 프로시저보다 앞 번호 파일에 둔다.
- 개발 DB 만으로는 순서 문제가 보이지 않는다. 스키마를 바꾼 PR 은 빈 DB 로 한 번 띄워 본다.
  (후속 후보: CI 에서 SQL Server 컨테이너에 init 전체를 빈 DB 로 실행하는 단계)
