package com.qualiguard.qms.agent.action;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 조치 기록(dbo.agent_action)과 효과 확인에 필요한 센서 조회. */
@Repository
public class ActionStore {

    /** 센서 키 → process_shot 컬럼. SQL 에 이름을 끼워 넣으므로 여기 있는 것만 허용한다. */
    private static final Map<String, String> COLUMNS = Map.of(
            "barrelTemp", "barrel_temp", "moldTemp", "mold_temp", "injectionPressure", "injection_pressure",
            "cushion", "cushion", "cycleTime", "cycle_time", "motorCurrent", "motor_current");

    private final JdbcTemplate jdbc;

    public ActionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(int analysisId, String equipmentId, String causeCode, ActionCatalog.Action a,
            ActionPolicy.Decision d, int attempt, Integer parentActionId) {
        boolean auto = d.mode() == ActionPolicy.Mode.AUTO;
        return jdbc.queryForObject("""
                INSERT INTO dbo.agent_action (analysis_id, equipment_id, cause_code, action_code, risk, mode, reasons,
                                              status, attempt, parent_action_id, decided_by)
                OUTPUT INSERTED.action_id
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Integer.class, analysisId, equipmentId, causeCode, a.code(), a.risk().name(), d.mode().name(),
                String.join(" / ", d.reasons()), auto ? "EXECUTED" : "WAITING_APPROVAL", attempt, parentActionId,
                auto ? "AGENT" : null);
    }

    /** 실행 표시: 지금 설비의 마지막 shot 을 효과 확인 구간의 시작으로 잡는다. */
    public void markExecuted(int actionId, String decidedBy) {
        jdbc.update("""
                UPDATE dbo.agent_action
                SET status = 'EXECUTED', executed_at = SYSDATETIMEOFFSET(), decided_by = COALESCE(?, decided_by),
                    base_shot = (SELECT MAX(shot_no) FROM dbo.process_shot WHERE equipment_id = agent_action.equipment_id)
                WHERE action_id = ? AND status IN ('EXECUTED', 'WAITING_APPROVAL')
                """, decidedBy, actionId);
    }

    /** 사람이 승인 대기 조치를 반려. 바뀐 행이 없으면 false (이미 처리됨). */
    public boolean reject(int actionId, String by) {
        return jdbc.update("UPDATE dbo.agent_action SET status = 'REJECTED', decided_by = ? "
                + "WHERE action_id = ? AND status = 'WAITING_APPROVAL'", by, actionId) == 1;
    }

    public boolean isWaiting(int actionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.agent_action WHERE action_id = ? AND status = 'WAITING_APPROVAL'",
                Integer.class, actionId) == 1;
    }

    public int autoCountLast24h(String equipmentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.agent_action
                WHERE equipment_id = ? AND mode = 'AUTO' AND created_at >= DATEADD(HOUR, -24, SYSDATETIMEOFFSET())
                """, Integer.class, equipmentId);
    }

