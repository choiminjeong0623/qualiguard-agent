-- =====================================================================
-- MES/ERP 연계 (F-09) — 승인 결과를 다른 시스템으로 전달
-- 여러 번 실행해도 안전하다.
--
-- 트랜잭션 아웃박스(outbox):
--   승인(06_qms_review.sql 의 usp_ReviewNcr)과 같은 트랜잭션에서 usp_EnqueueNcrApproved 로 integration_outbox 에 이벤트를 쓴다.
--   앱의 발행기(OutboxPublisher)가 아직 보내지 않은 이벤트를 Kafka quality.ncr.approved 로 보내고 보낸 시각을 기록한다.
--   → "승인은 됐는데 이벤트는 안 나감"(DB 커밋 후 Kafka 실패)이나 "이벤트는 나갔는데 승인은 취소됨"이 생기지 않는다.
--   Kafka 전송은 최소 1번(at-least-once)이므로, 수신 쪽(integration_receipt)은 event_id 로 중복을 거른다.
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.integration_outbox', N'U') IS NULL
CREATE TABLE dbo.integration_outbox (
    event_id      UNIQUEIDENTIFIER  NOT NULL CONSTRAINT pk_integration_outbox PRIMARY KEY,
    event_type    VARCHAR(30)       NOT NULL,          -- NCR_APPROVED
    topic         VARCHAR(100)      NOT NULL,
    msg_key       VARCHAR(50)       NOT NULL,          -- Kafka 메시지 key (설비 ID → 같은 설비 이벤트 순서 보장)
    ncr_id        INT               NOT NULL CONSTRAINT fk_integration_outbox_ncr REFERENCES dbo.ncr (ncr_id),
    payload       NVARCHAR(MAX)     NOT NULL,          -- JSON
    created_at    DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_integration_outbox_created DEFAULT (SYSDATETIMEOFFSET()),
    published_at  DATETIMEOFFSET(3) NULL,              -- NULL 이면 아직 보내지 않음
    attempts      INT               NOT NULL CONSTRAINT df_integration_outbox_attempts DEFAULT (0),
    last_error    NVARCHAR(1000)    NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_integration_outbox_pending')
    CREATE INDEX ix_integration_outbox_pending ON dbo.integration_outbox (published_at, created_at);
GO

-- ── 가상 MES/ERP 수신 기록 (실제 시스템 대신, 받은 이벤트와 만든 업무를 남김) ──
IF OBJECT_ID(N'dbo.integration_receipt', N'U') IS NULL
CREATE TABLE dbo.integration_receipt (
    receipt_id   INT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_integration_receipt PRIMARY KEY,
    event_id     UNIQUEIDENTIFIER  NOT NULL,
    system_name  VARCHAR(10)       NOT NULL CONSTRAINT ck_integration_receipt_system CHECK (system_name IN ('MES', 'ERP')),
    ncr_no       VARCHAR(20)       NOT NULL,
    action       NVARCHAR(300)     NOT NULL,          -- 수신 시스템이 만든 업무 (가상)
    received_at  DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_integration_receipt_received DEFAULT (SYSDATETIMEOFFSET()),
    CONSTRAINT uq_integration_receipt UNIQUE (event_id, system_name)   -- 같은 이벤트를 두 번 받아도 한 번만 처리
);
GO

-- ---------------------------------------------------------------------
-- usp_EnqueueNcrApproved: 승인된 부적합을 이벤트로 아웃박스에 쓴다 (호출하는 쪽 트랜잭션 안에서)
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_EnqueueNcrApproved
    @NcrId INT
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @eventId UNIQUEIDENTIFIER = NEWID();
    DECLARE @payload NVARCHAR(MAX) = (
        SELECT @eventId AS eventId, 'NCR_APPROVED' AS eventType,
               CONVERT(VARCHAR(33), SYSDATETIMEOFFSET(), 127) AS occurredAt,
               n.ncr_no AS ncrNo, n.equipment_id AS equipmentId, n.status AS status,
               n.cause_code AS causeCode, n.root_cause AS rootCause, n.defect_type AS defectType,
               n.related_sensors AS relatedSensors, n.recommended_action AS recommendedAction,
               n.created_by AS createdBy, n.approved_by AS approvedBy,
               CONVERT(VARCHAR(33), n.approved_at, 127) AS approvedAt,
               CONVERT(VARCHAR(33), n.occurred_at, 127) AS occurredAtLine,
               (SELECT STRING_AGG(x.anomaly_id, ',') FROM dbo.ncr_anomaly AS x WHERE x.ncr_id = n.ncr_id) AS anomalyIds
        FROM dbo.ncr AS n WHERE n.ncr_id = @NcrId
        FOR JSON PATH, WITHOUT_ARRAY_WRAPPER);

    INSERT INTO dbo.integration_outbox (event_id, event_type, topic, msg_key, ncr_id, payload)
    SELECT @eventId, 'NCR_APPROVED', 'quality.ncr.approved', n.equipment_id, n.ncr_id, @payload
    FROM dbo.ncr AS n WHERE n.ncr_id = @NcrId;
END;
GO

SELECT name AS integration_object FROM sys.objects
WHERE name IN (N'integration_outbox', N'integration_receipt', N'usp_EnqueueNcrApproved') ORDER BY name;
GO
