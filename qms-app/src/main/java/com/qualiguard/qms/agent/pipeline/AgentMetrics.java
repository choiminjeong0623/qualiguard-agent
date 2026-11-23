package com.qualiguard.qms.agent.pipeline;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 에이전트 운영 지표 (로드맵 O2). /actuator/metrics/qg.agent.stage 등으로 본다 (API 토큰 또는 검토자 로그인).
 *
 *   qg.agent.stage     Timer   단계별 머문 시간. 태그 stage = TRIAGED·ANALYZING·VERIFYING·WRITING
 *                              (검증에서 되돌리면 ANALYZING·VERIFYING 이 한 사건에서 두 번 잡힌다)
 *   qg.agent.incident  Timer   사건 하나 전체 시간. 태그 outcome = AWAITING_APPROVAL·CLOSED·FAILED
 *   qg.agent.result    Counter 사건 결과. 태그 decision(NCR_DRAFT·OBSERVE·SENSOR_CHECK·FAILED), verify(PASS·FAIL·-),
 *                              outcome, retried(검증 되돌림이 있었는지 true/false)
 *
 * 퍼센타일(P50·P95)은 Timer 에 함께 낸다. registry 가 null 이면 아무것도 하지 않는다 (단위 시험).
 */
public class AgentMetrics {

    public static final String STAGE = "qg.agent.stage";
    public static final String INCIDENT = "qg.agent.incident";
    public static final String RESULT = "qg.agent.result";

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static AgentMetrics none() {
        return new AgentMetrics(null);
    }

    void stage(String stage, long ms) {
        if (registry == null || ms < 0) {
            return;
        }
        timer(STAGE, "에이전트 파이프라인 단계별 처리 시간", "stage", stage).record(Duration.ofMillis(ms));
    }

    void result(String decision, String verify, String outcome, boolean retried, long totalMs) {
        if (registry == null) {
            return;
        }
        timer(INCIDENT, "사건 하나를 끝내기까지 걸린 시간", "outcome", outcome).record(Duration.ofMillis(totalMs));
        Counter.builder(RESULT).description("에이전트 사건 결과")
                .tag("decision", decision == null ? "-" : decision)
                .tag("verify", verify == null ? "-" : verify)
                .tag("outcome", outcome)
                .tag("retried", String.valueOf(retried))
                .register(registry).increment();
    }

    private Timer timer(String name, String description, String tagKey, String tagValue) {
        return Timer.builder(name).description(description).tag(tagKey, tagValue)
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }
}
