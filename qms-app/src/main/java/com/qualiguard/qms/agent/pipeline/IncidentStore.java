package com.qualiguard.qms.agent.pipeline;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 진행 중인 사건 저장소 + 사건 상태 기록(agent_incident, agent_incident_event).
 * DB 기록은 화면·쿼리로 진행 상황을 보기 위한 것이며, 실패해도 파이프라인을 멈추지 않는다.
 */
public class IncidentStore {

    private static final Logger log = LoggerFactory.getLogger(IncidentStore.class);

    private final Map<String, Incident> live = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final AgentMetrics metrics;

    /** @param jdbc null 이면 DB 기록 없이 메모리만 (단위 시험) */
    public IncidentStore(JdbcTemplate jdbc) {
        this(jdbc, AgentMetrics.none());
    }

    public IncidentStore(JdbcTemplate jdbc, AgentMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
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
    }

    void close(Incident i, Integer analysisId, String ncrNo) {
        live.remove(i.id);
        db(() -> jdbc.update("UPDATE dbo.agent_incident SET analysis_id = ?, ncr_no = ?, updated_at = SYSDATETIMEOFFSET() "
                + "WHERE incident_id = ?", analysisId, ncrNo, i.id));
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
