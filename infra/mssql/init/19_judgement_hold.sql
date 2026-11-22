-- 판단 보류 (로드맵 F2)
--
-- 에이전트가 끝내 결론을 내지 못한 분석을 사람 검토로 넘긴다.
--   PENDING  : 사람 검토 대기 (QMS "에이전트 조치" 화면의 판단 보류 목록)
--   RESOLVED : 검토자가 확인하고 처리 메모를 남김
--   NULL     : 보류 대상이 아님
-- 보류 사유(hold_reason)는 JudgementHoldPolicy 가 정한다.
--   RECHECK_LIMIT : 새 경보 없는 재확인을 상한(recheck-max)까지 했는데도 관찰(OBSERVE)
--   VERIFY_WEAK   : 검증 에이전트 지적이 재분석 뒤에도 남았는데 부적합 초안 없이 경보가 종결됨
-- 여러 번 실행해도 안전하다.
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_analysis', N'hold_status') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_status VARCHAR(10) NULL
        CONSTRAINT ck_agent_analysis_hold_status CHECK (hold_status IS NULL OR hold_status IN ('PENDING', 'RESOLVED'));
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'hold_reason') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_reason VARCHAR(20) NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'hold_detail') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_detail NVARCHAR(500) NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'hold_resolved_by') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_resolved_by NVARCHAR(50) NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'hold_resolved_at') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_resolved_at DATETIMEOFFSET(3) NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'hold_note') IS NULL
    ALTER TABLE dbo.agent_analysis ADD hold_note NVARCHAR(1000) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_analysis_hold' AND object_id = OBJECT_ID(N'dbo.agent_analysis'))
    CREATE INDEX ix_agent_analysis_hold ON dbo.agent_analysis (hold_status) WHERE hold_status IS NOT NULL;
GO
