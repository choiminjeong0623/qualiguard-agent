-- =====================================================================
-- 에이전트 사건 재시작 복구 (로드맵 A2, ADR-016)
--   agent_incident.checkpoint_json : 진행 중 사건의 기록 (작업 정보·분류·시도 번호·분석/검증/작성 결과·단계 기록)
--   단계가 바뀌거나 에이전트가 결과를 쓸 때마다 갱신하고, 사건이 끝나면 비운다.
--   앱이 다시 켜지면 상태가 TRIAGED/ANALYZING/VERIFYING/WRITING 인 사건을 이 기록으로 되살려 이어 간다.
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.agent_incident', N'checkpoint_json') IS NULL
    ALTER TABLE dbo.agent_incident ADD checkpoint_json NVARCHAR(MAX) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_incident_open')
    CREATE INDEX ix_agent_incident_open ON dbo.agent_incident (state, created_at)
        WHERE state IN ('TRIAGED', 'ANALYZING', 'VERIFYING', 'WRITING');
GO

SELECT name FROM sys.columns WHERE object_id = OBJECT_ID(N'dbo.agent_incident') AND name = N'checkpoint_json';
GO
