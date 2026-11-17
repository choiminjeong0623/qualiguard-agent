-- 출하 시 확정 시리얼 ↔ shot 연결 (ADR-017) — 표만 먼저 만든다. 채우는 로직은 향후(출하 확정 단계).
--
-- 공정 중 시리얼(process_shot.serial_no)은 null 이거나 임시값(TMP-…)이고 중복될 수 있어 고유 키가 아니다.
-- shot 의 추적 키는 message_id(그리고 사람이 읽는 키 equipment_id + shot_no + shot_ts)다.
-- 출하 확정 때 이 표에 "확정 시리얼 → 그 제품을 만든 shot" 을 한 줄씩 넣고, 이때만 고유성을 강제한다.
--   - confirmed_serial UNIQUE : 확정 시리얼은 한 번만
--   - message_id      UNIQUE : shot 하나는 확정 시리얼 하나에만
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.shipment_serial_link', N'U') IS NULL
CREATE TABLE dbo.shipment_serial_link (
    link_id           BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT pk_shipment_serial_link PRIMARY KEY,
    confirmed_serial  NVARCHAR(40)      NOT NULL,   -- 출하 때 확정한 시리얼
    message_id        VARCHAR(36)       NOT NULL,   -- 그 제품을 만든 shot (process_shot.message_id)
    equipment_id      VARCHAR(20)       NOT NULL,
    shot_no           BIGINT            NULL,       -- 원본에 없었을 수 있음
    shot_ts           DATETIMEOFFSET(3) NOT NULL,
    provisional_serial NVARCHAR(40)     NULL,       -- 공정 중에 쓰던 임시 시리얼(있었다면), 대조용
    shipment_no       VARCHAR(30)       NULL,       -- 출하 번호 (출하 시스템 연계 시)
    confirmed_at      DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_shipment_serial_confirmed DEFAULT (SYSDATETIMEOFFSET()),
    confirmed_by      NVARCHAR(50)      NOT NULL,
    CONSTRAINT fk_shipment_serial_shot FOREIGN KEY (message_id) REFERENCES dbo.process_shot (message_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ux_shipment_serial_confirmed' AND object_id = OBJECT_ID(N'dbo.shipment_serial_link'))
    CREATE UNIQUE INDEX ux_shipment_serial_confirmed ON dbo.shipment_serial_link (confirmed_serial);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ux_shipment_serial_message' AND object_id = OBJECT_ID(N'dbo.shipment_serial_link'))
    CREATE UNIQUE INDEX ux_shipment_serial_message ON dbo.shipment_serial_link (message_id);
GO
