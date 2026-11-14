package com.qualiguard.qms.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** qg.agent.auto-enabled=true 일 때, 주기마다 미처리 경보 사건을 1건씩 자동 분석한다. */
@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final IncidentAnalyzer analyzer;
    private final AgentProperties props;

    public AgentScheduler(IncidentAnalyzer analyzer, AgentProperties props) {
        this.analyzer = analyzer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${qg.agent.poll-ms:5000}", initialDelayString = "${qg.agent.poll-ms:5000}")
    public void poll() {
        if (!props.autoEnabled()) {
            return;
        }
        try {
            analyzer.analyzeNext();
        } catch (RuntimeException e) {
            log.error("[에이전트] 자동 분석 중 오류: {}", e.toString());
        }
    }
}
