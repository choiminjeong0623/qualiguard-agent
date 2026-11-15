package com.qualiguard.qms.integration;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.bridge.KafkaTopics;

import tools.jackson.databind.ObjectMapper;

/**
 * QMS 쪽 응답 수신 (ADR-012): 가상 MES/ERP 의 처리 결과를 받아 연계 상태(integration_status)를 갱신한다.
 * 중복(같은 response_id)은 무시하고, 늦게 도착한 낮은 단계 응답은 상태를 되돌리지 않는다 (usp_ApplyIntegrationAck).
 */
@Component
public class IntegrationAckListener {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAckListener.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IntegrationAckListener(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "qg-qms-ack", groupId = "qg-qms-ack", topics = KafkaTopics.INTEGRATION_NCR_ACK,
            autoStartup = "${qg.integration.ack-enabled:true}")
    public void onAck(String value) {
        apply(value);
    }

    /** 응답 1건 반영. 결과: APPLIED(상태 바뀜) / STALE(늦게 온 낮은 단계) / DUPLICATE(이미 받음) / INVALID */
    @SuppressWarnings("unchecked")
    String apply(String value) {
        Map<String, Object> a = objectMapper.readValue(value, Map.class);
        Object seq = a.get("seq");
        if (a.get("responseId") == null || a.get("eventId") == null || a.get("system") == null
                || a.get("status") == null || !(seq instanceof Number)) {
            log.warn("[연계 응답] 형식이 맞지 않는 응답을 건너뜀: {}", value);
            return "INVALID";
        }
        String result = jdbc.queryForObject("EXEC dbo.usp_ApplyIntegrationAck ?, ?, ?, ?, ?, ?, ?, ?", String.class,
                a.get("responseId"), a.get("eventId"), a.get("system"), a.get("status"), ((Number) seq).intValue(),
                a.get("referenceNo"), a.get("message"), value);
        log.info("[연계 응답] {} {} {} → {}", a.get("ncrNo"), a.get("system"), a.get("status"), result);
        return result;
    }
}
