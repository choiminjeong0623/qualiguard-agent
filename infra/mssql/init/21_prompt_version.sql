-- 프롬프트 버전 기록 (로드맵 O1)
-- 분석마다 어떤 시스템 프롬프트로 판단했는지 남겨, 평가 결과와 짝지어 비교할 수 있게 한다.
--   prompt_version : "파일 이름@본문 해시 8자리" (예: analysis-v1@3f2a9c1d)
--   이 열이 생기기 전의 분석은 NULL 로 남는다. 여러 번 실행해도 안전하다.
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_analysis', N'prompt_version') IS NULL
    ALTER TABLE dbo.agent_analysis ADD prompt_version VARCHAR(60) NULL;
GO
