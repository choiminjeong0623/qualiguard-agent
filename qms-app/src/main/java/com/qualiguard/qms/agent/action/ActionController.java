package com.qualiguard.qms.agent.action;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qualiguard.qms.security.AppUser;

/**
 * 조치 조회·승인 API (QMS 화면 "조치" 탭).
 *
 *   GET  /api/qms/actions?top=30            최근 조치 (자동 실행·승인 대기·효과 검증 결과)
 *   POST /api/qms/actions/{id}/approve      승인 대기 조치를 사람이 승인 → 실행, 효과 검증 대상이 됨
 *   POST /api/qms/actions/{id}/reject       반려
 * /api/qms/** 라서 조회는 REVIEWER·VIEWER, 승인·반려는 REVIEWER 만 (SecurityConfig, CSRF 확인 포함).
 */
@RestController
@RequestMapping("/api/qms/actions")
public class ActionController {

    private final ActionStore store;

    public ActionController(ActionStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "30") int top) {
        return store.recent(Math.min(top, 200));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable int id, Authentication auth) {
        if (!store.isWaiting(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "승인 대기 중인 조치가 아닙니다"));
        }
        store.markExecuted(id, actor(auth));
        return ResponseEntity.ok(Map.of("actionId", id, "status", "EXECUTED"));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable int id, Authentication auth) {
        if (!store.reject(id, actor(auth))) {
            return ResponseEntity.status(409).body(Map.of("error", "승인 대기 중인 조치가 아닙니다"));
        }
        return ResponseEntity.ok(Map.of("actionId", id, "status", "REJECTED"));
    }

    private static String actor(Authentication auth) {
        if (auth == null) {
            return "unknown";
        }
        return auth.getPrincipal() instanceof AppUser u ? u.getDisplayName() : auth.getName();
    }
}
