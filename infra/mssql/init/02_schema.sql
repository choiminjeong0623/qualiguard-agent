-- =====================================================================
-- QualiGuard 스키마 — 여러 번 실행해도 안전하다 (없을 때만 생성)
-- =====================================================================
USE QUALIGUARD;
GO

-- ── 설비 마스터 ─────────────────────────────────────────────────────
IF OBJECT_ID(N'dbo.equipment', N'U') IS NULL
CREATE TABLE dbo.equipment (
    equipment_id    VARCHAR(20)    NOT NULL CONSTRAINT pk_equipment PRIMARY KEY,
    line_id         VARCHAR(20)    NOT NULL,
    equipment_name  NVARCHAR(100)  NOT NULL,
    model           NVARCHAR(100)  NULL,
    product_name    NVARCHAR(100)  NULL,          -- 생산 품목
    install_date    DATE           NULL,
    status          VARCHAR(20)    NOT NULL CONSTRAINT df_equipment_status DEFAULT ('RUNNING')
);
GO

-- ── 검사 규격 (설계 기준) ──────────────────────────────────────────
-- 규격 한계(LSL/USL)는 "제품이 합격하려면 지켜야 하는 범위"로, 고객·설계가 정한다.
-- SPC 관리 한계(μ±3σ)는 "공정이 평소처럼 돌고 있는지"를 보는 범위로, 데이터에서 계산한다. 둘은 다르다.
IF OBJECT_ID(N'dbo.sensor_spec', N'U') IS NULL
CREATE TABLE dbo.sensor_spec (
    equipment_id  VARCHAR(20)    NOT NULL CONSTRAINT fk_sensor_spec_equipment REFERENCES dbo.equipment (equipment_id),
    sensor_key    VARCHAR(30)    NOT NULL,
    sensor_name   NVARCHAR(50)   NOT NULL,
    unit          NVARCHAR(10)   NOT NULL,
    lsl           DECIMAL(9, 3)  NOT NULL,        -- 규격 하한
    target        DECIMAL(9, 3)  NOT NULL,        -- 목표값
    usl           DECIMAL(9, 3)  NOT NULL,        -- 규격 상한
    CONSTRAINT pk_sensor_spec PRIMARY KEY (equipment_id, sensor_key),
    CONSTRAINT ck_sensor_spec_range CHECK (lsl < target AND target < usl)
);
GO

