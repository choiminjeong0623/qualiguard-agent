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
 * 가상 MES/ERP 수신 서비스 (F-09). 실제 MES/ERP 대신, 승인 이벤트를 받아 각 시스템이 만들 업무를 기록한다.
 *
 * - MES: 해당 설비의 점검 작업 지시
 * - ERP: 부적합 품질 비용 전표 (금액은 다루지 않음, 전표 대기만)
 *
 * 같은 이벤트를 두 번 받아도(at-least-once 재전송) event_id + 시스템 고유 제약과 NOT EXISTS 로 한 번만 기록한다.
 * 별도 소비자 그룹이라 DB 적재 소비자와 서로 영향을 주지 않는다.
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

    public MesErpReceiver(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "qg-mes-erp-sim", groupId = "qg-mes-erp-sim", topics = KafkaTopics.QUALITY_NCR_APPROVED,
            autoStartup = "${qg.integration.receiver-enabled:true}")
    public void onApproved(String value) {
        receive(value);
    }

    /** 이벤트 1건을 처리하고, 새로 기록한 업무 수를 돌려준다 (중복이면 0). */
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
        int rows = jdbc.update(INSERT_RECEIPT, eventId, "MES", ncrNo, shorten(mes, 300), eventId, "MES")
                + jdbc.update(INSERT_RECEIPT, eventId, "ERP", ncrNo, shorten(erp, 300), eventId, "ERP");
        if (rows > 0) {
            log.info("[가상 MES/ERP] {} 수신 → 업무 {}건 기록", ncrNo, rows);
        } else {
            log.info("[가상 MES/ERP] {} 이미 받은 이벤트 {} — 건너뜀", ncrNo, eventId);
        }
        return rows;
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
