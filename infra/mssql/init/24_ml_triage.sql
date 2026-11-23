-- =====================================================================
-- ML 먼저, 애매하면 LLM (실험 기능) — ML 판단 기록
--   사건마다 ML 이 낸 원인·확률·낯선 정도와, LLM 을 건너뛰었는지(route), LLM 이 낸 원인(있을 때)을 남긴다.
--   shadow(기록만)·표본 점검(audit) 사건은 둘 다 있어서 일치율을 셀 수 있다 (GET /api/agent/ml-triage/stats).
--   최종 원인·사람 확정 원인은 agent_analysis.incident_id → ncr 로 이어 본다.
-- route
--   SHADOW         : shadow 모드, LLM 이 분석 (ML 은 기록만)
--   SKIPPED        : LLM 을 건너뛰고 ML 결과로 진행
--   AUDIT          : 건너뛸 수 있었지만 표본 점검으로 LLM 이 분석
--   LLM_NOISE      : ML 이 단발성이라 LLM 으로
--   LLM_LOW        : 확률이 기준보다 낮아 LLM 으로
--   LLM_UNFAMILIAR : 학습 경보들과 많이 달라(낯선 경보) LLM 으로
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.agent_ml_judgment', N'U') IS NULL
CREATE TABLE dbo.agent_ml_judgment (
    incident_id     VARCHAR(36)       NOT NULL CONSTRAINT pk_agent_ml_judgment PRIMARY KEY,
    equipment_id    VARCHAR(20)       NOT NULL,
    alarm_shot      BIGINT            NULL,
    mode            VARCHAR(10)       NOT NULL,   -- SHADOW / SKIP
    route           VARCHAR(20)       NOT NULL,
    ml_cause        VARCHAR(40)       NOT NULL,
    ml_probability  DECIMAL(5, 3)     NOT NULL,
    novelty         DECIMAL(9, 3)     NULL,       -- 고른 원인의 학습 경보들과의 거리
    novelty_limit   DECIMAL(9, 3)     NULL,       -- 이보다 멀면 낯선 경보
    would_skip      BIT               NOT NULL,   -- skip 모드였다면 LLM 을 건너뛰었을까 (확률·원인·낯섦 기준)
    llm_cause       VARCHAR(40)       NULL,       -- LLM 이 분석했을 때 첫 시도의 원인
    threshold       DECIMAL(4, 3)     NOT NULL,
    created_at      DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_ml_judgment_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_agent_ml_judgment_created')
    CREATE INDEX ix_agent_ml_judgment_created ON dbo.agent_ml_judgment (created_at);
GO

SELECT name FROM sys.tables WHERE name = N'agent_ml_judgment';
GO
