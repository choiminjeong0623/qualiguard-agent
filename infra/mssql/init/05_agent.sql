-- =====================================================================
-- 에이전트 단계 스키마 — 분석 기록, 부적합-이상 연결, 초안 번호 채번
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

-- ── ncr 에 에이전트 결과 컬럼 추가 ─────────────────────────────────
IF COL_LENGTH(N'dbo.ncr', N'cause_code') IS NULL
    ALTER TABLE dbo.ncr ADD cause_code VARCHAR(30) NULL;          -- HEATER_DEGRADATION 등
GO
IF COL_LENGTH(N'dbo.ncr', N'confidence') IS NULL
    ALTER TABLE dbo.ncr ADD confidence TINYINT NULL;              -- 에이전트 확신도 0~100
GO
IF COL_LENGTH(N'dbo.ncr', N'recommended_action') IS NULL
    ALTER TABLE dbo.ncr ADD recommended_action NVARCHAR(1000) NULL; -- 권고 조치 (조치 내용과 구분)
GO
-- 과거 사례에도 원인 코드를 채워 둔다 (유사 사례 비교·평가용)
UPDATE dbo.ncr SET cause_code = 'HEATER_DEGRADATION' WHERE ncr_no = 'NCR-2025-0012' AND cause_code IS NULL;
UPDATE dbo.ncr SET cause_code = 'COOLING_ISSUE'      WHERE ncr_no = 'NCR-2025-0021' AND cause_code IS NULL;
UPDATE dbo.ncr SET cause_code = 'CHECK_RING_WEAR'    WHERE ncr_no = 'NCR-2024-0033' AND cause_code IS NULL;
UPDATE dbo.ncr SET cause_code = 'SENSOR_FAULT'       WHERE ncr_no = 'NCR-2026-0004' AND cause_code IS NULL;
UPDATE dbo.ncr SET cause_code = 'MATERIAL'           WHERE ncr_no = 'NCR-2025-0030' AND cause_code IS NULL;
GO

-- ── 부적합 ↔ 이상 연결 (한 부적합에 여러 경보가 묶일 수 있음) ───────
IF OBJECT_ID(N'dbo.ncr_anomaly', N'U') IS NULL
CREATE TABLE dbo.ncr_anomaly (
    ncr_id      INT          NOT NULL CONSTRAINT fk_ncr_anomaly_ncr REFERENCES dbo.ncr (ncr_id),
    anomaly_id  VARCHAR(36)  NOT NULL CONSTRAINT fk_ncr_anomaly_anomaly REFERENCES dbo.anomaly_event (anomaly_id),
    CONSTRAINT pk_ncr_anomaly PRIMARY KEY (ncr_id, anomaly_id)
);
GO

-- ── 에이전트 분석 기록 (판단 근거·도구 호출·소요 시간 — 감사와 평가용) ─
IF OBJECT_ID(N'dbo.agent_analysis', N'U') IS NULL
CREATE TABLE dbo.agent_analysis (
    analysis_id    INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_agent_analysis PRIMARY KEY,
    equipment_id   VARCHAR(20)       NOT NULL CONSTRAINT fk_agent_analysis_equipment REFERENCES dbo.equipment (equipment_id),
    seed_anomaly_id VARCHAR(36)      NOT NULL,       -- 분석을 시작한 경보
    anomaly_ids    VARCHAR(MAX)      NOT NULL,       -- 함께 묶인 경보 (쉼표 구분)
    decision       VARCHAR(20)       NOT NULL CONSTRAINT ck_agent_decision CHECK (decision IN ('NCR_DRAFT', 'OBSERVE', 'SENSOR_CHECK', 'FAILED')),
    cause_code     VARCHAR(30)       NULL,
    confidence     TINYINT           NULL,
    result_json    NVARCHAR(MAX)     NULL,
    tool_trace     NVARCHAR(MAX)     NULL,           -- 에이전트가 호출한 도구와 인자 (순서대로)
    model          VARCHAR(50)       NULL,
    latency_ms     INT               NULL,
    ncr_id         INT               NULL CONSTRAINT fk_agent_analysis_ncr REFERENCES dbo.ncr (ncr_id),
    error_message  NVARCHAR(1000)    NULL,
    created_at     DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_agent_analysis_created DEFAULT (SYSDATETIMEOFFSET())
);
GO

-- 시간 순 재생 평가에서 실행을 구분하는 표시 (운영 분석은 NULL)
IF COL_LENGTH(N'dbo.agent_analysis', N'run_tag') IS NULL
    ALTER TABLE dbo.agent_analysis ADD run_tag VARCHAR(60) NULL;
GO

-- ── 에이전트 초안 번호 (AI-연도-0001) ──────────────────────────────
IF OBJECT_ID(N'dbo.seq_ncr_draft', N'SO') IS NULL
    CREATE SEQUENCE dbo.seq_ncr_draft AS INT START WITH 1 INCREMENT BY 1;
GO

SELECT name AS agent_object FROM sys.objects
WHERE name IN (N'ncr_anomaly', N'agent_analysis', N'seq_ncr_draft') ORDER BY name;
GO
