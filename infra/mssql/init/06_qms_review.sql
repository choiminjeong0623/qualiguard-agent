-- =====================================================================
-- QMS 검토 단계 — 부적합 초안 검토·수정·승인/반려 (F-08)
-- 여러 번 실행해도 안전하다.
--
-- 상태 흐름: DRAFT ─(수정)→ DRAFT ─(승인)→ APPROVED
--                             └─(반려)→ REJECTED
-- 쓰기는 모두 저장 프로시저로 한다: 상태 확인·변경·이력 기록을 한 트랜잭션에서 처리하고,
-- 상태가 맞지 않으면 THROW 로 거절한다 (화면·API 가 상태 규칙을 따로 구현하지 않게).
-- =====================================================================
USE QUALIGUARD;
GO

-- ── ncr 검토 정보 ────────────────────────────────────────────────────
IF COL_LENGTH(N'dbo.ncr', N'reviewed_by') IS NULL
    ALTER TABLE dbo.ncr ADD reviewed_by NVARCHAR(50) NULL;           -- 마지막으로 승인·반려한 사람
GO
IF COL_LENGTH(N'dbo.ncr', N'reviewed_at') IS NULL
    ALTER TABLE dbo.ncr ADD reviewed_at DATETIMEOFFSET(3) NULL;
GO
IF COL_LENGTH(N'dbo.ncr', N'review_comment') IS NULL
    ALTER TABLE dbo.ncr ADD review_comment NVARCHAR(1000) NULL;      -- 승인 의견 또는 반려 사유
GO
IF COL_LENGTH(N'dbo.ncr', N'updated_at') IS NULL
    ALTER TABLE dbo.ncr ADD updated_at DATETIMEOFFSET(3) NULL;
GO

-- ── 검토 이력 (누가 언제 무엇을 바꿨는지 — AI 초안을 사람이 어떻게 고쳤는지 남김) ──
IF OBJECT_ID(N'dbo.ncr_history', N'U') IS NULL
CREATE TABLE dbo.ncr_history (
    history_id   INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_ncr_history PRIMARY KEY,
    ncr_id       INT               NOT NULL CONSTRAINT fk_ncr_history_ncr REFERENCES dbo.ncr (ncr_id),
    action       VARCHAR(20)       NOT NULL CONSTRAINT ck_ncr_history_action
                 CHECK (action IN ('EDIT', 'APPROVE', 'REJECT')),
    actor        NVARCHAR(50)      NOT NULL,
    from_status  VARCHAR(20)       NOT NULL,
    to_status    VARCHAR(20)       NOT NULL,
    changes_json NVARCHAR(MAX)     NULL,          -- 바뀐 항목: {"필드": {"before": ..., "after": ...}}
    comment      NVARCHAR(1000)    NULL,
    created_at   DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_ncr_history_created DEFAULT (SYSDATETIMEOFFSET())
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_ncr_history_ncr')
    CREATE INDEX ix_ncr_history_ncr ON dbo.ncr_history (ncr_id, created_at);
GO

-- ---------------------------------------------------------------------
-- usp_ListNcr: 부적합 목록 (상태·설비로 거르기, 최근 순)
--    예) EXEC dbo.usp_ListNcr 'DRAFT', NULL, 50;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ListNcr
    @Status      VARCHAR(20) = NULL,
    @EquipmentId VARCHAR(20) = NULL,
    @Top         INT         = 100
AS
BEGIN
    SET NOCOUNT ON;
    SELECT TOP (@Top)
           n.ncr_no, n.equipment_id, n.status, n.title, n.cause_code, n.confidence, n.defect_type,
           n.created_by, n.reviewed_by,
           CONVERT(VARCHAR(33), n.occurred_at, 127) AS occurred_at,
           CONVERT(VARCHAR(33), n.created_at, 127)  AS created_at,
           CONVERT(VARCHAR(33), n.reviewed_at, 127) AS reviewed_at,
           (SELECT COUNT(*) FROM dbo.ncr_anomaly AS x WHERE x.ncr_id = n.ncr_id) AS anomaly_count
    FROM dbo.ncr AS n
    WHERE (@Status IS NULL OR n.status = @Status)
      AND (@EquipmentId IS NULL OR n.equipment_id = @EquipmentId)
    ORDER BY n.created_at DESC, n.ncr_id DESC;
END;
GO

