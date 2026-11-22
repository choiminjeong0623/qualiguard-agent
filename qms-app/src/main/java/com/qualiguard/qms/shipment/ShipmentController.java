package com.qualiguard.qms.shipment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qualiguard.qms.security.AppUser;
import com.qualiguard.qms.shipment.SerialLinkRule.Candidate;

/**
 * 출하 확정 시리얼 연결 API (QMS 화면 "출하 시리얼").
 *
 *   GET  /api/qms/shipment/candidates?serial=TMP-…   임시 시리얼로 shot 후보 찾기
 *   POST /api/qms/shipment/confirm                   {confirmedSerial, provisionalSerial | messageId, shipmentNo}
 *   GET  /api/qms/shipment/links?top=30              최근 연결
 *   GET  /api/qms/shipment/trace/{confirmedSerial}   확정 시리얼 → shot 공정 값·그 무렵 경보·부적합
 * /api/qms/** 라서 조회는 REVIEWER·VIEWER, 확정은 REVIEWER 만 (SecurityConfig, CSRF 확인 포함).
 */
@RestController
@RequestMapping("/api/qms/shipment")
public class ShipmentController {

    private final ShipmentSerialService service;

    public ShipmentController(ShipmentSerialService service) {
        this.service = service;
    }

    public record ConfirmRequest(String confirmedSerial, String provisionalSerial, String messageId,
            String shipmentNo) {
    }

    @GetMapping("/candidates")
    public List<Candidate> candidates(@RequestParam String serial) {
        return service.candidatesBySerial(serial);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmRequest req, Authentication auth) {
        var r = service.confirm(req.confirmedSerial(), req.provisionalSerial(), req.messageId(), req.shipmentNo(),
                actor(auth));
        var d = r.decision();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", d.outcome().name());
        body.put("message", d.message());
        if (d.warning() != null) {
            body.put("warning", d.warning());
        }
        if (r.linkId() != null) {
            body.put("linkId", r.linkId());
            body.put("shot", d.chosen());
        } else {
            body.put("candidates", d.candidates());
        }
        int status = switch (d.outcome()) {
            case LINK -> 200;
            case AMBIGUOUS, REJECTED -> 409;
            case NOT_FOUND -> 404;
            case BAD_REQUEST -> 400;
        };
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/links")
    public List<Map<String, Object>> links(@RequestParam(defaultValue = "30") int top) {
        return service.recentLinks(Math.min(Math.max(top, 1), 200));
    }

    @GetMapping("/trace/{confirmedSerial}")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String confirmedSerial) {
        return service.trace(confirmedSerial).map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "연결된 확정 시리얼이 없습니다")));
    }

    private static String actor(Authentication auth) {
        if (auth == null) {
            return "unknown";
        }
        return auth.getPrincipal() instanceof AppUser u ? u.getDisplayName() : auth.getName();
    }
}
