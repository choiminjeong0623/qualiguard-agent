package com.qualiguard.qms.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 호출 보호 (로드맵 O4): 시간 제한 + 일시 장애일 때만 재시도 + 끝내 실패하면 예외.
 *
 * - 시간 제한: 호출 1회가 timeout 을 넘으면 끊는다 (도구 호출을 포함한 조사 전체가 한 번의 호출).
 * - 재시도: 시간 초과, 서버 오류(5xx), 요청 한도(429), 네트워크 오류처럼 다시 하면 나을 수 있는 경우만,
 *   backoff → backoff×2 … 로 간격을 늘려 maxRetries 회까지. 잘못된 키(401·403)·잘못된 요청(400)·응답 형식 오류는
 *   다시 해도 같으므로 바로 실패한다.
 * - 끝내 실패하면 LlmUnavailableException. 호출한 쪽(오케스트레이터)이 FAILED 로 기록하고 경보를 NEW 로 되돌려
 *   나중에 다시 분석할 수 있게 한다 (IncidentAnalyzer.fail).
 */
public class LlmCallPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmCallPolicy.class);

    /** application.yml 의 qg.agent.llm. 값이 없으면 90초, 재시도 1회, 첫 대기 2초. */
    @ConfigurationProperties(prefix = "qg.agent.llm")
    public record Settings(Duration timeout, Integer maxRetries, Duration backoff) {

        public Settings {
            timeout = timeout == null ? Duration.ofSeconds(90) : timeout;
            maxRetries = maxRetries == null ? 1 : Math.max(0, maxRetries);
            backoff = backoff == null ? Duration.ofSeconds(2) : backoff;
        }

        public static Settings defaults() {
            return new Settings(null, null, null);
        }
    }

    /** 재시도까지 모두 실패했을 때. */
    public static class LlmUnavailableException extends RuntimeException {

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 시험에서 대기 시간을 없애려고 바꿔 끼운다. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final Settings settings;
    private final Sleeper sleeper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmCallPolicy(Settings settings) {
        this(settings, d -> Thread.sleep(d.toMillis()));
    }

    LlmCallPolicy(Settings settings, Sleeper sleeper) {
        this.settings = settings;
        this.sleeper = sleeper;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * @param label 로그·trace 에 남길 이름 (예: "analysis", "repair")
     * @param trace 재시도·시간 초과를 남길 도구 호출 기록 (null 이면 남기지 않음)
     */
    public <T> T call(String label, Supplier<T> call, List<String> trace) {
        int attempts = settings.maxRetries() + 1;
        Duration wait = settings.backoff();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return once(call);
            } catch (RuntimeException e) {
                last = e;
                boolean retry = attempt < attempts && retryable(e);
                String reason = reason(e);
                if (trace != null) {
                    trace.add("llmCall(" + label + ", 시도 " + attempt + "/" + attempts + ") 실패: " + reason
                            + (retry ? " → " + wait.toMillis() + "ms 뒤 재시도" : ""));
                }
                if (!retry) {
                    break;
                }
                log.warn("[LLM] {} 호출 실패({}/{}): {} — {}ms 뒤 재시도", label, attempt, attempts, reason,
                        wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                wait = wait.multipliedBy(2);
            }
        }
        throw new LlmUnavailableException("LLM " + label + " 호출 실패: " + reason(last), last);
    }

    private <T> T once(Supplier<T> call) {
        Future<T> f = executor.submit(call::get);
        try {
            return f.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new LlmTimeoutException("응답 시간 초과(" + settings.timeout().toSeconds() + "초)", e);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 호출 대기 중 중단됨", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(c);
        }
    }

    static class LlmTimeoutException extends RuntimeException {
        LlmTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 다시 하면 나을 수 있는 장애인지. 원인 사슬 전체를 본다. */
    static boolean retryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LlmTimeoutException || t instanceof IOException) {
                return true;
            }
            String name = t.getClass().getName();
            if (name.equals("org.springframework.ai.retry.NonTransientAiException")) {
                return false;
            }
            if (name.equals("org.springframework.ai.retry.TransientAiException")
                    || name.equals("com.google.genai.errors.ServerException")
                    || name.equals("com.google.genai.errors.GenAiIOException")
                    || name.equals("org.springframework.web.client.ResourceAccessException")) {
                return true;
            }
            if (t instanceof com.google.genai.errors.ApiException api) {
                int code = api.code();
                return code == 408 || code == 429 || code >= 500;
            }
        }
        return false;
    }

    private static String reason(Throwable e) {
        if (e == null) {
            return "알 수 없음";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 200 ? m.substring(0, 200) : m));
    }
}
