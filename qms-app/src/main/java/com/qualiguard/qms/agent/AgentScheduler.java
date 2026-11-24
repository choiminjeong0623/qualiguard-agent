package com.qualiguard.qms.agent;

import com.qualiguard.qms.agent.action.EffectCheckScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.agent.pipeline.AgentPipeline;

/**
 * qg.agent.auto-enabled=true 일 때, 주기마다 미처리 경보 사건을 1건씩 파이프라인에 넣는다 (기다리지 않는다).
 * 미처리 경보가 없으면, 새 경보 없이 recheck-shots 가 지난 관찰 판단을 1건 재확인한다.
 * 진행 중인 사건이 있으면 넣지 않는다: LLM 호출을 한 번에 하나로 (M1 8GB·비용 상한).
 * 조치 효과가 없어 재분석이 자리를 기다리는 중이면 새 경보를 넣지 않는다 (재분석이 밀린 경보 뒤로 계속 밀리지 않게).
 * 오늘 토큰 예산을 다 썼으면 넣지 않는다 (로드맵 O5, TokenBudget). 수동 분석 API 는 이 검사를 거치지 않는다.
 */
@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final IncidentAnalyzer analyzer;
    private final AgentPipeline pipeline;
    private final AgentProperties props;
    private final TokenBudget budget;
    private final ObjectProvider<EffectCheckScheduler> effectCheck;

    public AgentScheduler(IncidentAnalyzer analyzer, AgentPipeline pipeline, AgentProperties props,
            TokenBudget budget, ObjectProvider<EffectCheckScheduler> effectCheck) {
        this.analyzer = analyzer;
        this.pipeline = pipeline;
        this.props = props;
        this.budget = budget;
        this.effectCheck = effectCheck;
    }

    @Scheduled(fixedDelayString = "${qg.agent.poll-ms:5000}", initialDelayString = "${qg.agent.poll-ms:5000}")
    public void poll() {
        if (!props.autoEnabled() || pipeline.inFlight() > 0) {
            return;
        }
        EffectCheckScheduler check = effectCheck.getIfAvailable();
        if (check != null && check.retryWaiting()) {
            return;   // 조치 효과 확인의 재분석이 먼저 (자동 조치 켰을 때만 있는 빈)
        }
        try {
            if (!budget.allowsAuto()) {
                return;
            }
            var seed = analyzer.nextSeed();
            if (seed.isPresent()) {
                pipeline.startAlarm(seed.get(), false, null);
                return;
            }
            analyzer.nextRecheckDue().ifPresent(id -> pipeline.startRecheck(id, null));
        } catch (RuntimeException e) {
            log.error("[에이전트] 자동 분석 중 오류: {}", e.toString());
        }
    }
}