-- ── shot 단위 공정 데이터 (sensor.clean 적재) ─────────────────────
IF OBJECT_ID(N'dbo.process_shot', N'U') IS NULL
CREATE TABLE dbo.process_shot (
    shot_id             BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_process_shot PRIMARY KEY,
    message_id          VARCHAR(36)       NOT NULL,
    line_id             VARCHAR(20)       NOT NULL,
    equipment_id        VARCHAR(20)       NOT NULL CONSTRAINT fk_process_shot_equipment REFERENCES dbo.equipment (equipment_id),
    shot_no             BIGINT            NOT NULL,
    shot_ts             DATETIMEOFFSET(3) NOT NULL,
    mold_temp           DECIMAL(9, 3)     NULL,
    barrel_temp         DECIMAL(9, 3)     NULL,
    injection_pressure  DECIMAL(9, 3)     NULL,
    holding_pressure    DECIMAL(9, 3)     NULL,
    cushion             DECIMAL(9, 3)     NULL,
    cycle_time          DECIMAL(9, 3)     NULL,
    motor_current       DECIMAL(9, 3)     NULL,
    inspection_result   CHAR(2)           NOT NULL CONSTRAINT ck_process_shot_result CHECK (inspection_result IN ('OK', 'NG')),
    defect_type         VARCHAR(20)       NULL,
    invalid_sensors     VARCHAR(200)      NULL,   -- 측정 범위를 벗어나 제외된 센서 (쉼표 구분)
    created_at          DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_process_shot_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
-- 같은 메시지는 한 번만 저장 (중복 방어의 마지막 단계)
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ux_process_shot_message' AND object_id = OBJECT_ID(N'dbo.process_shot'))
    CREATE UNIQUE INDEX ux_process_shot_message ON dbo.process_shot (message_id);
GO
-- 설비별 시간 범위 조회용 (추이·불량률 프로시저)
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_equipment_ts' AND object_id = OBJECT_ID(N'dbo.process_shot'))
    CREATE INDEX ix_process_shot_equipment_ts ON dbo.process_shot (equipment_id, shot_ts)
        INCLUDE (inspection_result, defect_type);
GO

-- ── 이상 이력 (quality.anomaly 적재) ──────────────────────────────
IF OBJECT_ID(N'dbo.anomaly_event', N'U') IS NULL
CREATE TABLE dbo.anomaly_event (
    anomaly_id      VARCHAR(36)       NOT NULL CONSTRAINT pk_anomaly_event PRIMARY KEY,
    line_id         VARCHAR(20)       NOT NULL,
    equipment_id    VARCHAR(20)       NOT NULL CONSTRAINT fk_anomaly_equipment REFERENCES dbo.equipment (equipment_id),
    shot_no         BIGINT            NOT NULL,
    detected_at     DATETIMEOFFSET(3) NOT NULL,
    rule_code       VARCHAR(20)       NOT NULL,   -- INDIVIDUAL, XBAR, S_CHART, P_CHART
    rule_name       NVARCHAR(100)     NOT NULL,
    sensor_key      VARCHAR(30)       NOT NULL,   -- 불량률 경보는 'inspection'
    sensor_name     NVARCHAR(50)      NOT NULL,
    observed        DECIMAL(12, 4)    NOT NULL,
    baseline_mean   DECIMAL(12, 4)    NULL,
    baseline_sigma  DECIMAL(12, 4)    NULL,
    lower_limit     DECIMAL(12, 4)    NULL,
    upper_limit     DECIMAL(12, 4)    NULL,
    recent_ng_rate  DECIMAL(6, 4)     NULL,
    message         NVARCHAR(500)     NOT NULL,
    evidence_json   NVARCHAR(MAX)     NOT NULL CONSTRAINT ck_anomaly_evidence_json CHECK (ISJSON(evidence_json) = 1),
    -- NEW → ANALYZING(에이전트 분석 중) → NCR_DRAFTED(초안 작성) / DISMISSED(관찰·종결)
    status          VARCHAR(20)       NOT NULL CONSTRAINT df_anomaly_status DEFAULT ('NEW')
                    CONSTRAINT ck_anomaly_status CHECK (status IN ('NEW', 'ANALYZING', 'NCR_DRAFTED', 'DISMISSED')),
    created_at      DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_anomaly_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_anomaly_equipment_detected' AND object_id = OBJECT_ID(N'dbo.anomaly_event'))
    CREATE INDEX ix_anomaly_equipment_detected ON dbo.anomaly_event (equipment_id, detected_at DESC);
GO

-- ── 설비 정비 이력 ─────────────────────────────────────────────────
IF OBJECT_ID(N'dbo.maintenance_history', N'U') IS NULL
CREATE TABLE dbo.maintenance_history (
    maint_id      INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_maintenance_history PRIMARY KEY,
    equipment_id  VARCHAR(20)    NOT NULL CONSTRAINT fk_maintenance_equipment REFERENCES dbo.equipment (equipment_id),
    maint_date    DATE           NOT NULL,
    maint_type    NVARCHAR(20)   NOT NULL,        -- 예방정비, 고장수리, 부품교체, 교정
    part_name     NVARCHAR(100)  NULL,
    description   NVARCHAR(500)  NOT NULL,
    technician    NVARCHAR(50)   NULL
);
GO

-- ── 부적합 보고서 (NCR) — 과거 사례 + 에이전트 초안 + 승인 결과 ─────
IF OBJECT_ID(N'dbo.ncr', N'U') IS NULL
CREATE TABLE dbo.ncr (
    ncr_id           INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_ncr PRIMARY KEY,
    ncr_no           VARCHAR(20)       NOT NULL CONSTRAINT uq_ncr_no UNIQUE,
    equipment_id     VARCHAR(20)       NOT NULL CONSTRAINT fk_ncr_equipment REFERENCES dbo.equipment (equipment_id),
    anomaly_id       VARCHAR(36)       NULL CONSTRAINT fk_ncr_anomaly REFERENCES dbo.anomaly_event (anomaly_id),
    defect_type      VARCHAR(20)       NULL,
    related_sensors  VARCHAR(200)      NULL,      -- 유사 사례 검색용 (쉼표 구분 센서 키)
    title            NVARCHAR(200)     NOT NULL,
    phenomenon       NVARCHAR(1000)    NOT NULL,  -- 현상
    suspected_cause  NVARCHAR(1000)    NULL,      -- 추정 원인 (초안)
    root_cause       NVARCHAR(1000)    NULL,      -- 확정 원인
    evidence         NVARCHAR(MAX)     NULL,      -- 근거
    action_taken     NVARCHAR(1000)    NULL,      -- 조치 내용
    cause_code       VARCHAR(30)       NULL,      -- 원인 코드(HEATER_DEGRADATION 등). 04의 usp_FindSimilarNcr가 읽으므로 여기서 만든다.(F1) [2026.11.23 추가]
    status           VARCHAR(20)       NOT NULL CONSTRAINT df_ncr_status DEFAULT ('DRAFT')
                     CONSTRAINT ck_ncr_status CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED', 'CLOSED')),
    created_by       NVARCHAR(50)      NOT NULL,  -- 'AGENT' 또는 작성자
    approved_by      NVARCHAR(50)      NULL,
    approved_at      DATETIMEOFFSET(3) NULL,
    occurred_at      DATETIMEOFFSET(3) NOT NULL,
    created_at       DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_ncr_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_ncr_equipment_status' AND object_id = OBJECT_ID(N'dbo.ncr'))
    CREATE INDEX ix_ncr_equipment_status ON dbo.ncr (equipment_id, status);
GO

SELECT name AS created_table FROM sys.tables ORDER BY name;
GO
