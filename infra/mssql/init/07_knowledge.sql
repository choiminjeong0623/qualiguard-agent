-- =====================================================================
-- 지식저장소 반영 (F-10) — 승인된 부적합을 다음 분석의 유사 사례로 재사용
-- 여러 번 실행해도 안전하다.
--
-- 흐름: AI 초안(DRAFT) ─승인→ APPROVED ─조치 완료→ CLOSED
--   · usp_FindSimilarNcr 는 APPROVED·CLOSED 를 유사 사례로 검색한다 (04_procedures.sql)
--   · 승인 시점(approved_at)이 분석 기준 시점보다 앞선 사례만 검색한다 → 지난 경보를 다시 분석해도 미래 지식이 섞이지 않음
--   · 승인에는 확정 원인·원인 코드가 필요하다 (06_qms_review.sql 의 usp_ReviewNcr) → 지식저장소에 빈 원인이 들어가지 않음
-- =====================================================================
USE QUALIGUARD;
GO

-- ── 검토 이력에 조치 완료(CLOSE) 추가 ────────────────────────────────
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name = N'ck_ncr_history_action' AND definition NOT LIKE N'%CLOSE%')
    ALTER TABLE dbo.ncr_history DROP CONSTRAINT ck_ncr_history_action;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'ck_ncr_history_action')
    ALTER TABLE dbo.ncr_history ADD CONSTRAINT ck_ncr_history_action
        CHECK (action IN ('EDIT', 'APPROVE', 'REJECT', 'CLOSE'));
GO

-- ---------------------------------------------------------------------
-- usp_CloseNcr: 조치 완료 (APPROVED 일 때만, 조치 내용 필요)
--   조치 내용(action_taken)은 다음에 비슷한 사례를 만난 에이전트가 권고 조치를 쓸 때 참고한다.
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_CloseNcr
    @NcrNo       VARCHAR(20),
    @Actor       NVARCHAR(50),
    @ActionTaken NVARCHAR(1000)
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    IF NULLIF(LTRIM(RTRIM(@ActionTaken)), N'') IS NULL
        THROW 50400, N'조치 내용을 입력하세요.', 1;

    BEGIN TRANSACTION;
        DECLARE @id INT, @status VARCHAR(20);
        SELECT @id = ncr_id, @status = status FROM dbo.ncr WITH (UPDLOCK, HOLDLOCK) WHERE ncr_no = @NcrNo;
        IF @id IS NULL
            THROW 50404, N'부적합을 찾을 수 없습니다.', 1;
        IF @status <> 'APPROVED'
            THROW 50409, N'승인(APPROVED)된 부적합만 조치 완료로 닫을 수 있습니다.', 1;

        UPDATE dbo.ncr SET status = 'CLOSED', action_taken = @ActionTaken, updated_at = SYSDATETIMEOFFSET()
        WHERE ncr_id = @id;

        INSERT INTO dbo.ncr_history (ncr_id, action, actor, from_status, to_status, comment)
        VALUES (@id, 'CLOSE', @Actor, 'APPROVED', 'CLOSED', @ActionTaken);
    COMMIT;
END;
GO

-- ---------------------------------------------------------------------
-- usp_KnowledgeStats: 지식저장소 현황 (사람 작성 / AI 초안 승인, 원인 코드별)
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_KnowledgeStats
AS
BEGIN
    SET NOCOUNT ON;
    SELECT ISNULL(cause_code, 'UNKNOWN') AS cause_code,
           SUM(CASE WHEN created_by = 'AGENT' THEN 0 ELSE 1 END) AS human_written,
           SUM(CASE WHEN created_by = 'AGENT' THEN 1 ELSE 0 END) AS ai_approved,
           COUNT(*) AS total
    FROM dbo.ncr
    WHERE status IN ('APPROVED', 'CLOSED')
    GROUP BY ISNULL(cause_code, 'UNKNOWN')
    ORDER BY cause_code;
END;
GO

SELECT name AS knowledge_object FROM sys.objects
WHERE name IN (N'usp_CloseNcr', N'usp_KnowledgeStats') ORDER BY name;
GO
