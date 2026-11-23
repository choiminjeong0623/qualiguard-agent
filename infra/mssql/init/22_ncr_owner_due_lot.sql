-- =====================================================================
-- 부적합 필수 항목 보강 (로드맵 P2)
--   process_shot.lot_no      : 설비가 보내는 생산 로트 번호 (없으면 NULL)
--   ncr.affected_lots        : 영향 범위 — 첫 경보부터 분석 시점까지 생산된 로트 (에이전트가 채움)
--   ncr.action_owner         : 조치 담당자 (사람이 검토 화면에서 입력)
--   ncr.action_due_date      : 조치 기한 (사람이 검토 화면에서 입력)
-- 담당자·기한이 비어 있어도 승인은 막지 않는다. 필수 항목 검사에서 "빠짐"으로만 보인다.
-- 여러 번 실행해도 안전하다.
-- =====================================================================
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.process_shot', N'lot_no') IS NULL
    ALTER TABLE dbo.process_shot ADD lot_no VARCHAR(40) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_lot')
    CREATE INDEX ix_process_shot_lot ON dbo.process_shot (lot_no, equipment_id) WHERE lot_no IS NOT NULL;
GO

IF COL_LENGTH(N'dbo.ncr', N'affected_lots') IS NULL
    ALTER TABLE dbo.ncr ADD affected_lots NVARCHAR(400) NULL;
GO
IF COL_LENGTH(N'dbo.ncr', N'action_owner') IS NULL
    ALTER TABLE dbo.ncr ADD action_owner NVARCHAR(50) NULL;
GO
IF COL_LENGTH(N'dbo.ncr', N'action_due_date') IS NULL
    ALTER TABLE dbo.ncr ADD action_due_date DATE NULL;
GO

-- ---------------------------------------------------------------------
-- usp_AssignNcrAction: 조치 담당자·기한·영향 로트 지정
--   초안(DRAFT)과 승인(APPROVED) 상태에서 바꿀 수 있다 (조치는 승인 뒤에 정해지기도 하므로).
--   @Clear* = 1 이면 그 칸을 비운다. 이력은 EDIT 로 남긴다 (changes_json 에 바뀐 항목).
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_AssignNcrAction
    @NcrNo         VARCHAR(20),
    @Actor         NVARCHAR(50),
    @Owner         NVARCHAR(50)  = NULL,
    @DueDate       DATE          = NULL,
    @AffectedLots  NVARCHAR(400) = NULL,
    @ClearOwner    BIT           = 0,
    @ClearDueDate  BIT           = 0,
    @ClearLots     BIT           = 0,
    @ChangesJson   NVARCHAR(MAX) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRANSACTION;
        DECLARE @id INT, @status VARCHAR(20);
        SELECT @id = ncr_id, @status = status FROM dbo.ncr WITH (UPDLOCK, HOLDLOCK) WHERE ncr_no = @NcrNo;
        IF @id IS NULL
            THROW 50404, N'부적합을 찾을 수 없습니다.', 1;
        IF @status NOT IN ('DRAFT', 'APPROVED')
            THROW 50409, N'초안(DRAFT)이나 승인(APPROVED) 상태에서만 담당자·기한을 정할 수 있습니다.', 1;

        UPDATE dbo.ncr SET
            action_owner    = CASE WHEN @ClearOwner = 1 THEN NULL ELSE COALESCE(@Owner, action_owner) END,
            action_due_date = CASE WHEN @ClearDueDate = 1 THEN NULL ELSE COALESCE(@DueDate, action_due_date) END,
            affected_lots   = CASE WHEN @ClearLots = 1 THEN NULL ELSE COALESCE(@AffectedLots, affected_lots) END,
            updated_at      = SYSDATETIMEOFFSET()
        WHERE ncr_id = @id;

        INSERT INTO dbo.ncr_history (ncr_id, action, actor, from_status, to_status, changes_json)
        VALUES (@id, 'EDIT', @Actor, @status, @status, @ChangesJson);
    COMMIT;
END;
GO

SELECT name FROM sys.columns
WHERE object_id IN (OBJECT_ID(N'dbo.ncr'), OBJECT_ID(N'dbo.process_shot'))
  AND name IN (N'lot_no', N'affected_lots', N'action_owner', N'action_due_date') ORDER BY name;
GO
