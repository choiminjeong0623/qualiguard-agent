-- =====================================================================
-- 저장 프로시저 — 에이전트의 조회 도구와 QMS 화면이 사용한다.
-- @AsOf: 지난 경보를 나중에 분석할 때 "경보 당시까지의 데이터"만 보도록 기준 시점을 지정한다.
-- CREATE OR ALTER 라 여러 번 실행해도 안전하다.
--
-- 시뮬레이터는 실제(30초/shot)보다 훨씬 빠르게 데이터를 보내므로,
-- 조회 범위는 시간 대신 "최근 N shot" 기준으로 잡는다.
-- =====================================================================
USE QUALIGUARD;
GO

-- ---------------------------------------------------------------------
-- 1) 센서 추이: 최근 N shot 을 M shot 씩 묶어 평균·최소·최대·표준편차·불량 수
--    예) EXEC dbo.usp_GetSensorTrend 'IMM-01', 'barrelTemp', 200, 10;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_GetSensorTrend
    @EquipmentId  VARCHAR(20),
    @SensorKey    VARCHAR(30),
    @LastShots    INT = 200,
    @BucketSize   INT = 10,
    @AsOf         DATETIMEOFFSET(3) = NULL   -- 이 시점까지의 데이터만 (NULL 이면 최신까지)
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM dbo.sensor_spec WHERE equipment_id = @EquipmentId AND sensor_key = @SensorKey)
        THROW 50001, N'등록되지 않은 설비 또는 센서입니다.', 1;
    IF @LastShots < 1 OR @BucketSize < 1
        THROW 50002, N'@LastShots 와 @BucketSize 는 1 이상이어야 합니다.', 1;

    WITH recent AS (
        SELECT TOP (@LastShots)
               p.shot_id, p.shot_no, p.shot_ts, p.inspection_result,
               CASE @SensorKey
                   WHEN 'moldTemp'          THEN p.mold_temp
                   WHEN 'barrelTemp'        THEN p.barrel_temp
                   WHEN 'injectionPressure' THEN p.injection_pressure
                   WHEN 'holdingPressure'   THEN p.holding_pressure
                   WHEN 'cushion'           THEN p.cushion
                   WHEN 'cycleTime'         THEN p.cycle_time
                   WHEN 'motorCurrent'      THEN p.motor_current
               END AS val
        FROM dbo.process_shot AS p
        WHERE p.equipment_id = @EquipmentId
          AND (@AsOf IS NULL OR p.shot_ts <= @AsOf)
        ORDER BY p.shot_id DESC
    ),
    bucketed AS (
        SELECT *, (ROW_NUMBER() OVER (ORDER BY shot_id) - 1) / @BucketSize AS bucket
        FROM recent
    )
    SELECT b.bucket + 1                               AS bucket_no,
           MIN(b.shot_no)                             AS from_shot_no,
           MAX(b.shot_no)                             AS to_shot_no,
           MIN(b.shot_ts)                             AS from_ts,
           COUNT(*)                                   AS shots,
           CAST(AVG(b.val)   AS DECIMAL(12, 3))       AS avg_value,
           MIN(b.val)                                 AS min_value,
           MAX(b.val)                                 AS max_value,
           CAST(STDEV(b.val) AS DECIMAL(12, 4))       AS stdev_value,
           SUM(CASE WHEN b.inspection_result = 'NG' THEN 1 ELSE 0 END) AS ng_count,
           COUNT(b.inspection_result)                 AS inspected,   -- 검사 결과가 있는 shot 수 (ADR-017)
           s.lsl, s.target, s.usl, s.unit
    FROM bucketed AS b
    JOIN dbo.sensor_spec AS s
      ON s.equipment_id = @EquipmentId AND s.sensor_key = @SensorKey
    GROUP BY b.bucket, s.lsl, s.target, s.usl, s.unit
    ORDER BY b.bucket;
END;
GO

