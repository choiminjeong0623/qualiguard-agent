# QualiGuard Agent

설비 센서 데이터(MQTT) → Kafka 전처리·이상 탐지 → LLM 에이전트의 부적합(NCR) 초안 작성 → QMS 화면 승인 → MES/ERP 연계까지 이어지는 **품질 부적합 대응 에이전트** 포트폴리오입니다.

## 구성 (작업 중)

| 폴더 | 내용 | 상태 |
|---|---|---|
| `infra/` | Mosquitto 설정, SQL Server 초기화 스크립트 | 준비됨 |
| `simulator/` | 공개 제조 데이터셋을 MQTT로 재생하는 Python 센서 시뮬레이터 | 예정 |
| `qms-app/` | Spring Boot 4 — MQTT→Kafka 브리지, QMS 화면(jQuery·Kendo UI), Spring AI 에이전트(MCP 도구) | 예정 |
| `anomaly-service/` | Python FastAPI — 이상 탐지(SPC 규칙 + Isolation Forest) | 예정 |
| `docs/` | 아키텍처, 설계 결정 기록 | 예정 |

## 로컬 실행

```bash
cp .env.example .env          # 값 채우기
docker compose up -d          # Mosquitto, Kafka(+토픽 생성), SQL Server(+DB 생성)
docker compose ps
docker compose --profile tools up -d kafka-ui   # 필요할 때만: http://localhost:8090
```

| 서비스 | 접속 주소 |
|---|---|
| MQTT | `localhost:1883` |
| Kafka | `localhost:9092` |
| SQL Server | `localhost:1433` (DB: `QUALIGUARD`, 사용자: `sa`) |
| Kafka UI | `http://localhost:8090` (tools 프로필) |

## Kafka 토픽

| 토픽 | 용도 |
|---|---|
| `sensor.raw` | 브리지가 MQTT에서 받아 표준화한 원본 센서 값 |
| `sensor.clean` | 전처리(결측·이상치 처리, 윈도우 집계)를 거친 값 |
| `quality.anomaly` | 이상 탐지 결과 — 에이전트 실행 트리거 |
| `quality.ncr.approved` | 품질 담당자가 승인한 부적합 — MES/ERP 연계 |
| `quality.dlq` | 처리 실패 메시지 보관(재처리용) |

## 개발 환경 메모

- Apple Silicon(M1)에서는 SQL Server 이미지를 Rosetta로 실행합니다 (`orb config set rosetta true`).
- 8GB RAM을 고려해 컨테이너별 메모리 상한을 두었고, Spring·Python 앱은 개발 중에 Mac에서 직접 실행합니다.
