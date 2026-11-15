-- =====================================================================
-- 역할 분리형 멀티 에이전트 (ADR-016) — 검증 결과·재분석 횟수, 사건 상태·단계 기록
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

-- 역할 분리 파이프라인 (ADR-016 2단계): 사건 번호와 분류·작성 결과·단계 기록
IF COL_LENGTH(N'dbo.agent_analysis', N'incident_id') IS NULL
    ALTER TABLE dbo.agent_analysis ADD incident_id VARCHAR(36) NULL;
GO
IF COL_LENGTH(N'dbo.agent_analysis', N'pipeline_json') IS NULL
    ALTER TABLE dbo.agent_analysis ADD pipeline_json NVARCHAR(MAX) NULL; -- {"triage":{...},"report":{...},"timeline":[...]}
GO

-- 사건 상태 (오케스트레이터가 단계마다 갱신). 진행 중인 사건을 화면·쿼리로 볼 수 있게
IF OBJECT_ID(N'dbo.agent_incident', N'U') IS NULL
CREATE TABLE dbo.agent_incident (
    incident_id    VARCHAR(36)       NOT NULL CONSTRAINT pk_agent_incident PRIMARY KEY,
    equipment_id   VARCHAR(20)       NOT NULL,
    trigger_type   VARCHAR(10)       NOT NULL,              -- ALARM / RECHECK
    seed_anomaly_id VARCHAR(36)      NULL,
    run_tag        VARCHAR(60)       NULL,
    severity       VARCHAR(10)       NULL,                  -- HIGH / MEDIUM / LOW (분류 에이전트)
    incident_type  VARCHAR(30)       NULL,                  -- QUALITY_IMPACT / PROCESS_SHIFT / VARIATION / SPIKE
    state          VARCHAR(20)       NOT NULL,              -- TRIAGED → ANALYZING → VERIFYING → (REWORK →) WRITING → AWAITING_APPROVAL / CLOSED / FAILED
    attempts       INT               NOT NULL CONSTRAINT df_agent_incident_attempts DEFAULT (0),
    analysis_id    INT               NULL,
    ncr_no         VARCHAR(20)       NULL,
    created_at     DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_incident_created DEFAULT (SYSDATETIMEOFFSET()),
    updated_at     DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_incident_updated DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF OBJECT_ID(N'dbo.agent_incident_event', N'U') IS NULL
CREATE TABLE dbo.agent_incident_event (
    event_id     BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_agent_incident_event PRIMARY KEY,
    incident_id  VARCHAR(36)       NOT NULL,
    topic        VARCHAR(60)       NOT NULL,                -- 받은 Kafka 토픽
    state        VARCHAR(20)       NOT NULL,                -- 처리 뒤 상태
    detail       NVARCHAR(1000)    NULL,
    created_at   DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_incident_event_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_incident_event_incident')
    CREATE INDEX ix_agent_incident_event_incident ON dbo.agent_incident_event (incident_id, event_id);
GO
