-- =====================================================================
-- 기준 데이터: 설비, 검사 규격, 정비 이력, 과거 부적합 사례
-- 여러 번 실행해도 중복 생성되지 않는다.
-- =====================================================================
USE QUALIGUARD;
GO

-- ── 설비 ───────────────────────────────────────────────────────────
MERGE dbo.equipment AS t
USING (VALUES
    ('IMM-01', 'LINE1', N'1라인 사출성형기 1호기', N'전동식 사출기 220톤', N'차단기 하우징 커버', '2019-03-15'),
    ('IMM-02', 'LINE1', N'1라인 사출성형기 2호기', N'전동식 사출기 220톤', N'차단기 하우징 커버', '2021-07-01')
) AS s (equipment_id, line_id, equipment_name, model, product_name, install_date)
ON t.equipment_id = s.equipment_id
WHEN NOT MATCHED THEN
    INSERT (equipment_id, line_id, equipment_name, model, product_name, install_date)
    VALUES (s.equipment_id, s.line_id, s.equipment_name, s.model, s.product_name, s.install_date);
GO

-- ── 검사 규격 (두 설비 동일 품목이라 같은 규격) ───────────────────
MERGE dbo.sensor_spec AS t
USING (
    SELECT e.equipment_id, v.sensor_key, v.sensor_name, v.unit, v.lsl, v.target, v.usl
    FROM dbo.equipment AS e
    CROSS JOIN (VALUES
        ('moldTemp',          N'금형 온도',   N'℃',   55.0,   60.0,   66.0),
        ('barrelTemp',        N'실린더 온도', N'℃',  220.0,  230.0,  240.0),
        ('injectionPressure', N'사출 압력',   N'bar', 1150.0, 1200.0, 1280.0),
        ('holdingPressure',   N'보압',        N'bar',  570.0,  600.0,  630.0),
        ('cushion',           N'쿠션량',      N'mm',     3.5,    5.0,    6.5),
        ('cycleTime',         N'사이클 타임', N's',     28.0,   30.0,   32.0),
        ('motorCurrent',      N'모터 전류',   N'A',     40.0,   45.0,   50.0)
    ) AS v (sensor_key, sensor_name, unit, lsl, target, usl)
) AS s
ON t.equipment_id = s.equipment_id AND t.sensor_key = s.sensor_key
WHEN NOT MATCHED THEN
    INSERT (equipment_id, sensor_key, sensor_name, unit, lsl, target, usl)
    VALUES (s.equipment_id, s.sensor_key, s.sensor_name, s.unit, s.lsl, s.target, s.usl);
GO

-- ── 정비 이력 (에이전트가 원인 추정 근거로 조회) ────────────────
IF NOT EXISTS (SELECT 1 FROM dbo.maintenance_history)
INSERT INTO dbo.maintenance_history (equipment_id, maint_date, maint_type, part_name, description, technician) VALUES
('IMM-01', '2024-11-20', N'부품교체', N'체크 링',        N'정기 교체. 이후 교체 주기 24개월 권장', N'설비보전팀 김OO'),
('IMM-01', '2025-09-03', N'고장수리', N'실린더 히터 밴드', N'2번 존 히터 밴드 저항값 상승으로 교체. 1·3번 존은 측정값 정상 범위 상한 근접, 차기 점검 시 확인 필요', N'설비보전팀 이OO'),
('IMM-01', '2026-06-15', N'예방정비', NULL,              N'유압·전동부 정기 점검, 이상 없음', N'설비보전팀 김OO'),
('IMM-02', '2025-12-10', N'예방정비', N'금형 냉각수 필터', N'냉각수 필터 청소. 스케일 다량 발견, 냉각 채널 세척 권고(미실시)', N'설비보전팀 박OO'),
('IMM-02', '2026-03-22', N'교정',     N'사출 압력 센서',   N'압력 센서 교정. 커넥터 체결부 헐거움 확인 후 재체결', N'계측팀 최OO'),
('IMM-02', '2026-08-30', N'예방정비', NULL,              N'정기 점검, 이상 없음', N'설비보전팀 박OO');
GO

