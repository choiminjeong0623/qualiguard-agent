package com.qualiguard.qms.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 담당자 모바일 경보 알림: 부적합 초안이 새로 생기거나 판단 보류가 걸리면 웹훅으로 알린다.
 *
 * 방식(qg.notify.mode)
 *  - off   : 보내지 않음 (기본)
 *  - log   : 로그로만 남김 (시험·시연)
 *  - ntfy  : ntfy 서버에 JSON 발행 → ntfy 앱(안드로이드·iOS)이 휴대폰 푸시로 띄운다.
 *            ntfy 는 직접 띄울 수 있어 폐쇄망에서도 공장 안 서버로 쓸 수 있다.
 *  - slack : Slack(또는 호환) Incoming Webhook 으로 {"text": ...}
 *
 * 알림은 분석 결과 저장(트랜잭션)이 끝난 뒤 별도 스레드에서 보내므로, 알림 서버가 느리거나
 * 꺼져 있어도 분석·저장에는 영향이 없다. 실패는 경고 로그만 남긴다.
 */
@Component
public final class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final int RECENT_MAX = 20;

    private final String mode;
    private final String url;
    private final String topic;
    private final String token;
    private final String qmsBaseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final ExecutorService executor;
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public AlertNotifier(@Value("${qg.notify.mode:off}") String mode,
            @Value("${qg.notify.url:}") String url,
            @Value("${qg.notify.topic:}") String topic,
            @Value("${qg.notify.token:}") String token,
            @Value("${qg.notify.qms-base-url:http://localhost:8080}") String qmsBaseUrl,
            ObjectMapper objectMapper) {
        this.mode = mode == null ? "off" : mode.trim().toLowerCase(Locale.ROOT);
        this.url = url == null ? "" : url.trim();
        this.topic = topic == null ? "" : topic.trim();
        this.token = token == null ? "" : token.trim();
        this.qmsBaseUrl = qmsBaseUrl == null ? "" : qmsBaseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "qg-notify");
            t.setDaemon(true);
            return t;
        });
        if (needsUrl() && this.url.isEmpty()) {
            log.warn("[알림] qg.notify.mode={} 인데 qg.notify.url 이 비어 있어 알림을 보내지 않습니다.", this.mode);
        }
    }

    public String mode() {
        return mode;
    }

    public boolean enabled() {
        return !mode.equals("off") && (!needsUrl() || !url.isEmpty());
    }

    private boolean needsUrl() {
        return mode.equals("ntfy") || mode.equals("slack");
    }

    /** 비동기로 보낸다. 꺼져 있으면 아무것도 하지 않는다. */
    public void notifyAsync(AlertNotice n) {
        if (!enabled() || n == null) {
            return;
        }
        executor.execute(() -> send(n));
    }

    /** 실제 전송 (시험에서 직접 부른다). 성공하면 true. */
    boolean send(AlertNotice n) {
        boolean ok;
        String error = null;
        try {
            ok = switch (mode) {
                case "log" -> {
                    log.info("[알림] {} — {} ({})", n.title(), n.message(), link(n));
                    yield true;
                }
                case "ntfy", "slack" -> post(n);
                default -> false;
            };
        } catch (Exception ex) {
            ok = false;
            error = ex.toString();
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        if (!ok) {
            log.warn("[알림] 보내지 못함 ({}): {} {}", mode, n.title(), error == null ? "" : error);
        }
        remember(n, ok);
        return ok;
    }

    private boolean post(AlertNotice n) throws Exception {
        String body = objectMapper.writeValueAsString(payload(n));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!token.isEmpty()) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return true;
    }

    /** 방식별 본문. ntfy JSON 발행 형식 / Slack Incoming Webhook 형식. */
    Map<String, Object> payload(AlertNotice n) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (mode.equals("slack")) {
            p.put("text", "*" + n.title() + "*\n" + n.message() + "\n<" + link(n) + "|QMS 에서 열기>");
            return p;
        }
        p.put("topic", topic);
        p.put("title", n.title());
        p.put("message", n.message());
        p.put("priority", n.priority());
        p.put("tags", List.of(AlertNotice.HOLD.equals(n.kind()) ? "warning" : "rotating_light", n.equipmentId()));
        p.put("click", link(n));
        p.put("actions", List.of(Map.of("action", "view", "label", "QMS 에서 열기", "url", link(n))));
        return p;
    }

    String link(AlertNotice n) {
        return qmsBaseUrl + n.path();
    }

    private synchronized void remember(AlertNotice n, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", java.time.OffsetDateTime.now().toString());
        m.put("kind", n.kind());
        m.put("equipmentId", n.equipmentId());
        m.put("title", n.title());
        m.put("message", n.message());
        m.put("link", link(n));
        m.put("sent", ok);
        recent.addFirst(m);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }

    /** 최근 보낸 알림 (GET /api/qms/notifications). */
    public synchronized List<Map<String, Object>> recent() {
        return new ArrayList<>(recent);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