-- ---------------------------------------------------------------------
-- 2) 공정 능력 요약: 최근 N shot 의 센서별 평균·표준편차와 규격 대비 Cpk
--    Cpk = min(USL - 평균, 평균 - LSL) / (3 × 표준편차)
--    1.33 이상이면 양호, 1.0 미만이면 규격을 벗어난 제품이 나올 가능성이 높다는 뜻.
--    예) EXEC dbo.usp_GetSpcSummary 'IMM-01', 50;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_GetSpcSummary
    @EquipmentId  VARCHAR(20),
    @LastShots    INT = 50,
    @AsOf         DATETIMEOFFSET(3) = NULL
AS
BEGIN
    SET NOCOUNT ON;

    WITH recent AS (
        SELECT TOP (@LastShots) *
        FROM dbo.process_shot
        WHERE equipment_id = @EquipmentId
          AND (@AsOf IS NULL OR shot_ts <= @AsOf)
        ORDER BY shot_id DESC
    ),
    unpivoted AS (
        SELECT v.ord, v.sensor_key, v.val
        FROM recent AS r
        CROSS APPLY (VALUES
            (1, 'moldTemp',          r.mold_temp),
            (2, 'barrelTemp',        r.barrel_temp),
            (3, 'injectionPressure', r.injection_pressure),
            (4, 'holdingPressure',   r.holding_pressure),
            (5, 'cushion',           r.cushion),
            (6, 'cycleTime',         r.cycle_time),
            (7, 'motorCurrent',      r.motor_current)
        ) AS v (ord, sensor_key, val)
        WHERE v.val IS NOT NULL
    ),
    stats AS (
        SELECT u.ord, u.sensor_key,
               COUNT(*)                        AS n,
               CAST(AVG(u.val) AS FLOAT)       AS mean_value,
               STDEV(u.val)                    AS stdev_value,
               SUM(CASE WHEN u.val < s.lsl OR u.val > s.usl THEN 1 ELSE 0 END) AS out_of_spec
        FROM unpivoted AS u
        JOIN dbo.sensor_spec AS s
          ON s.equipment_id = @EquipmentId AND s.sensor_key = u.sensor_key
        GROUP BY u.ord, u.sensor_key
    )
    SELECT st.sensor_key, s.sensor_name, s.unit, st.n AS shots,
           CAST(st.mean_value  AS DECIMAL(12, 3)) AS mean_value,
           CAST(st.stdev_value AS DECIMAL(12, 4)) AS stdev_value,
           s.lsl, s.target, s.usl,
           CAST(CASE WHEN st.stdev_value > 0
                     THEN LEAST(s.usl - st.mean_value, st.mean_value - s.lsl) / (3 * st.stdev_value)
                END AS DECIMAL(9, 2))             AS cpk,
           st.out_of_spec                         AS out_of_spec_shots,
           CASE WHEN st.stdev_value IS NULL OR st.stdev_value = 0 THEN N'판정 불가'
                WHEN LEAST(s.usl - st.mean_value, st.mean_value - s.lsl) / (3 * st.stdev_value) >= 1.33 THEN N'양호'
                WHEN LEAST(s.usl - st.mean_value, st.mean_value - s.lsl) / (3 * st.stdev_value) >= 1.0  THEN N'주의'
                ELSE N'부족' END                  AS capability
    FROM stats AS st
    JOIN dbo.sensor_spec AS s
      ON s.equipment_id = @EquipmentId AND s.sensor_key = st.sensor_key
    ORDER BY st.ord;
END;
GO

-- ---------------------------------------------------------------------
-- 3) 불량 요약: 최근 N shot 을 구간별로 나눠 불량률과 유형별 건수
--    예) EXEC dbo.usp_GetDefectSummary 'IMM-01', 500, 50;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_GetDefectSummary
    @EquipmentId  VARCHAR(20),
    @LastShots    INT = 500,
    @WindowSize   INT = 50,
    @AsOf         DATETIMEOFFSET(3) = NULL