-- ── 과거 부적합 사례 (승인 완료) — 유사 사례 검색·지식저장소의 출발점 ─
-- 실제 원인 4종에 대응하는 사례와, 비슷해 보이지만 원인이 다른 사례를 섞었다.
IF NOT EXISTS (SELECT 1 FROM dbo.ncr WHERE ncr_no = 'NCR-2025-0012')
INSERT INTO dbo.ncr (ncr_no, equipment_id, defect_type, related_sensors, title, phenomenon, suspected_cause, root_cause, evidence, action_taken, status, created_by, approved_by, approved_at, occurred_at) VALUES
('NCR-2025-0012', 'IMM-01', 'SHORT_SHOT', 'barrelTemp,injectionPressure,motorCurrent',
 N'1호기 미성형 증가 — 실린더 히터 밴드 열화',
 N'오후 근무 중 미성형 불량률이 0.5%에서 8%로 증가',
 N'수지 온도 저하에 따른 유동성 저하',
 N'실린더 2번 존 히터 밴드 열화로 실린더 온도가 설정 대비 약 12℃ 낮게 유지됨',
 N'실린더 온도 3시간에 걸쳐 서서히 하락, 같은 기간 사출 압력과 모터 전류 상승. 히터 밴드 저항값 측정 결과 정격 대비 35% 증가',
 N'히터 밴드 교체 후 온도·불량률 정상화. 히터 저항 정기 측정 항목 추가',
 'CLOSED', N'품질팀 정OO', N'품질팀장 한OO', '2025-09-04T10:00:00+09:00', '2025-09-02T15:30:00+09:00'),

('NCR-2025-0021', 'IMM-02', 'DIMENSION', 'moldTemp,cycleTime',
 N'2호기 치수 불량 — 금형 냉각 채널 스케일',
 N'커버 외곽 치수가 상한 초과, 일부 휨 발생',
 N'금형 온도 상승에 따른 수축량 증가',
 N'금형 냉각 채널 내부 스케일 누적으로 냉각수 유량 감소',
 N'금형 온도 이틀에 걸쳐 약 6℃ 상승, 냉각 시간 증가로 사이클 타임 연장. 냉각수 유량계 값 정상 대비 60%',
 N'냉각 채널 화학 세척 후 정상화. 냉각수 필터 점검 주기를 분기→월 단위로 단축',
 'CLOSED', N'품질팀 정OO', N'품질팀장 한OO', '2025-11-18T09:00:00+09:00', '2025-11-14T08:00:00+09:00'),

('NCR-2024-0033', 'IMM-01', 'SHORT_SHOT', 'cushion,injectionPressure',
 N'1호기 간헐적 미성형 — 체크 링 마모',
 N'미성형이 불규칙하게 발생, 쿠션량 편차 증가',
 N'역류로 인한 사출량 부족',
 N'스크류 선단 체크 링 마모로 사출 중 용융 수지 역류',
 N'쿠션량이 평균 5.0mm에서 3.2mm로 감소, 사출 압력 변동폭이 평소의 3배. 분해 점검 결과 체크 링 마모 확인',
 N'체크 링 교체(2024-11-20). 쿠션량 하한 경보 설정',
 'CLOSED', N'품질팀 윤OO', N'품질팀장 한OO', '2024-11-21T11:00:00+09:00', '2024-11-18T13:00:00+09:00'),

('NCR-2026-0004', 'IMM-02', NULL, 'injectionPressure',
 N'2호기 사출 압력 이상값 — 센서 접촉 불량 (제품 영향 없음)',
 N'사출 압력 값이 간헐적으로 ±200bar 이상 튐. 해당 시간대 제품 검사 결과 이상 없음',
 N'측정 계통 이상',
 N'압력 센서 커넥터 체결부 헐거움으로 신호 노이즈 발생',
 N'튀는 값이 연속되지 않고 단발적. 다른 센서와 제품 품질은 정상. 커넥터 재체결 후 재발 없음',
 N'커넥터 재체결 및 센서 교정(2026-03-22). 제품 부적합 아님으로 종결',
 'CLOSED', N'품질팀 정OO', N'품질팀장 한OO', '2026-03-23T09:00:00+09:00', '2026-03-21T10:00:00+09:00'),

('NCR-2025-0030', 'IMM-01', 'SHORT_SHOT', 'injectionPressure',
 N'1호기 미성형 — 원료 건조 불량',
 N'원료 로트 변경 직후 미성형과 은줄(실버 스트리크) 동반 발생',
 N'원료 수분',
 N'원료 건조기 온도 설정 오류로 수분 함량 기준 초과',
 N'원료 로트 변경 시점과 불량 발생 시점이 일치, 실린더 온도는 정상. 수분계 측정 0.18% (기준 0.05% 이하)',
 N'건조 조건 재설정, 로트 변경 시 수분 측정 의무화',
 'CLOSED', N'품질팀 윤OO', N'품질팀장 한OO', '2025-12-03T09:00:00+09:00', '2025-12-02T09:00:00+09:00');
GO

SELECT 'equipment' AS table_name, COUNT(*) AS row_count FROM dbo.equipment
UNION ALL SELECT 'sensor_spec', COUNT(*) FROM dbo.sensor_spec
UNION ALL SELECT 'maintenance_history', COUNT(*) FROM dbo.maintenance_history
UNION ALL SELECT 'ncr', COUNT(*) FROM dbo.ncr;
GO
