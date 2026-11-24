package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import com.qualiguard.qms.agent.action.EffectCheckScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qualiguard.qms.agent.pipeline.AgentPipeline;

/** 하루 토큰 예산 (로드맵 O5): 서울 0시 기준으로 세고, 넘으면 자동 분석만 멈춘다. SQL 자체는 컨테이너 시험에서 확인. */
class TokenBudgetTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 서울 2026-11-23 00:30 (UTC 로는 전날 15:30) */
    private static final Clock JUST_AFTER_MIDNIGHT = Clock.fixed(Instant.parse("2026-11-22T15:30:00Z"), ZoneOffset.UTC);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private TokenBudget budget(long limit, long usedToday) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(usedToday);
        return new TokenBudget(jdbc, new TokenBudget.Settings(limit, SEOUL), JUST_AFTER_MIDNIGHT);
    }

    @Test
    void 오늘은_서울_0시부터_센다() {
        TokenBudget b = budget(1_000_000, 10);
        TokenBudget.Status s = b.status();
        assertThat(s.day()).hasToString("2026-11-23");   // UTC 로는 아직 22일이지만 서울은 23일
        verify(jdbc).queryForObject(anyString(), eq(Long.class), eq("2026-11-23T00:00:00+09:00"));
    }

    @Test
    void 예산_안이면_자동_분석_가능() {
        TokenBudget b = budget(1_000_000, 999_999);
        assertThat(b.allowsAuto()).isTrue();
        assertThat(b.status().remaining()).isEqualTo(1);
    }

    @Test
    void 예산에_닿으면_자동_분석_중지() {
        TokenBudget b = budget(1_000_000, 1_000_000);
        assertThat(b.allowsAuto()).isFalse();
        assertThat(b.status().exceeded()).isTrue();
        assertThat(b.status().remaining()).isZero();
    }

    @Test
    void 영이면_예산을_끄고_DB_도_보지_않는다() {
        TokenBudget b = new TokenBudget(jdbc, new TokenBudget.Settings(0L, SEOUL), JUST_AFTER_MIDNIGHT);
        assertThat(b.allowsAuto()).isTrue();
        assertThat(b.status().remaining()).isEqualTo(-1);
        verifyNoInteractions(jdbc);
    }

    @Test
    void 설정이_없으면_하루_100만_토큰_서울() {
        TokenBudget.Settings s = new TokenBudget.Settings(null, null);
        assertThat(s.dailyTokens()).isEqualTo(1_000_000L);
        assertThat(s.zone()).isEqualTo(SEOUL);
    }

    @Test
    void 스케줄러는_예산을_넘으면_새_사건을_넣지_않는다() {
        IncidentAnalyzer analyzer = mock(IncidentAnalyzer.class);
        AgentPipeline pipeline = mock(AgentPipeline.class);
        when(analyzer.nextSeed()).thenReturn(Optional.of("a-1"));
        AgentProperties props = new AgentProperties(true, 5000, 100, 30, 60, 40, 3, 1);

        new AgentScheduler(analyzer, pipeline, props, budget(100, 100), noEffectCheck()).poll();
        verify(pipeline, never()).startAlarm(anyString(), anyBoolean(), any());

        new AgentScheduler(analyzer, pipeline, props, budget(100, 99), noEffectCheck()).poll();
        verify(pipeline).startAlarm("a-1", false, null);
    }

    @Test
    void 조치_재분석이_자리를_기다리면_새_경보를_넣지_않는다() {
        IncidentAnalyzer analyzer = mock(IncidentAnalyzer.class);
        AgentPipeline pipeline = mock(AgentPipeline.class);
        when(analyzer.nextSeed()).thenReturn(Optional.of("a-1"));
        AgentProperties props = new AgentProperties(true, 5000, 100, 30, 60, 40, 3, 1);
        EffectCheckScheduler check = mock(EffectCheckScheduler.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("effectCheck", check);

        when(check.retryWaiting()).thenReturn(true);
        new AgentScheduler(analyzer, pipeline, props, budget(100, 0), beans.getBeanProvider(EffectCheckScheduler.class)).poll();
        verify(pipeline, never()).startAlarm(anyString(), anyBoolean(), any());

        when(check.retryWaiting()).thenReturn(false);
        new AgentScheduler(analyzer, pipeline, props, budget(100, 0), beans.getBeanProvider(EffectCheckScheduler.class)).poll();
        verify(pipeline).startAlarm("a-1", false, null);
    }

    private static ObjectProvider<EffectCheckScheduler> noEffectCheck() {
        return new StaticListableBeanFactory().getBeanProvider(EffectCheckScheduler.class);
    }
}
