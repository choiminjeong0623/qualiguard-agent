-- =====================================================================
-- 에이전트 운영 리포트 (로드맵 O3). 읽기 전용.
--   ./scripts/ops_report.sh                       최근 7일, 모든 실행
--   ./scripts/ops_report.sh 30 eval-2026-11-24    최근 30일, 평가 실행 태그 하나만
--   단가: INPUT_USD_PER_M / OUTPUT_USD_PER_M 환경 변수 (100만 토큰당 달러, 모델 요금표에서 확인해 넣는다)
--
-- sqlcmd 변수: $(Days) 최근 며칠, $(RunTag) 실행 태그(ALL 이면 전체, LIVE 면 태그 없는 실제 운영만),
--              $(InUsd) $(OutUsd) 100만 토큰당 입력·출력 단가 (생각 토큰은 출력 단가로 계산)
--
-- 1) 모델·프롬프트 버전별 요약: 분석 수, 실패율, 지연 P50·P95, 분석당 토큰·비용
-- 2) 검증 되돌림: 되돌림율, 되돌린 뒤 통과율
-- 3) 사건 단계별 시간 P50·P95 (agent_incident_event, 앱을 재시작해도 남는 기록)
-- 4) 결정별 분포
-- =====================================================================
SET NOCOUNT ON;
USE QUALIGUARD;

DECLARE @from DATETIMEOFFSET(3) = DATEADD(DAY, -$(Days), SYSDATETIMEOFFSET());
DECLARE @tag VARCHAR(60) = '$(RunTag)';
DECLARE @inUsd DECIMAL(10, 4) = $(InUsd);
DECLARE @outUsd DECIMAL(10, 4) = $(OutUsd);

IF OBJECT_ID('tempdb..#a') IS NOT NULL DROP TABLE #a;
SELECT a.analysis_id, a.incident_id, a.decision, a.latency_ms, a.attempts, a.verify_status,
       ISNULL(a.model, '-') AS model, ISNULL(a.prompt_version, '-') AS prompt_version,
       CAST(ISNULL(a.prompt_tokens, 0) AS BIGINT) AS in_tok,
       CAST(ISNULL(a.output_tokens, 0) AS BIGINT) + ISNULL(a.thought_tokens, 0) AS out_tok
INTO #a
FROM dbo.agent_analysis AS a
WHERE a.created_at >= @from
  AND (@tag = 'ALL' OR (@tag = 'LIVE' AND a.run_tag IS NULL) OR a.run_tag = @tag);

PRINT N'== 1) 모델·프롬프트 버전별 요약 (최근 $(Days)일, 태그 $(RunTag), 단가 입력 $' + CAST(@inUsd AS VARCHAR(20))
    + N' · 출력 $' + CAST(@outUsd AS VARCHAR(20)) + N' / 100만 토큰) ==';
SELECT s.model, s.prompt_version, s.analyses, s.failed,
       CAST(100.0 * s.failed / NULLIF(s.analyses, 0) AS DECIMAL(5, 1)) AS failed_pct,
       p.p50_ms, p.p95_ms, s.avg_in_tok, s.avg_out_tok,
       CAST((s.avg_in_tok * @inUsd + s.avg_out_tok * @outUsd) / 1000000.0 AS DECIMAL(10, 5)) AS usd_per_analysis,
       CAST((s.sum_in * @inUsd + s.sum_out * @outUsd) / 1000000.0 AS DECIMAL(10, 4)) AS usd_total
FROM (
    SELECT model, prompt_version, COUNT(*) AS analyses, SUM(CASE WHEN decision = 'FAILED' THEN 1 ELSE 0 END) AS failed,
           AVG(in_tok) AS avg_in_tok, AVG(out_tok) AS avg_out_tok, SUM(in_tok) AS sum_in, SUM(out_tok) AS sum_out
    FROM #a GROUP BY model, prompt_version
) AS s
LEFT JOIN (
    SELECT DISTINCT model, prompt_version,
           CAST(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms) OVER (PARTITION BY model, prompt_version) AS INT) AS p50_ms,
           CAST(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms) OVER (PARTITION BY model, prompt_version) AS INT) AS p95_ms
    FROM #a WHERE decision <> 'FAILED' AND latency_ms IS NOT NULL
) AS p ON p.model = s.model AND p.prompt_version = s.prompt_version
ORDER BY s.analyses DESC;

PRINT N'== 2) 검증 되돌림 (실패 제외) ==';
SELECT COUNT(*) AS analyses,
       SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS reworked,
       CAST(100.0 * SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0) AS DECIMAL(5, 1)) AS rework_pct,
       SUM(CASE WHEN attempts > 1 AND verify_status = 'PASS' THEN 1 ELSE 0 END) AS reworked_then_pass,
       CAST(100.0 * SUM(CASE WHEN attempts > 1 AND verify_status = 'PASS' THEN 1 ELSE 0 END)
            / NULLIF(SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END), 0) AS DECIMAL(5, 1)) AS pass_after_rework_pct,
       SUM(CASE WHEN verify_status = 'PASS' THEN 1 ELSE 0 END) AS verify_pass,
       SUM(CASE WHEN verify_status = 'WEAK' THEN 1 ELSE 0 END) AS verify_weak
FROM #a WHERE decision <> 'FAILED';

PRINT N'== 3) 사건 단계별 시간 (다음 상태 기록까지 걸린 시간) ==';
IF OBJECT_ID('tempdb..#s') IS NOT NULL DROP TABLE #s;
SELECT e.state AS stage,
       DATEDIFF(MILLISECOND, e.created_at,
                LEAD(e.created_at) OVER (PARTITION BY e.incident_id ORDER BY e.event_id)) AS ms
INTO #s
FROM dbo.agent_incident_event AS e
JOIN dbo.agent_incident AS i ON i.incident_id = e.incident_id
WHERE i.created_at >= @from
  AND (@tag = 'ALL' OR (@tag = 'LIVE' AND i.run_tag IS NULL) OR i.run_tag = @tag);
SELECT DISTINCT stage, COUNT(*) OVER (PARTITION BY stage) AS n,
       CAST(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY ms) OVER (PARTITION BY stage) AS INT) AS p50_ms,
       CAST(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY ms) OVER (PARTITION BY stage) AS INT) AS p95_ms
FROM #s WHERE ms IS NOT NULL
ORDER BY stage;

PRINT N'== 4) 결정별 분포 ==';
SELECT decision, COUNT(*) AS n, CAST(100.0 * COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0) AS DECIMAL(5, 1)) AS pct
FROM #a GROUP BY decision ORDER BY n DESC;
