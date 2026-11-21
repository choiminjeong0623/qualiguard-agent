package com.qualiguard.qms.agent.pipeline;

import java.util.List;

/**
 * 역할 분리형 멀티 에이전트의 Kafka 토픽 (ADR-016). 넘겨주는 지점마다 토픽 하나, 키는 사건 ID.
 *
 * <pre>
 * 분류 ──triaged──▶ 오케스트레이터 ──analysis.requested──▶ 원인 분석 ──analysis.completed──▶ 오케스트레이터
 *   ──verification.requested──▶ 검증 ──verification.completed──▶ 오케스트레이터 (약하면 analysis.requested 로 되돌림)
 *   ──report.requested──▶ 보고서 작성 ──report.completed──▶ 오케스트레이터 → 부적합 초안(사람 승인 대기)
 * </pre>
 */
public final class AgentTopics {

    public static final String TRIAGED = "agent.incident.triaged";
    public static final String ANALYSIS_REQUESTED = "agent.analysis.requested";
    public static final String ANALYSIS_COMPLETED = "agent.analysis.completed";
    public static final String VERIFICATION_REQUESTED = "agent.verification.requested";
    public static final String VERIFICATION_COMPLETED = "agent.verification.completed";
    public static final String ACTION_REQUESTED = "agent.action.requested";
    public static final String REPORT_REQUESTED = "agent.report.requested";
    public static final String REPORT_COMPLETED = "agent.report.completed";

    public static final List<String> ALL = List.of(TRIAGED, ANALYSIS_REQUESTED, ANALYSIS_COMPLETED,
            VERIFICATION_REQUESTED, VERIFICATION_COMPLETED, REPORT_REQUESTED, REPORT_COMPLETED, ACTION_REQUESTED);

    private AgentTopics() {
    }
}
