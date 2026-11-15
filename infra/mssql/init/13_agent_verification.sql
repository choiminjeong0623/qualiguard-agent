-- =====================================================================
-- 검증 에이전트 (ADR-016) — 분석 결과를 데이터와 대조한 결과와 재분석 횟수
-- 여러 번 실행해도 안전하다.
-- (11·12 번은 머지하지 않은 브랜치 feat/ncr-vector-search·feat/agent-token-usage 가 쓰고 있어 건너뜀)
-- =====================================================================
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_analysis', N'attempts') IS NULL
    ALTER TABLE dbo.agent_analysis ADD attempts INT NULL;              -- 분석 에이전트 호출 횟수 (1 + 검증 반려 재분석)
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'verify_status') IS NULL
    ALTER TABLE dbo.agent_analysis ADD verify_status VARCHAR(10) NULL; -- PASS(통과) / WEAK(재분석 뒤에도 근거 부족)
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'verify_json') IS NULL
    ALTER TABLE dbo.agent_analysis ADD verify_json NVARCHAR(MAX) NULL; -- {"status","fails":[...],"warns":[...]}
GO
