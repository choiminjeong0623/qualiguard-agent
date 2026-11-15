package com.qualiguard.qms.ncr;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qualiguard.qms.security.AppUser;

/**
 * QMS 부적합 검토 API (F-08). 화면: /qms/index.html
 *
 *   GET  /api/qms/ncrs?status=DRAFT&equipmentId=IMM-01   목록
 *   GET  /api/qms/ncrs/{ncrNo}                            상세 (경보·에이전트 분석·검토 이력 포함)
 *   PUT  /api/qms/ncrs/{ncrNo}                            초안 수정  {"fields": {...}}
 *   POST /api/qms/ncrs/{ncrNo}/approve                    승인      {"comment": "..."}
 *   POST /api/qms/ncrs/{ncrNo}/reject                     반려      {"reason": "..."}
 *   POST /api/qms/ncrs/{ncrNo}/close                      조치 완료 {"actionTaken": "..."}
 *   GET  /api/qms/ncrs/knowledge                          지식저장소 현황 (유사 사례 검색 대상)
 *   POST /api/qms/ncrs/{ncrNo}/integration/retry          MES/ERP 연계 다시 보내기 (최근 연계에 실패가 있을 때)
 *   GET  /api/qms/me                                      로그인 사용자 (이름·역할)
 *
 * 검토 이력의 검토자는 로그인한 사용자다 (요청 본문의 이름은 쓰지 않는다). 권한은 SecurityConfig (ADR-011).
 */
@RestController
@RequestMapping("/api/qms")
public class NcrController {

    private final NcrReviewService service;

    public NcrController(NcrReviewService service) {
        this.service = service;
    }

    /** 검토 이력에 남길 이름: 로그인 사용자의 표시 이름 (없으면 사용자 이름). */
    static String actor(Authentication auth) {
        if (auth == null) {
            return null;
        }
        return auth.getPrincipal() instanceof AppUser u ? u.getDisplayName() : auth.getName();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        String role = auth.getPrincipal() instanceof AppUser u ? u.getRole()
                : auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).findFirst().orElse("");
        return Map.of("username", auth.getName(), "displayName", actor(auth), "role", role);
    }

    @GetMapping("/ncrs")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String equipmentId, @RequestParam(defaultValue = "100") int top) {
        return service.list(status, equipmentId, top);
    }

    @GetMapping("/ncrs/knowledge")
    public List<Map<String, Object>> knowledge() {
        return service.knowledgeStats();
    }

    @GetMapping("/ncrs/{ncrNo}")
    public Map<String, Object> detail(@PathVariable String ncrNo) {
        return service.detail(ncrNo);
    }

    @PutMapping("/ncrs/{ncrNo}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> update(@PathVariable String ncrNo, @RequestBody Map<String, Object> body,
            Authentication auth) {
        Object fields = body.get("fields");
        return service.update(ncrNo, actor(auth),
                fields instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
    }

    @PostMapping("/ncrs/{ncrNo}/approve")
    public Map<String, Object> approve(@PathVariable String ncrNo, @RequestBody Map<String, String> body,
            Authentication auth) {
        return service.approve(ncrNo, actor(auth), body.get("comment"));
    }

    @PostMapping("/ncrs/{ncrNo}/reject")
    public Map<String, Object> reject(@PathVariable String ncrNo, @RequestBody Map<String, String> body,
            Authentication auth) {
        return service.reject(ncrNo, actor(auth), body.get("reason"));
    }

    @PostMapping("/ncrs/{ncrNo}/close")
    public Map<String, Object> close(@PathVariable String ncrNo, @RequestBody Map<String, String> body,
            Authentication auth) {
        return service.close(ncrNo, actor(auth), body.get("actionTaken"));
    }

    @PostMapping("/ncrs/{ncrNo}/integration/retry")
    public Map<String, Object> retryIntegration(@PathVariable String ncrNo, Authentication auth) {
        return service.retryIntegration(ncrNo, actor(auth));
    }

    @ExceptionHandler(NcrReviewService.ReviewException.class)
    public ResponseEntity<Map<String, String>> handle(NcrReviewService.ReviewException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
