package com.qualiguard.qms.agent.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 진행 중인 사건 저장소 + 사건 상태 기록(agent_incident, agent_incident_event).
 * DB 기록은 화면·쿼리로 진행 상황을 보기 위한 것이며, 실패해도 파이프라인을 멈추지 않는다.
 *
 * <p>재시작 복구 (로드맵 A2): 단계가 바뀌거나 에이전트가 결과를 쓸 때마다 사건 기록(checkpoint_json)을 남긴다.
 * 앱이 다시 켜지면 {@link #restore} 가 끝나지 않은 사건을 메모리로 되살리고, 오케스트레이터가 멈춘 단계부터 이어 간다.
 */
public class IncidentStore {

    private static final Logger log = LoggerFactory.getLogger(IncidentStore.class);

    private final Map<String, Incident> live = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final AgentMetrics metrics;
    private final ObjectMapper objectMapper;

    /** @param jdbc null 이면 DB 기록 없이 메모리만 (단위 시험) */
    public IncidentStore(JdbcTemplate jdbc) {
        this(jdbc, AgentMetrics.none());
    }

    public IncidentStore(JdbcTemplate jdbc, AgentMetrics metrics) {
        this(jdbc, metrics, null);
    }

    /** @param objectMapper null 이면 사건 기록(checkpoint)을 남기지 않는다 */
    public IncidentStore(JdbcTemplate jdbc, AgentMetrics metrics, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /** 사건 기록을 남긴다. 에이전트가 결과를 쓴 뒤, 다음 이벤트를 보내기 전에 부른다 (재시작 복구용). */
    void checkpoint(Incident i) {
        if (objectMapper == null) {
            return;
        }
        db(() -> jdbc.update("UPDATE dbo.agent_incident SET checkpoint_json = ? WHERE incident_id = ?",
                objectMapper.writeValueAsString(i.snapshot()), i.id));
    }

    /**
     * 재시작 뒤: 끝나지 않은 사건을 DB 기록에서 되살려 진행 목록에 넣는다.
     * 기록이 없거나 읽을 수 없는 사건(이 기능 전의 사건 등)은 이어 갈 수 없어 FAILED 로 닫는다.
     *
     * @return 되살린 사건 (restoredAgeMinutes 에 마지막 기록 뒤 지난 분)
     */
    public List<Incident> restore() {
        if (jdbc == null || objectMapper == null) {
            return List.of();
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT incident_id, checkpoint_json, DATEDIFF(MINUTE, updated_at, SYSDATETIMEOFFSET()) AS age_min
                    FROM dbo.agent_incident
                    WHERE state IN ('TRIAGED', 'ANALYZING', 'VERIFYING', 'WRITING')
                    ORDER BY created_at
                    """);
        } catch (RuntimeException e) {
            log.warn("[재시작 복구] 끝나지 않은 사건 조회 실패: {}", e.toString());
            return List.of();
        }
        return restoreFrom(rows);
    }

    /** @param rows incident_id, checkpoint_json, age_min (단위 시험은 DB 없이 이걸 바로 부른다) */
    @SuppressWarnings("unchecked")
    List<Incident> restoreFrom(List<Map<String, Object>> rows) {
        List<Incident> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("incident_id");
            String json = (String) r.get("checkpoint_json");
            if (live.containsKey(id)) {
                continue;
            }
            try {
                if (json == null) {
                    throw new IllegalStateException("사건 기록 없음");
                }
                Incident i = Incident.fromSnapshot(id, objectMapper.readValue(json, Map.class), objectMapper);
                i.restoredAgeMinutes = r.get("age_min") instanceof Number n ? n.longValue() : 0;
                live.put(id, i);
                out.add(i);
            } catch (RuntimeException e) {
                log.warn("[재시작 복구] 사건 {} 을 되살릴 수 없어 실패로 닫음: {}", id, e.toString());
                db(() -> {
                    jdbc.update("UPDATE dbo.agent_incident SET state = 'FAILED', updated_at = SYSDATETIMEOFFSET() "
                            + "WHERE incident_id = ?", id);
                    jdbc.update("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, detail) "
                            + "VALUES (?, ?, 'FAILED', ?)", id, AgentTopics.RESUMED, "재시작 뒤 되살릴 수 없음: " + e);
                });
            }
        }
        return out;
    }

    /** 단계 기록과 DB 사건 이력에만 남긴다 (상태는 그대로, 지표 없음). */
    void note(Incident i, String topic, String detail) {
        i.mark(topic, i.state, detail);
        db(() -> jdbc.update("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, detail) VALUES (?, ?, ?, ?)",
                i.id, topic, i.state.name(), detail));
        checkpoint(i);
    }

    void open(Incident i) {
        i.mark(AgentTopics.TRIAGED, Incident.State.TRIAGED, i.triage.severity() + " · " + i.triage.type());
        live.put(i.id, i);
        db(() -> jdbc.update("""
                INSERT INTO dbo.agent_incident (incident_id, equipment_id, trigger_type, seed_anomaly_id, run_tag,
                                                severity, incident_type, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, i.id, i.job.equipmentId(), i.job.trigger(), i.job.seedAnomalyId(), i.job.tag(),
                i.triage.severity(), i.triage.type(), i.state.name()));
        db(() -> jdbc.update("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, detail) VALUES (?, ?, ?, ?)",
                i.id, AgentTopics.TRIAGED, Incident.State.TRIAGED.name(), i.triage.reason()));
        checkpoint(i);
    }

    Optional<Incident> get(String id) {
        return Optional.ofNullable(live.get(id));
    }

    /** 오케스트레이터가 상태를 바꿀 때마다: 메모리 기록 + DB 기록. */
    void transition(Incident i, String topic, Incident.State to, String detail) {
        i.mark(topic, to, detail);
        recordStage(i);
        db(() -> {
            jdbc.update("UPDATE dbo.agent_incident SET state = ?, attempts = ?, updated_at = SYSDATETIMEOFFSET() "
                    + "WHERE incident_id = ?", to.name(), i.attempts, i.id);
            jdbc.update("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, detail) VALUES (?, ?, ?, ?)",
                    i.id, topic, to.name(), detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        });
        checkpoint(i);
    }

    /** 메모리 기록은 이미 남겼고(Incident.mark) DB 에만 남길 때. */
    void transitionLogged(Incident i, String topic, Incident.State to, String detail) {
        recordStage(i);
        db(() -> {
            jdbc.update("UPDATE dbo.agent_incident SET state = ?, attempts = ?, updated_at = SYSDATETIMEOFFSET() "
                    + "WHERE incident_id = ?", to.name(), i.attempts, i.id);
            jdbc.update("INSERT INTO dbo.agent_incident_event (incident_id, topic, state, detail) VALUES (?, ?, ?, ?)",
                    i.id, topic, to.name(), detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        });
        checkpoint(i);
    }

    void close(Incident i, Integer analysisId, String ncrNo) {
        live.remove(i.id);
        // 끝난 사건은 이어 갈 일이 없어 기록을 지운다 (결과는 agent_analysis 에 있다)
        db(() -> jdbc.update("UPDATE dbo.agent_incident SET analysis_id = ?, ncr_no = ?, checkpoint_json = NULL, "
                + "updated_at = SYSDATETIMEOFFSET() WHERE incident_id = ?", analysisId, ncrNo, i.id));
    }

    /** 사건 결과 지표 (로드맵 O2). 오케스트레이터가 사건을 닫을 때 부른다. */
    void recordResult(Incident i, String decision) {
        String verify = i.verification == null ? "-" : i.verification.status();
        metrics.result(decision, verify, i.state.name(), i.attempts > 1, i.elapsedMs());
    }

    private void recordStage(Incident i) {
        Object[] s = i.lastStage();
        if (s != null) {
            metrics.stage(String.valueOf(s[0]), (Long) s[1]);
        }
    }

    public Collection<Incident> inFlight() {
        return live.values();
    }

    private void db(Runnable r) {
        if (jdbc == null) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("[오케스트레이터] 사건 상태 기록 실패 (진행은 계속): {}", e.toString());
        }
    }
}
