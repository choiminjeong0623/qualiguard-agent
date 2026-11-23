package com.qualiguard.qms.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 하루 토큰 예산 (로드맵 O5): 오늘 쓴 LLM 토큰이 상한을 넘으면 자동 분석을 멈춘다.
 *
 * - "오늘" 은 zone(기본 Asia/Seoul) 의 0시부터. agent_analysis 에 기록된 입력·출력·생각 토큰의 합으로 센다
 *   (분석·재분석·재확인 모두. 로컬 LLM 작성기와 임베딩은 클라우드 과금이 아니라 세지 않는다).
 * - 넘으면 AgentScheduler 가 새 사건을 넣지 않는다. 사람이 API·화면에서 직접 누르는 수동 분석은 막지 않는다
 *   (급한 경보를 사람이 판단해 돌릴 수 있게).
 * - 이미 진행 중인 사건은 끝까지 간다. 그래서 상한을 약간 넘을 수 있다 (사건 1건만큼).
 * - dailyTokens 가 0 이면 끔.
 */
public class TokenBudget {

    private static final Logger log = LoggerFactory.getLogger(TokenBudget.class);

    /** application.yml 의 qg.agent.budget. 값이 없으면 하루 100만 토큰, 서울 기준. */
    @ConfigurationProperties(prefix = "qg.agent.budget")
    public record Settings(Long dailyTokens, ZoneId zone) {

        public Settings {
            dailyTokens = dailyTokens == null ? 1_000_000L : Math.max(0, dailyTokens);
            zone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        }
    }

    /** 오늘 사용량. limit 이 0 이면 예산 끔 (exceeded 는 항상 false). */
    public record Status(LocalDate day, long limit, long used, boolean exceeded) {

        public long remaining() {
            return limit == 0 ? -1 : Math.max(0, limit - used);
        }
    }

    private final JdbcTemplate jdbc;
    private final Settings settings;
    private final Clock clock;
    /** 같은 날 경고 로그를 한 번만 남기려고 */
    private final AtomicReference<LocalDate> warnedDay = new AtomicReference<>();

    public TokenBudget(JdbcTemplate jdbc, Settings settings) {
        this(jdbc, settings, Clock.systemUTC());
    }

    /** 시험에서 "오늘" 을 고정하려고 시계를 받는다. */
    public TokenBudget(JdbcTemplate jdbc, Settings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    public Status status() {
        LocalDate today = LocalDate.now(clock.withZone(settings.zone()));
        if (settings.dailyTokens() == 0) {
            return new Status(today, 0, 0, false);
        }
        OffsetDateTime from = today.atStartOfDay(settings.zone()).toOffsetDateTime();
        Long used = jdbc.queryForObject("""
                SELECT ISNULL(SUM(CAST(ISNULL(prompt_tokens, 0) AS BIGINT) + ISNULL(output_tokens, 0)
                                  + ISNULL(thought_tokens, 0)), 0)
                FROM dbo.agent_analysis
                WHERE created_at >= CAST(? AS DATETIMEOFFSET(3))
                """, Long.class, from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        long u = used == null ? 0 : used;
        return new Status(today, settings.dailyTokens(), u, u >= settings.dailyTokens());
    }

    /** 자동 분석을 해도 되는지. 넘었으면 그날 처음 한 번만 경고를 남긴다. */
    public boolean allowsAuto() {
        Status s = status();
        if (!s.exceeded()) {
            return true;
        }
        if (!s.day().equals(warnedDay.getAndSet(s.day()))) {
            log.warn("[에이전트] 오늘({}) 토큰 예산 소진: {} / {} — 자동 분석을 내일 0시까지 멈춥니다 (수동 분석은 가능)",
                    s.day(), s.used(), s.limit());
        }
        return false;
    }
}
