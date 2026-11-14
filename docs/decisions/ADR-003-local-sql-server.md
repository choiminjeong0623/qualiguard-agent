# ADR-003. SQL Server를 로컬 컨테이너로 운영한다

- 상태: 채택
- 날짜: 2026-11-14

## 배경
채용 공고의 필수 요건이 MS SQL(쿼리·프로시저)이라 SQL Server를 써야 합니다. 그런데 개발 PC가 **MacBook M1, RAM 8GB**입니다.
- SQL Server 공식 이미지는 Intel(x86)용만 있어 M1에서는 Rosetta 에뮬레이션으로 실행해야 합니다.
- macOS 26에서 Rosetta로 SQL Server 2025 컨테이너를 실행할 때 문제가 있었다는 보고가 있었습니다.
- 전체 구성 요소를 띄우면 8GB가 빠듯할 것으로 예상했습니다.

## 고려한 선택지
| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 로컬 컨테이너 (SQL Server 2025)** | 외부 의존 없음, `docker compose` 한 번으로 재현 | 에뮬레이션 동작 여부 불확실, 메모리 부담 |
| B. 로컬 컨테이너 (SQL Server 2022) | 에뮬레이션 안정성 보고가 많음 | 벡터 타입 미지원 |
| C. Azure SQL Database 무료 제공 | Rosetta·메모리 문제 없음 | 외부 계정 필요, 오프라인 개발 불가 |

## 결정
**추측하지 않고 먼저 시험했습니다.** M1 + macOS 26.6 + OrbStack(Rosetta 사용)에서 SQL Server 2025 컨테이너를 실행해,
- `SQL Server is now ready for client connections` 확인
- 대기 상태 메모리 **713MB** 측정

을 거쳐 **A안(로컬 SQL Server 2025)**으로 확정했습니다.

## 근거
- 실제로 동작했고, 메모리도 예상(2GB)보다 훨씬 적게 썼습니다.
- SQL Server 2025는 벡터 타입을 지원해, 과거 부적합 사례 검색(지식저장소)을 별도 벡터 DB 없이 같은 DB에서 처리할 수 있습니다.
- 포트폴리오를 보는 사람 대부분은 Intel/AMD PC라 같은 `docker-compose.yml`로 바로 실행할 수 있습니다.

## 결과
- 컨테이너에 `platform: linux/amd64`, 메모리 상한 2.5GB(엔진 내부 2GB)를 지정했습니다.
- 실패 시 대안(2022 이미지 → Azure SQL) 순서를 미리 정해 두었습니다.