AS
BEGIN
    SET NOCOUNT ON;

    IF @LastShots < 1 OR @WindowSize < 1
        THROW 50002, N'@LastShots 와 @WindowSize 는 1 이상이어야 합니다.', 1;

    WITH recent AS (
        SELECT TOP (@LastShots) shot_id, shot_no, shot_ts, inspection_result, defect_type
        FROM dbo.process_shot
        WHERE equipment_id = @EquipmentId
          AND (@AsOf IS NULL OR shot_ts <= @AsOf)
        ORDER BY shot_id DESC
    ),
    windowed AS (
        SELECT *, (ROW_NUMBER() OVER (ORDER BY shot_id) - 1) / @WindowSize AS win
        FROM recent
    )
    SELECT win + 1                                                        AS window_no,
           MIN(shot_no)                                                   AS from_shot_no,
           MAX(shot_no)                                                   AS to_shot_no,
           COUNT(*)                                                       AS shots,
           COUNT(inspection_result)                                       AS inspected,   -- 검사 결과 없는 shot 은 불량률 분모에서 뺌 (ADR-017)
           SUM(CASE WHEN inspection_result = 'NG' THEN 1 ELSE 0 END)      AS ng_count,
           CAST(100.0 * SUM(CASE WHEN inspection_result = 'NG' THEN 1 ELSE 0 END)
                / NULLIF(COUNT(inspection_result), 0) AS DECIMAL(5, 1))  AS ng_rate_pct,
           SUM(CASE WHEN defect_type = 'SHORT_SHOT' THEN 1 ELSE 0 END)    AS short_shot,
           SUM(CASE WHEN defect_type = 'FLASH'      THEN 1 ELSE 0 END)    AS flash,
           SUM(CASE WHEN defect_type = 'DIMENSION'  THEN 1 ELSE 0 END)    AS dimension,
           SUM(CASE WHEN defect_type = 'WARPAGE'    THEN 1 ELSE 0 END)    AS warpage
    FROM windowed
    GROUP BY win
    ORDER BY win;
END;
GO

-- ---------------------------------------------------------------------
-- 4) 유사 부적합 사례 검색 (지식저장소)
--    관련 센서가 많이 겹칠수록, 불량 유형·설비가 같을수록 점수가 높다.
--    지금은 키워드 겹침 방식이고, 에이전트 단계에서 문장 임베딩(벡터) 검색을 더한다.
--    예) EXEC dbo.usp_FindSimilarNcr 'IMM-01', 'barrelTemp,injectionPressure', 'SHORT_SHOT', 3;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_FindSimilarNcr
    @EquipmentId  VARCHAR(20)  = NULL,
    @SensorKeys   VARCHAR(200) = NULL,     -- 쉼표 구분
    @DefectType   VARCHAR(20)  = NULL,
    @Top          INT          = 5,
    @AsOf         DATETIMEOFFSET(3) = NULL, -- 이 시점까지 승인된 사례만 (지난 경보 분석 시 미래 지식 차단)
    @QueryVector  NVARCHAR(MAX)     = NULL  -- 현재 현상 설명의 임베딩 '[...]' (768차원). 있으면 의미 유사도를 점수에 더함 (ADR-014)
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @keys TABLE (k VARCHAR(30) PRIMARY KEY);
    INSERT INTO @keys (k)
    SELECT DISTINCT LTRIM(RTRIM(value))
    FROM STRING_SPLIT(ISNULL(@SensorKeys, ''), ',')
    WHERE LTRIM(RTRIM(value)) <> '';

    WITH scored AS (
        SELECT n.ncr_no, n.equipment_id, n.defect_type, n.related_sensors, n.title, n.phenomenon,
               n.root_cause, n.evidence, n.action_taken, n.occurred_at, n.cause_code,
               CASE WHEN n.created_by = 'AGENT' THEN N'AI 초안을 사람이 승인' ELSE N'사람이 작성' END AS source,
               CASE WHEN @QueryVector IS NULL OR OBJECT_ID(N'dbo.ncr_embedding', N'U') IS NULL THEN NULL
                    ELSE (SELECT 1 - VECTOR_DISTANCE('cosine', e.embedding, CAST(@QueryVector AS VECTOR(768)))
                          FROM dbo.ncr_embedding AS e WHERE e.ncr_id = n.ncr_id) END AS semantic_similarity,
               (SELECT COUNT(*)
                  FROM STRING_SPLIT(ISNULL(n.related_sensors, ''), ',') AS s
                  JOIN @keys AS q ON q.k = LTRIM(RTRIM(s.value)))                     AS sensor_match,
               CASE WHEN @DefectType IS NOT NULL AND n.defect_type = @DefectType THEN 1 ELSE 0 END AS defect_match,
               CASE WHEN n.equipment_id = @EquipmentId THEN 1 ELSE 0 END             AS same_equipment
        FROM dbo.ncr AS n
        WHERE n.status IN ('APPROVED', 'CLOSED')
          AND (@AsOf IS NULL OR COALESCE(n.approved_at, n.created_at) <= @AsOf)
    )
    SELECT TOP (@Top)
           ncr_no, equipment_id, defect_type, related_sensors, title, phenomenon,
           root_cause, evidence, action_taken, occurred_at, cause_code, source,
           sensor_match, defect_match, same_equipment,
           CAST(ROUND(semantic_similarity, 3) AS DECIMAL(5, 3)) AS semantic_similarity,
           sensor_match * 2 + defect_match * 2 + same_equipment
             + CASE WHEN semantic_similarity >= 0.6 THEN CAST(ROUND((semantic_similarity - 0.6) * 10, 0) AS INT) ELSE 0 END AS score
    FROM scored
    WHERE sensor_match > 0 OR defect_match = 1 OR semantic_similarity >= 0.75
    ORDER BY score DESC, occurred_at DESC;