-- ---------------------------------------------------------------------
-- usp_UpdateNcrDraft: 초안 수정 (DRAFT 일 때만). NULL 인 인자는 바꾸지 않는다.
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_UpdateNcrDraft
    @NcrNo             VARCHAR(20),
    @Actor             NVARCHAR(50),
    @Title             NVARCHAR(200)  = NULL,
    @Phenomenon        NVARCHAR(1000) = NULL,
    @SuspectedCause    NVARCHAR(1000) = NULL,
    @RootCause         NVARCHAR(1000) = NULL,
    @Evidence          NVARCHAR(MAX)  = NULL,
    @RecommendedAction NVARCHAR(1000) = NULL,
    @CauseCode         VARCHAR(30)    = NULL,
    @DefectType        VARCHAR(20)    = NULL,
    @RelatedSensors    VARCHAR(200)   = NULL,
    @ChangesJson       NVARCHAR(MAX)  = NULL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRANSACTION;
        DECLARE @id INT, @status VARCHAR(20);
        SELECT @id = ncr_id, @status = status FROM dbo.ncr WITH (UPDLOCK, HOLDLOCK) WHERE ncr_no = @NcrNo;
        IF @id IS NULL
            THROW 50404, N'부적합을 찾을 수 없습니다.', 1;
        IF @status <> 'DRAFT'
            THROW 50409, N'초안(DRAFT) 상태에서만 수정할 수 있습니다.', 1;

        UPDATE dbo.ncr SET
            title              = COALESCE(@Title, title),
            phenomenon         = COALESCE(@Phenomenon, phenomenon),
            suspected_cause    = COALESCE(@SuspectedCause, suspected_cause),
            root_cause         = COALESCE(@RootCause, root_cause),
            evidence           = COALESCE(@Evidence, evidence),
            recommended_action = COALESCE(@RecommendedAction, recommended_action),
            cause_code         = COALESCE(@CauseCode, cause_code),
            defect_type        = COALESCE(@DefectType, defect_type),
            related_sensors    = COALESCE(@RelatedSensors, related_sensors),
            updated_at         = SYSDATETIMEOFFSET()
        WHERE ncr_id = @id;

        INSERT INTO dbo.ncr_history (ncr_id, action, actor, from_status, to_status, changes_json)
        VALUES (@id, 'EDIT', @Actor, 'DRAFT', 'DRAFT', @ChangesJson);
    COMMIT;
END;
GO

-- ---------------------------------------------------------------------
-- usp_ReviewNcr: 승인 또는 반려 (DRAFT 일 때만)
--   승인: 확정 원인(root_cause)과 원인 코드가 있어야 한다 → 다음 분석의 유사 사례가 된다 (F-10)
--   반려: 사유가 있어야 한다
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ReviewNcr
    @NcrNo     VARCHAR(20),
    @Decision  VARCHAR(10),            -- APPROVE / REJECT
    @Actor     NVARCHAR(50),
    @Comment   NVARCHAR(1000) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    IF @Decision NOT IN ('APPROVE', 'REJECT')
        THROW 50400, N'검토 결정은 APPROVE 또는 REJECT 입니다.', 1;
    IF @Decision = 'REJECT' AND NULLIF(LTRIM(RTRIM(@Comment)), N'') IS NULL
        THROW 50400, N'반려 사유를 입력하세요.', 1;

    BEGIN TRANSACTION;
        DECLARE @id INT, @status VARCHAR(20), @root NVARCHAR(1000), @cause VARCHAR(30);
        SELECT @id = ncr_id, @status = status, @root = root_cause, @cause = cause_code
        FROM dbo.ncr WITH (UPDLOCK, HOLDLOCK) WHERE ncr_no = @NcrNo;
        IF @id IS NULL
            THROW 50404, N'부적합을 찾을 수 없습니다.', 1;
        IF @status <> 'DRAFT'
            THROW 50409, N'초안(DRAFT) 상태에서만 승인·반려할 수 있습니다.', 1;
        IF @Decision = 'APPROVE' AND (NULLIF(LTRIM(RTRIM(@root)), N'') IS NULL OR @cause IS NULL OR @cause IN ('UNKNOWN', 'NOISE'))
            THROW 50400, N'승인하려면 확정 원인과 원인 코드(UNKNOWN·NOISE 제외)를 먼저 입력하세요.', 1;

        DECLARE @to VARCHAR(20) = CASE @Decision WHEN 'APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END;
        UPDATE dbo.ncr SET
            status         = @to,
            reviewed_by    = @Actor,
            reviewed_at    = SYSDATETIMEOFFSET(),
            review_comment = @Comment,
            approved_by    = CASE WHEN @to = 'APPROVED' THEN @Actor ELSE approved_by END,
            approved_at    = CASE WHEN @to = 'APPROVED' THEN SYSDATETIMEOFFSET() ELSE approved_at END,
            updated_at     = SYSDATETIMEOFFSET()
        WHERE ncr_id = @id;

        INSERT INTO dbo.ncr_history (ncr_id, action, actor, from_status, to_status, comment)
        VALUES (@id, @Decision, @Actor, 'DRAFT', @to, @Comment);

        -- 승인 결과를 MES/ERP 로 보낼 이벤트를 같은 트랜잭션에서 아웃박스에 쓴다 (F-09, 08_integration.sql)
        IF @to = 'APPROVED'
            EXEC dbo.usp_EnqueueNcrApproved @id;
    COMMIT;
END;
GO

SELECT name AS qms_review_object FROM sys.objects
WHERE name IN (N'ncr_history', N'usp_ListNcr', N'usp_UpdateNcrDraft', N'usp_ReviewNcr') ORDER BY name;
GO
