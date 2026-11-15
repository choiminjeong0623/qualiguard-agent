package com.qualiguard.qms.agent.pipeline;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qualiguard.qms.agent.IncidentAnalyzer;

/**
 * 분류 에이전트 (ADR-016): 경보를 사건으로 묶고, 심각도·유형을 정해 오케스트레이터에 넘긴다.
 *
 * 규칙 기반이다. 경보 규칙의 뜻(ADR-005)이 이미 분명해서 LLM 이 필요 없고, 모든 경보에 LLM 을 부르지 않는 것이
 * 이 구조의 비용 원칙(N-04)이기 때문이다. 묶기는 같은 설비·shot·시간 범위 규칙(IncidentAnalyzer.prepare*)을 쓴다.
 *
 *  - HIGH   · QUALITY_IMPACT : 불량률 경보(P_CHART) — 이미 제품에 영향
 *  - MEDIUM · PROCESS_SHIFT  : 평균 이동(XBAR) — 지속된 공정 변화
 *  - MEDIUM · VARIATION      : 산포 증가(S_CHART) — 지속된 흔들림
 *  - LOW    · SPIKE          : 개별값 이탈(INDIVIDUAL)만 — 단발성일 수 있음
 *  여러 센서가 함께 경보를 내면 한 단계 올린다 (LOW → MEDIUM, MEDIUM → HIGH).
 */
public class TriageAgent {

    private static final Logger log = LoggerFactory.getLogger(TriageAgent.class);

    public record Triage(String severity, String type, int alarms, List<String> sensors, String reason) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity);
            m.put("type", type);
            m.put("alarms", alarms);
            m.put("sensors", sensors);
            m.put("reason", reason);
            return m;
        }
    }

    private final IncidentAnalyzer analyzer;
    private final IncidentStore store;
    private final EventBus bus;

    public TriageAgent(IncidentAnalyzer analyzer, IncidentStore store, EventBus bus) {
        this.analyzer = analyzer;
        this.store = store;
        this.bus = bus;
    }

    /** 경보에서 시작하는 사건. 잘못된 요청(없는 경보 등)은 예외로 바로 돌려준다. */
    public Incident openAlarm(String seedAnomalyId, boolean replay, String runTag) {
        return open(analyzer.prepareAlarm(seedAnomalyId, replay, runTag));
    }

    /** 관찰 판단 뒤 새 경보 없이 다시 보는 사건. 상한·데이터 없음 등은 예외로 바로 돌려준다. */
    public Incident openRecheck(int parentAnalysisId, Long asOfShot) {
        return open(analyzer.prepareRecheck(parentAnalysisId, asOfShot));
    }

    private Incident open(IncidentAnalyzer.Job job) {
        Triage t = classify(job.trigger(), job.alarms());
        Incident i = new Incident(UUID.randomUUID().toString(), job, t);
        store.open(i);
        log.info("[분류] {} {} 경보 {}건 → {} · {} ({})", job.equipmentId(), job.trigger(), t.alarms(), t.severity(),
                t.type(), t.reason());
        bus.publish(AgentTopics.TRIAGED, new AgentEvent(i.id, 0, t.toMap()));
        return i;
    }

    static Triage classify(String trigger, List<Map<String, Object>> alarms) {
        Set<String> rules = new LinkedHashSet<>();
        Set<String> sensors = new LinkedHashSet<>();
        for (Map<String, Object> a : alarms) {
            Object r = a.get("rule_code");
            if (r != null) {
                rules.add(r.toString());
            }
            Object k = a.get("sensor_key");
            if (k != null && !"inspection".equals(k)) {
                sensors.add(k.toString());
            }
        }
        String severity;
        String type;
        if (rules.contains("P_CHART")) {
            severity = "HIGH";
            type = "QUALITY_IMPACT";
        } else if (rules.contains("XBAR")) {
            severity = "MEDIUM";
            type = "PROCESS_SHIFT";
        } else if (rules.contains("S_CHART")) {
            severity = "MEDIUM";
            type = "VARIATION";
        } else {
            severity = "LOW";
            type = "SPIKE";
        }
        String reason = "경보 규칙 " + rules;
        if (sensors.size() >= 2 && !"HIGH".equals(severity)) {
            severity = "LOW".equals(severity) ? "MEDIUM" : "HIGH";
            reason += ", 여러 센서 동시 경보 " + sensors;
        }
        if ("RECHECK".equals(trigger)) {
            reason += ", 관찰 뒤 재확인";
        }
        return new Triage(severity, type, alarms.size(), List.copyOf(sensors), reason);
    }
}