END;
GO

-- 평가용 숨김 표시: "처음 보는 고장" 실험에서 정비 이력을 잠시 가린다 (scripts/evaluate_agent.py --hide-maintenance).
-- 행을 지우지 않고 표시만 바꿔, 실험이 끝나면 그대로 되돌린다.
IF COL_LENGTH(N'dbo.maintenance_history', N'eval_hidden') IS NULL
    ALTER TABLE dbo.maintenance_history ADD eval_hidden BIT NOT NULL
        CONSTRAINT df_maintenance_eval_hidden DEFAULT (0);
GO

-- ---------------------------------------------------------------------
-- 5) 정비 이력
--    예) EXEC dbo.usp_GetMaintenanceHistory 'IMM-01', 10;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_GetMaintenanceHistory
    @EquipmentId  VARCHAR(20),
    @Top          INT = 10
AS
BEGIN
    SET NOCOUNT ON;

    SELECT TOP (@Top)
           maint_date, maint_type, part_name, description, technician,
           DATEDIFF(DAY, maint_date, CAST(SYSDATETIMEOFFSET() AS DATE)) AS days_ago
    FROM dbo.maintenance_history
    WHERE equipment_id = @EquipmentId
      AND eval_hidden = 0
    ORDER BY maint_date DESC;
END;
GO

-- ---------------------------------------------------------------------
-- 6) 이상 이력 목록 (QMS 화면·에이전트 공용)
--    예) EXEC dbo.usp_ListAnomalies @EquipmentId = 'IMM-01', @Status = 'NEW', @Top = 20;
-- ---------------------------------------------------------------------
CREATE OR ALTER PROCEDURE dbo.usp_ListAnomalies
    @EquipmentId  VARCHAR(20) = NULL,
    @Status       VARCHAR(20) = NULL,
    @Top          INT         = 50
AS
BEGIN
    SET NOCOUNT ON;

    SELECT TOP (@Top)
           anomaly_id, equipment_id, shot_no, detected_at, rule_code, rule_name,
           sensor_key, sensor_name, observed, lower_limit, upper_limit,
           recent_ng_rate, message, status
    FROM dbo.anomaly_event
    WHERE (@EquipmentId IS NULL OR equipment_id = @EquipmentId)
      AND (@Status IS NULL OR status = @Status)
    ORDER BY detected_at DESC, anomaly_id;
END;
GO

SELECT name AS created_procedure FROM sys.procedures ORDER BY name;
GO