    /** 이 분석이 "효과 없음" 때문에 다시 한 분석이면, 그 원인이 된 앞 조치. */
    public Optional<Map<String, Object>> retriedFrom(Integer parentAnalysisId) {
        if (parentAnalysisId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("""
                SELECT TOP 1 action_id, attempt FROM dbo.agent_action
                WHERE analysis_id = ? AND status = 'NOT_RESOLVED' ORDER BY action_id DESC
                """, parentAnalysisId).stream().findFirst();
    }

    /**
     * 실행했고, 실행 뒤 checkShots 만큼 shot 이 쌓인 조치들.
     * shot 번호는 시뮬레이터를 다시 돌리면 1부터 다시 시작하므로, 실행 시각(executed_at) 뒤에 들어온 shot 을 센다.
     */
    public List<Map<String, Object>> dueForCheck(int checkShots) {
        return jdbc.queryForList("""
                SELECT a.action_id, a.analysis_id, a.equipment_id, a.action_code, a.cause_code, a.attempt, a.base_shot
                FROM dbo.agent_action AS a
                WHERE a.status = 'EXECUTED' AND a.executed_at IS NOT NULL
                  AND (SELECT COUNT(*) FROM dbo.process_shot AS p
                        WHERE p.equipment_id = a.equipment_id AND p.created_at > a.executed_at) >= ?
                ORDER BY a.action_id
                """, checkShots);
    }

    /**
     * 조치 전·뒤 구간의 센서 평균, 규격 목표, 뒤 구간 경보 수.
     * 구간은 shot 번호가 아니라 들어온 순서로 자른다: 실행 시각 전에 들어온 마지막 before 개, 뒤에 들어온 처음 after 개.
     * (shot 번호는 실행마다 1부터 다시 시작해, 번호로 자르면 이전 실행의 같은 번호 shot 이 섞인다)
     */
    public EffectEvaluator.Window window(int actionId, String equipmentId, String sensorKey, int before, int after) {
        String col = sensorKey == null ? null : COLUMNS.get(sensorKey);
        String value = col == null ? "CAST(NULL AS FLOAT)" : "CAST(" + col + " AS FLOAT)";
        Map<String, Object> r = jdbc.queryForMap("""
                WITH act AS (SELECT executed_at FROM dbo.agent_action WHERE action_id = ?),
                aft AS (SELECT TOP (?) p.created_at, {VALUE} AS v FROM dbo.process_shot AS p CROSS JOIN act
                        WHERE p.equipment_id = ? AND p.created_at > act.executed_at ORDER BY p.shot_id),
                bef AS (SELECT TOP (?) {VALUE} AS v FROM dbo.process_shot AS p CROSS JOIN act
                        WHERE p.equipment_id = ? AND p.created_at <= act.executed_at ORDER BY p.shot_id DESC)
                SELECT (SELECT COUNT(*) FROM aft) AS shots_after,
                       (SELECT AVG(v) FROM bef) AS mean_before,
                       (SELECT AVG(v) FROM aft) AS mean_after,
                       (SELECT COUNT(*) FROM dbo.anomaly_event AS e CROSS JOIN act
                         WHERE e.equipment_id = ? AND e.created_at > act.executed_at
                           AND e.created_at <= (SELECT MAX(created_at) FROM aft)
                           AND (? = 1 OR e.rule_code <> 'INDIVIDUAL')) AS alarms
                """.replace("{VALUE}", value), actionId, after, equipmentId, before, equipmentId, equipmentId,
                sensorKey == null ? 1 : 0);
        int shotsAfter = ((Number) r.get("shots_after")).intValue();
        int alarms = ((Number) r.get("alarms")).intValue();
        if (col == null) {
            return new EffectEvaluator.Window(null, null, null, alarms, shotsAfter);
        }
        Double target = jdbc.queryForList("SELECT CAST(target AS FLOAT) FROM dbo.sensor_spec "
                + "WHERE equipment_id = ? AND sensor_key = ?", Double.class, equipmentId, sensorKey)
                .stream().findFirst().orElse(null);
        return new EffectEvaluator.Window(toDouble(r.get("mean_before")), toDouble(r.get("mean_after")), target,
                alarms, shotsAfter);
    }

    private static Double toDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    /**
     * 효과가 없어 재분석했는데 다음 조치가 생기지 않은 조치들 (재분석이 관찰·원인 불명·실패로 끝남).
     * 재분석 결과가 저장되고 graceSeconds 가 지나도 다음 조치(parent_action_id)가 없으면 사람에게 넘길 대상.
     */
    public List<Map<String, Object>> stalledRetries(int graceSeconds) {
        return jdbc.queryForList("""
                SELECT a.action_id, a.action_code, a.attempt, a.effect_detail,
                       g.analysis_id AS retry_analysis_id, g.decision AS retry_decision, g.cause_code AS retry_cause
                FROM dbo.agent_action AS a
                CROSS APPLY (SELECT TOP 1 x.analysis_id, x.decision, x.cause_code, x.created_at
                             FROM dbo.agent_analysis AS x
                             WHERE x.parent_analysis_id = a.analysis_id AND x.created_at >= a.checked_at
                             ORDER BY x.analysis_id DESC) AS g
                WHERE a.status = 'NOT_RESOLVED'
                  AND g.created_at < DATEADD(SECOND, -?, SYSDATETIMEOFFSET())
                  AND NOT EXISTS (SELECT 1 FROM dbo.agent_action AS c WHERE c.parent_action_id = a.action_id)
                ORDER BY a.action_id
                """, graceSeconds);
    }

    public void markChecked(int actionId, String status, String detail) {
        jdbc.update("UPDATE dbo.agent_action SET status = ?, effect_detail = ?, checked_at = SYSDATETIMEOFFSET() "
                + "WHERE action_id = ?", status, detail.length() > 500 ? detail.substring(0, 500) : detail, actionId);
    }

    public List<Map<String, Object>> recent(int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) a.action_id, a.analysis_id, a.equipment_id, a.cause_code, a.action_code, a.risk, a.mode,
                       a.reasons, a.status, a.attempt, a.parent_action_id, a.base_shot, a.effect_detail, a.decided_by,
                       CONVERT(VARCHAR(33), a.created_at, 127) AS created_at,
                       CONVERT(VARCHAR(33), a.checked_at, 127) AS checked_at
                FROM dbo.agent_action AS a ORDER BY a.action_id DESC
                """, top);
    }
}