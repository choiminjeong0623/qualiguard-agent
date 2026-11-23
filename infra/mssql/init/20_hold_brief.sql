-- 판단 보류 요약 (ADR-020 후속)
--
-- 검토자가 판단 보류 건을 빨리 처리하도록, 같은 사건의 분석 흐름(경보 → 재확인 …)을 LLM 이 3줄 요약과
-- 현장 확인 항목으로 정리해 둔다. 보류 여부를 정하는 것은 여전히 규칙(JudgementHoldPolicy)이고,
-- 요약은 보조 정보일 뿐이다. 처리(RESOLVED)는 사람이 한다.
--   hold_brief : {"summary":[...], "checks":[...], "source":"LLM|RULE", "model":"...", "createdAt":"..."}
-- 여러 번 실행해도 안전하다.
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_analysis', N'hold_brief') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_brief NVARCHAR(MAX) NULL;
GO
