package com.qualiguard.qms.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 담당자 모바일 알림: 문구, 방식별 본문, 실제 HTTP 전송, 꺼짐·실패 처리. */
class AlertNotifierTest {

    private final ObjectMapper om = JsonMapper.builder().build();
    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private AlertNotifier notifier(String mode, String url) {
        return new AlertNotifier(mode, url, "qg-alerts", "", "https://qms.example/", om);
    }

    @Test
    void 초안_알림_문구와_링크() {
        var n = AlertNotice.draft("IMM-01", "AI-2026-0118", "실린더 온도 지속 하강 (히터 열화 의심)", 88);
        assertThat(n.title()).isEqualTo("[QualiGuard] IMM-01 부적합 초안 검토 대기");
        assertThat(n.message()).isEqualTo("실린더 온도 지속 하강 (히터 열화 의심) · 확신도 88% · AI-2026-0118");
        assertThat(notifier("log", "").link(n)).isEqualTo("https://qms.example/qms/index.html#AI-2026-0118");
    }

    @Test
    void 판단_보류_알림은_사유를_한글로_우선순위를_높게() {
        var n = AlertNotice.hold("IMM-02", 1301, "RECHECK_LIMIT", "새 경보 없는 재확인 3회(상한 3) 뒤에도 원인을 정하지 못했습니다.");
        assertThat(n.title()).isEqualTo("[QualiGuard] IMM-02 판단 보류 · 사람 확인 필요");
        assertThat(n.message()).startsWith("재확인 상한 도달 · 새 경보 없는");
        assertThat(n.path()).isEqualTo("/qms/actions.html#hold/1301");
        assertThat(n.priority()).isEqualTo(5);
    }

    @Test
    void ntfy_로_JSON_을_보낸다() throws Exception {
        var nt = notifier("ntfy", startServer());
        assertThat(nt.send(AlertNotice.draft("IMM-01", "AI-2026-0118", "히터 열화 의심", 88))).isTrue();
        Map<?, ?> body = om.readValue(bodies.get(0), Map.class);
        assertThat(body.get("topic")).isEqualTo("qg-alerts");
        assertThat(body.get("title")).isEqualTo("[QualiGuard] IMM-01 부적합 초안 검토 대기");
        assertThat(body.get("click")).isEqualTo("https://qms.example/qms/index.html#AI-2026-0118");
        assertThat(body.get("priority")).isEqualTo(4);
        assertThat(nt.recent()).hasSize(1);
        assertThat(nt.recent().get(0).get("sent")).isEqualTo(true);
    }

    @Test
    void slack_은_text_한_줄_형식() throws Exception {
        var nt = notifier("slack", startServer());
        nt.send(AlertNotice.hold("IMM-02", 7, "VERIFY_WEAK", "근거 부족"));
        Map<?, ?> body = om.readValue(bodies.get(0), Map.class);
        assertThat(body).hasSize(1).containsKey("text");
        assertThat((String) body.get("text")).contains("판단 보류").contains("<https://qms.example/qms/actions.html#hold/7|QMS 에서 열기>");
    }

    @Test
    void 서버가_실패하면_false_를_돌려주고_예외를_던지지_않는다() throws Exception {
        status = 500;
        var nt = notifier("ntfy", startServer());
        assertThat(nt.send(AlertNotice.draft("IMM-01", "AI-1", "t", null))).isFalse();
        assertThat(nt.recent().get(0).get("sent")).isEqualTo(false);
    }

    @Test
    void 꺼져_있거나_주소가_없으면_보내지_않는다() {
        assertThat(notifier("off", "http://x").enabled()).isFalse();
        assertThat(notifier("ntfy", "").enabled()).isFalse();
        assertThat(notifier("log", "").enabled()).isTrue();
        notifier("off", "").notifyAsync(AlertNotice.draft("IMM-01", "AI-1", "t", 1));
    }
}
