-- =====================================================================
-- 위험도 기반 자동 조치와 조치 효과 검증 — 조치 기록
-- 여러 번 실행해도 안전하다. (16 번은 EAI 계획 docs/06 이 쓰기로 해서 건너뜀)
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.agent_action', N'U') IS NULL
CREATE TABLE dbo.agent_action (
    action_id        INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_agent_action PRIMARY KEY,
    analysis_id      INT               NOT NULL,              -- 이 조치를 제안한 분석
    equipment_id     VARCHAR(20)       NOT NULL,
    cause_code       VARCHAR(30)       NULL,
    action_code      VARCHAR(40)       NOT NULL,              -- ActionCatalog 코드
    risk             VARCHAR(10)       NOT NULL,              -- LOW / MEDIUM / HIGH
    mode             VARCHAR(10)       NOT NULL,              -- AUTO(에이전트 실행) / APPROVAL(사람 승인)
    reasons          NVARCHAR(500)     NULL,                  -- 안전장치가 그렇게 정한 이유
    -- 상태: WAITING_APPROVAL → EXECUTED → RESOLVED / NOT_RESOLVED(→ 재분석) / ESCALATED, 또는 REJECTED
    status           VARCHAR(20)       NOT NULL,
    attempt          INT               NOT NULL CONSTRAINT df_agent_action_attempt DEFAULT (1),  -- 같은 사건에서 몇 번째 조치
    parent_action_id INT               NULL,                  -- 효과가 없어 다시 분석하게 만든 앞 조치
    base_shot        BIGINT            NULL,                  -- 실행 시점 shot (효과 확인 구간의 시작)
    effect_detail    NVARCHAR(500)     NULL,
    decided_by       NVARCHAR(50)      NULL,                  -- AGENT 또는 승인·반려한 사람
    created_at       DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_action_created DEFAULT (SYSDATETIMEOFFSET()),
    executed_at      DATETIMEOFFSET(3) NULL,
    checked_at       DATETIMEOFFSET(3) NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_action_status')
    CREATE INDEX ix_agent_action_status ON dbo.agent_action (status, equipment_id);
GO