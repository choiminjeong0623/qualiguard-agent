package com.qualiguard.qms.notify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모바일 경보 알림 확인용 API.
 *
 *   GET  /api/qms/notifications       설정 방식과 최근 보낸 알림 20건
 *   POST /api/qms/notifications/test  시험 알림 1건 (휴대폰에 오는지 확인, REVIEWER 만)
 */
@RestController
@RequestMapping("/api/qms/notifications")
public class NotificationController {

    private final AlertNotifier notifier;

    public NotificationController(AlertNotifier notifier) {
        this.notifier = notifier;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", notifier.mode());
        m.put("enabled", notifier.enabled());
        m.put("recent", notifier.recent());
        return m;
    }

    @PostMapping("/test")
    public ResponseEntity<?> test() {
        if (!notifier.enabled()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "알림이 꺼져 있습니다. qg.notify.mode 를 ntfy·slack·log 중 하나로, url 을 채워 주세요."));
        }
        boolean ok = notifier.send(new AlertNotice("TEST", "TEST", "[QualiGuard] 시험 알림",
                "이 알림이 보이면 경보 알림 설정이 맞습니다.", "/qms/index.html", 3));
        return ok ? ResponseEntity.ok(Map.of("sent", true))
                : ResponseEntity.status(502).body(Map.of("sent", false, "message", "알림 서버로 보내지 못했습니다. 로그를 확인해 주세요."));
    }
}
