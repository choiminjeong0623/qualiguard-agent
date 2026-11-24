-- =====================================================================
-- 조치 승인 대기 묶기·근거 표시 (F3 맥 실행 후속, 2026-11-24)
--   같은 설비·같은 조치가 이미 승인 대기면 새 행을 만들지 않고 그 행에 "같은 판단 N번 더"로 묶는다.
--   승인자가 판단할 근거(확신도·검증 통과)를 행에 남긴다.
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_action', N'confidence') IS NULL
    ALTER TABLE dbo.agent_action ADD confidence TINYINT NULL;          -- 조치를 제안한 분석의 확신도 (묶이면 마지막 분석 값)
GO
IF COL_LENGTH(N'dbo.agent_action', N'verified') IS NULL
    ALTER TABLE dbo.agent_action ADD verified BIT NULL;                -- 검증 에이전트 통과 여부
GO
IF COL_LENGTH(N'dbo.agent_action', N'repeat_count') IS NULL
    ALTER TABLE dbo.agent_action ADD repeat_count INT NOT NULL
        CONSTRAINT df_agent_action_repeat DEFAULT (0);                  -- 승인 대기 중 같은 판단이 더 온 횟수
GO
IF COL_LENGTH(N'dbo.agent_action', N'last_analysis_id') IS NULL
    ALTER TABLE dbo.agent_action ADD last_analysis_id INT NULL;        -- 마지막으로 묶인 분석
GO
IF COL_LENGTH(N'dbo.agent_action', N'last_seen_at') IS NULL
    ALTER TABLE dbo.agent_action ADD last_seen_at DATETIMEOFFSET(3) NULL;
GO
-- 같은 설비·조치의 승인 대기 찾기
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_action_equipment_code' AND object_id = OBJECT_ID(N'dbo.agent_action'))
    CREATE INDEX ix_agent_action_equipment_code ON dbo.agent_action (equipment_id, action_code, status);
GO
