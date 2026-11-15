-- =====================================================================
-- 에이전트 분석 토큰 기록 — 분석 시간이 어디서 드는지 보기 위함 (입력·출력·생각 토큰)
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_analysis', N'prompt_tokens') IS NULL
    ALTER TABLE dbo.agent_analysis ADD prompt_tokens INT NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'output_tokens') IS NULL
    ALTER TABLE dbo.agent_analysis ADD output_tokens INT NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'thought_tokens') IS NULL
    ALTER TABLE dbo.agent_analysis ADD thought_tokens INT NULL;
GO
