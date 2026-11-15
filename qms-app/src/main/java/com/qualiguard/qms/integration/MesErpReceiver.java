package com.qualiguard.qms.integration;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.bridge.KafkaTopics;

import tools.jackson.databind.ObjectMapper;

/**
 * 가상 MES/ERP 수신 서비스 (F-09, ADR-010·012). 실제 MES/ERP 대신, 승인 이벤트를 받아 각 시스템이 만들 업무를 기록하고
 * 처리 결과를 응답 토픽(integration.ncr.ack)으로 돌려준다.
 *
 * - MES: 해당 설비의 점검 작업 지시 / ERP: 부적합 품질 비용 전표 (금액은 다루지 않음)
 * - 응답: 1 RECEIVED(수신 확인) → 2 PROCESSED(처리 완료, 작업 지시·전표 번호) 또는 FAILED(실패, 사유)
 * - 같은 이벤트를 다시 받으면 업무는 다시 만들지 않고 같은 응답(같은 response_id)을 다시 보낸다
 *   → 응답이 중간에 사라졌어도 이벤트 재전송으로 회복되고, QMS 는 response_id 로 중복을 거른다.
 * - qg.integration.simulate-failure=ERP 처럼 지정한 시스템은 실패 응답을 보낸다 (실패·재시도 흐름 시험용)
 */
@Component
public class MesErpReceiver {

    private static final Logger log = LoggerFactory.getLogger(MesErpReceiver.class);

    private static final String INSERT_RECEIPT = """
            INSERT INTO dbo.integration_receipt (event_id, system_name, ncr_no, action)
            SELECT ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM dbo.integration_receipt WHERE event_id = ? AND system_name = ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final Set<String> failingSystems;

    public MesErpReceiver(JdbcTemplate jdbc, ObjectMapper objectMapper, KafkaTemplate<String, String> kafka,
            @Value("${qg.integration.simulate-failure:}") String simulateFailure) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.failingSystems = Arrays.stream(simulateFailure.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).map(String::toUpperCase).collect(Collectors.toSet());
    }

    @KafkaListener(id = "qg-mes-erp-sim", groupId = "qg-mes-erp-sim", topics = KafkaTopics.QUALITY_NCR_APPROVED,
            autoStartup = "${qg.integration.receiver-enabled:true}")
    public void onApproved(String value) {
        receive(value);
    }

    /** 이벤트 1건을 처리하고, 새로 기록한 업무 수를 돌려준다 (중복이면 0). 응답은 매번 보낸다. */
    @SuppressWarnings("unchecked")
    int receive(String value) {
        Map<String, Object> e = objectMapper.readValue(value, Map.class);
        String eventId = (String) e.get("eventId");
        String ncrNo = (String) e.get("ncrNo");
        if (eventId == null || ncrNo == null) {
            log.warn("[가상 MES/ERP] eventId·ncrNo 가 없는 이벤트를 건너뜀: {}", value);
            return 0;
        }
        String mes = String.format("%s 점검 작업 지시 생성 — 원인 %s, 권고 조치: %s",
                e.get("equipmentId"), e.get("causeCode"), shorten((String) e.get("recommendedAction"), 200));
        String erp = String.format("부적합 %s 품질 비용 전표 대기 (불량 유형 %s, 승인 %s)",
                ncrNo, e.get("defectType") == null ? "-" : e.get("defectType"), e.get("approvedBy"));
        // 다시 보내기 이벤트는 대상 시스템(targetSystems)만 처리한다 — 이미 처리한 시스템에 같은 업무를 또 만들지 않게
        String targets = (String) e.get("targetSystems");
        boolean toMes = targets == null || targets.toUpperCase().contains("MES");
        boolean toErp = targets == null || targets.toUpperCase().contains("ERP");
        int rows = (toMes ? jdbc.update(INSERT_RECEIPT, eventId, "MES", ncrNo, shorten(mes, 300), eventId, "MES") : 0)
                + (toErp ? jdbc.update(INSERT_RECEIPT, eventId, "ERP", ncrNo, shorten(erp, 300), eventId, "ERP") : 0);
        if (rows > 0) {
            log.info("[가상 MES/ERP] {} 수신 → 업무 {}건 기록", ncrNo, rows);
        } else {
            log.info("[가상 MES/ERP] {} 이미 받은 이벤트 {} — 업무는 그대로, 응답만 다시 보냄", ncrNo, eventId);
        }
        if (toMes) {
            respond(eventId, ncrNo, "MES", e.get("equipmentId") == null ? "설비 정보가 없어 작업 지시를 만들 수 없음" : null);
        }
        if (toErp) {
            respond(eventId, ncrNo, "ERP", null);
        }
        return rows;
    }

    /** 수신 확인(1)과 처리 결과(2)를 보낸다. 같은 이벤트·시스템·단계면 response_id 가 같다. */
    private void respond(String eventId, String ncrNo, String system, String naturalFailure) {
        send(ack(eventId, ncrNo, system, "RECEIVED", 1, null, null));
        String failure = naturalFailure != null ? naturalFailure
                : failingSystems.contains(system) ? system + " 처리 실패 (시험용 실패 설정)" : null;
        if (failure != null) {
            send(ack(eventId, ncrNo, system, "FAILED", 2, null, failure));
        } else {
            String prefix = system.equals("MES") ? "MES-WO-" : "ERP-QC-";
            send(ack(eventId, ncrNo, system, "PROCESSED", 2,
                    prefix + eventId.replace("-", "").substring(0, 8).toUpperCase(), null));
        }
    }

    static Map<String, Object> ack(String eventId, String ncrNo, String system, String status, int seq,
            String referenceNo, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("responseId", UUID.nameUUIDFromBytes((eventId.toLowerCase() + "|" + system + "|" + seq)
                .getBytes(StandardCharsets.UTF_8)).toString());
        m.put("eventId", eventId);
        m.put("ncrNo", ncrNo);
        m.put("system", system);
        m.put("status", status);
        m.put("seq", seq);
        m.put("referenceNo", referenceNo);
        m.put("message", message);
        m.put("occurredAt", OffsetDateTime.now().toString());
        return m;
    }

    private void send(Map<String, Object> ack) {
        try {
            // key = 부적합 번호: 같은 부적합의 응답은 같은 파티션으로 (순서 보장의 1차 장치, 2차는 QMS 의 seq 비교)
            kafka.send(KafkaTopics.INTEGRATION_NCR_ACK, (String) ack.get("ncrNo"), objectMapper.writeValueAsString(ack))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 응답을 못 보내면 수신 처리 자체를 실패로 돌려, 이벤트가 다시 전달될 때 응답도 다시 보낸다
            throw new IllegalStateException("응답 전송 실패: " + ack.get("system") + " " + ack.get("status"), ex);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
