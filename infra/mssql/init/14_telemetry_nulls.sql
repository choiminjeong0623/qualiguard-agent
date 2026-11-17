-- 장비 데이터 결측 허용과 시리얼 번호 비고유 (ADR-017)
--
-- 실제 설비 메시지는 shotNo·검사 결과가 빠지는 일이 있다. 그런 shot 도 버리지 않고 적재한다.
--   - process_shot.shot_no           : NULL 허용 (메시지에 shotNo 없음)
--   - process_shot.inspection_result : NULL 허용 (비전 검사 결과 없음 → 불량률 집계에서 뺌)
--   - process_shot.serial_no         : 새 칸. 출하 전에는 미할당(NULL)·중복이 정상이라 UNIQUE 를 걸지 않는다
-- 추적 키는 message_id(고유 인덱스 그대로)와 equipment_id + shot_no 다.
-- 여러 번 실행해도 같은 결과가 되도록 지금 상태를 보고 바꾼다 (기존 로컬 DB 에도 그대로 실행).
USE QUALIGUARD;
GO

-- inspection_result 는 인덱스 포함 칸·CHECK 제약에 걸려 있어, 둘을 잠시 지우고 바꾼 뒤 다시 만든다
IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID(N'dbo.process_shot') AND name = N'inspection_result' AND is_nullable = 0)
BEGIN
    IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_equipment_ts' AND object_id = OBJECT_ID(N'dbo.process_shot'))
        DROP INDEX ix_process_shot_equipment_ts ON dbo.process_shot;
    IF OBJECT_ID(N'dbo.ck_process_shot_result', N'C') IS NOT NULL
        ALTER TABLE dbo.process_shot DROP CONSTRAINT ck_process_shot_result;

    ALTER TABLE dbo.process_shot ALTER COLUMN inspection_result CHAR(2) NULL;
END;
GO
IF OBJECT_ID(N'dbo.ck_process_shot_result', N'C') IS NULL
    ALTER TABLE dbo.process_shot ADD CONSTRAINT ck_process_shot_result
        CHECK (inspection_result IS NULL OR inspection_result IN ('OK', 'NG'));
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_equipment_ts' AND object_id = OBJECT_ID(N'dbo.process_shot'))
    CREATE INDEX ix_process_shot_equipment_ts ON dbo.process_shot (equipment_id, shot_ts)
        INCLUDE (inspection_result, defect_type);
GO

IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID(N'dbo.process_shot') AND name = N'shot_no' AND is_nullable = 0)
    ALTER TABLE dbo.process_shot ALTER COLUMN shot_no BIGINT NULL;
GO

IF COL_LENGTH(N'dbo.process_shot', N'serial_no') IS NULL
    ALTER TABLE dbo.process_shot ADD serial_no NVARCHAR(40) NULL;
GO
-- 시리얼로 찾기용 (고유하지 않음: 미할당·중복 허용, 출하 확정 때 고유 검증은 따로)
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_serial' AND object_id = OBJECT_ID(N'dbo.process_shot'))
    CREATE INDEX ix_process_shot_serial ON dbo.process_shot (serial_no) WHERE serial_no IS NOT NULL;
GO
-- 설비 shot 번호로 찾기용 (이상 이력 → 그 shot 의 공정 값). 고유하지 않음: 설비 카운터 리셋·결측
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_process_shot_equipment_shot' AND object_id = OBJECT_ID(N'dbo.process_shot'))
    CREATE INDEX ix_process_shot_equipment_shot ON dbo.process_shot (equipment_id, shot_no) WHERE shot_no IS NOT NULL;
GO

-- 늦게 도착한 shot (그 설비에서 이미 판정한 shot 보다 ts 가 앞섬): 저장은 하되 실시간 관리도 판정은 건너뛴 행
IF COL_LENGTH(N'dbo.process_shot', N'arrival_late') IS NULL
    ALTER TABLE dbo.process_shot ADD arrival_late BIT NOT NULL
        CONSTRAINT df_process_shot_arrival_late DEFAULT (0);
GO
