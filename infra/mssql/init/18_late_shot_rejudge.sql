-- 늦게 도착한 shot 재판정 결과 (ADR-019)
--
-- process_shot.arrival_late = 1 인 행(그 설비에서 이미 판정한 shot 보다 ts 가 앞서 늦게 온 shot)을 어떻게 다시 판정했는지.
--   REJUDGED      : 최근 30 shot 안이라 시간 순서에 끼워 모든 규칙(개별값·X̄·S·불량률)으로 다시 판정
--   OUT_OF_WINDOW : 더 늦게 와서 순서와 무관한 규칙(개별값·불량률)만 판정
--   NULL          : 늦지 않은 shot, 또는 이 기능 이전에 저장한 늦은 shot
-- 재판정으로 난 경보는 anomaly_event.evidence_json 의 "lateRejudged": true 와 메시지 앞 "[늦게 도착한 shot 재판정]" 으로 구분한다.
-- 여러 번 실행해도 안전하다.
USE QUALIGUARD;
GO

IF COL_LENGTH(N'dbo.process_shot', N'late_status') IS NULL
    ALTER TABLE dbo.process_shot ADD late_status VARCHAR(20) NULL
        CONSTRAINT ck_process_shot_late_status CHECK (late_status IS NULL OR late_status IN ('REJUDGED', 'OUT_OF_WINDOW'));
GO
