package com.qualiguard.qms.ncr;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QMS 부적합 검토 API (F-08). 화면: /qms/index.html
 *
 *   GET  /api/qms/ncrs?status=DRAFT&equipmentId=IMM-01   목록
 *   GET  /api/qms/ncrs/{ncrNo}                            상세 (경보·에이전트 분석·검토 이력 포함)
 *   PUT  /api/qms/ncrs/{ncrNo}                            초안 수정  {"actor": "...", "fields": {...}}
 *   POST /api/qms/ncrs/{ncrNo}/approve                    승인      {"actor": "...", "comment": "..."}
 *   POST /api/qms/ncrs/{ncrNo}/reject                     반려      {"actor": "...", "reason": "..."}
 *
 * 로그인은 없다(로컬 시연용). 검토자 이름은 요청마다 받아 이력에 남긴다.
 */
@RestController
@RequestMapping("/api/qms/ncrs")
public class NcrController {

    private final NcrReviewService service;

    public NcrController(NcrReviewService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String equipmentId, @RequestParam(defaultValue = "100") int top) {
        return service.list(status, equipmentId, top);
    }

    @GetMapping("/{ncrNo}")
    public Map<String, Object> detail(@PathVariable String ncrNo) {
        return service.detail(ncrNo);
    }

    @PutMapping("/{ncrNo}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> update(@PathVariable String ncrNo, @RequestBody Map<String, Object> body) {
        Object fields = body.get("fields");
        return service.update(ncrNo, (String) body.get("actor"),
                fields instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
    }

    @PostMapping("/{ncrNo}/approve")
    public Map<String, Object> approve(@PathVariable String ncrNo, @RequestBody Map<String, String> body) {
        return service.approve(ncrNo, body.get("actor"), body.get("comment"));
    }

    @PostMapping("/{ncrNo}/reject")
    public Map<String, Object> reject(@PathVariable String ncrNo, @RequestBody Map<String, String> body) {
        return service.reject(ncrNo, body.get("actor"), body.get("reason"));
    }

    @ExceptionHandler(NcrReviewService.ReviewException.class)
    public ResponseEntity<Map<String, String>> handle(NcrReviewService.ReviewException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
