-- =====================================================================
-- MES/ERP 응답 수신 (ADR-012) — 가상 MES/ERP 의 처리 결과를 QMS 가 받아 연계 상태를 갱신
-- 여러 번 실행해도 안전하다.
--
-- 응답 단계(seq): 1 = RECEIVED(수신 확인) → 2 = PROCESSED(처리 완료, 작업 지시·전표 번호) 또는 FAILED(실패, 사유)
--   · 같은 응답(response_id)을 두 번 받으면 무시 (integration_ack_log 고유)
--   · 순서가 뒤바뀌어 늦게 온 낮은 단계 응답은 상태를 되돌리지 않음 (seq 가 큰 것만 반영)
--   · 다시 보내기(retry)는 새 이벤트(event_id)로 보내므로, 이전 실패 기록은 그대로 남는다
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.integration_ack_log', N'U') IS NULL
CREATE TABLE dbo.integration_ack_log (
    response_id  UNIQUEIDENTIFIER  NOT NULL CONSTRAINT pk_integration_ack_log PRIMARY KEY,
    event_id     UNIQUEIDENTIFIER  NOT NULL,
    system_name  VARCHAR(10)       NOT NULL,
    status       VARCHAR(20)       NOT NULL,
    seq          INT               NOT NULL,
    payload      NVARCHAR(MAX)     NOT NULL,
    applied      BIT               NOT NULL,          -- 상태를 바꿨는지 (순서가 늦은 응답이면 0)
    received_at  DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_integration_ack_log_received DEFAULT (SYSDATETIMEOFFSET())
);
GO

IF OBJECT_ID(N'dbo.integration_status', N'U') IS NULL
CREATE TABLE dbo.integration_status (
    event_id     UNIQUEIDENTIFIER  NOT NULL,
    system_name  VARCHAR(10)       NOT NULL CONSTRAINT ck_integration_status_system CHECK (system_name IN ('MES', 'ERP')),
    status       VARCHAR(20)       NOT NULL CONSTRAINT ck_integration_status_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    seq          INT               NOT NULL,
    reference_no VARCHAR(50)       NULL,               -- 작업 지시 번호·전표 번호
    message      NVARCHAR(500)     NULL,               -- 실패 사유 등
    updated_at   DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_integration_status_updated DEFAULT (SYSDATETIMEOFFSET()),
    CONSTRAINT pk_integration_status PRIMARY KEY (event_id, system_name)
);
GO

-- ---------------------------------------------------------------------
-- usp_ApplyIntegrationAck: 응답 1건 반영. 결과 한 줄: result = DUPLICATE / STALE / APPLIED
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ApplyIntegrationAck
    @ResponseId  UNIQUEIDENTIFIER,
    @EventId     UNIQUEIDENTIFIER,
    @System      VARCHAR(10),
    @Status      VARCHAR(20),
    @Seq         INT,
    @ReferenceNo VARCHAR(50)    = NULL,
    @Message     NVARCHAR(500)  = NULL,
    @Payload     NVARCHAR(MAX)
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRANSACTION;
        IF EXISTS (SELECT 1 FROM dbo.integration_ack_log WITH (UPDLOCK, HOLDLOCK) WHERE response_id = @ResponseId)
        BEGIN
            COMMIT;
            SELECT 'DUPLICATE' AS result;
            RETURN;
        END;

        DECLARE @current INT;
        SELECT @current = seq FROM dbo.integration_status WITH (UPDLOCK, HOLDLOCK)
        WHERE event_id = @EventId AND system_name = @System;

        DECLARE @applied BIT = CASE WHEN @current IS NULL OR @Seq > @current THEN 1 ELSE 0 END;
        IF @current IS NULL
            INSERT INTO dbo.integration_status (event_id, system_name, status, seq, reference_no, message)
            VALUES (@EventId, @System, @Status, @Seq, @ReferenceNo, @Message);
        ELSE IF @applied = 1
            UPDATE dbo.integration_status
            SET status = @Status, seq = @Seq, reference_no = COALESCE(@ReferenceNo, reference_no),
                message = @Message, updated_at = SYSDATETIMEOFFSET()
            WHERE event_id = @EventId AND system_name = @System;

        INSERT INTO dbo.integration_ack_log (response_id, event_id, system_name, status, seq, payload, applied)
        VALUES (@ResponseId, @EventId, @System, @Status, @Seq, @Payload, @applied);
    COMMIT;
    SELECT CASE WHEN @applied = 1 THEN 'APPLIED' ELSE 'STALE' END AS result;
END;
GO

SELECT name AS ack_object FROM sys.objects
WHERE name IN (N'integration_ack_log', N'integration_status', N'usp_ApplyIntegrationAck') ORDER BY name;
GO
