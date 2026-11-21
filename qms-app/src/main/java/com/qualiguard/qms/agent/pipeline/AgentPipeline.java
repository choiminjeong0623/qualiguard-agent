package com.qualiguard.qms.agent.pipeline;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.qualiguard.qms.agent.IncidentAnalyzer;

/**
 * 파이프라인 입구. 분류 에이전트로 사건을 열고, 필요하면 사람 승인 대기(또는 종결)까지 기다린다.
 * API·평가 스크립트는 결과를 바로 받아야 하므로 기다리고, 자동 분석(스케줄러)은 기다리지 않는다.
 */
public class AgentPipeline {

    private final TriageAgent triage;
    private final IncidentStore store;
    private final Duration waitTimeout;

    public AgentPipeline(TriageAgent triage, IncidentStore store, Duration waitTimeout) {
        this.triage = triage;
        this.store = store;
        this.waitTimeout = waitTimeout;
    }

    public Incident startAlarm(String seedAnomalyId, boolean replay, String runTag) {
        return triage.openAlarm(seedAnomalyId, replay, runTag);
    }

    public Incident startRecheck(int parentAnalysisId, Long asOfShot) {
        return triage.openRecheck(parentAnalysisId, asOfShot);
    }

    public Incident startRetry(int parentAnalysisId, String excludedCause) {
        return triage.openRetry(parentAnalysisId, excludedCause);
    }

    public IncidentAnalyzer.Outcome analyzeAndWait(String seedAnomalyId, boolean replay, String runTag) {
        return await(startAlarm(seedAnomalyId, replay, runTag));
    }

    public IncidentAnalyzer.Outcome recheckAndWait(int parentAnalysisId, Long asOfShot) {
        return await(startRecheck(parentAnalysisId, asOfShot));
    }

    public int inFlight() {
        return store.inFlight().size();
    }

    /** 기다리다 끝나지 않았거나 처리 중 예외가 난 경우 (요청 오류 400·409 와 구별). */
    public static class WaitException extends RuntimeException {
        public WaitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    IncidentAnalyzer.Outcome await(Incident i) {
        try {
            return i.done().get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new WaitException("사건 " + i.id() + " 이 " + waitTimeout.toSeconds() + "초 안에 끝나지 않았습니다 (상태 "
                    + i.state() + "). 파이프라인은 계속 진행합니다.", e);
        } catch (ExecutionException e) {
            throw new WaitException("사건 " + i.id() + " 처리 실패: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WaitException("기다리는 중 중단됨", e);
        }
    }
}
