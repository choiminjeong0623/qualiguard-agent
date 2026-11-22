package com.qualiguard.qms.shipment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.qualiguard.qms.shipment.SerialLinkRule.Candidate;
import com.qualiguard.qms.shipment.SerialLinkRule.Decision;
import com.qualiguard.qms.shipment.SerialLinkRule.Outcome;

/**
 * 출하 확정 시리얼 연결 (ADR-017 의 "향후", ADR-019).
 *
 * 공정 중 시리얼(process_shot.serial_no)은 고유하지 않다. 출하 확정 때 확정 시리얼 ↔ shot 을
 * dbo.shipment_serial_link 에 한 줄 넣고, 이때만 고유성을 강제한다(확정 시리얼 UNIQUE, message_id UNIQUE).
 * 연결해 두면 나중에 확정 시리얼 하나로 "그 제품을 만든 shot 의 공정 값·그 무렵 설비 경보·부적합" 을 거꾸로 찾을 수 있다.
 */
@Service
public class ShipmentSerialService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentSerialService.class);

    /** 추적 화면에서 볼 경보 범위: shot 시각 기준 앞 30분 ~ 뒤 5분 */
    static final int TRACE_BEFORE_MIN = 30;
    static final int TRACE_AFTER_MIN = 5;

    private static final String CANDIDATE_SELECT = """
            SELECT TOP (50) p.message_id, p.equipment_id, p.shot_no,
                   CONVERT(VARCHAR(40), p.shot_ts, 127) AS shot_ts, p.serial_no, p.inspection_result, p.defect_type,
                   l.confirmed_serial
            FROM dbo.process_shot AS p
            LEFT JOIN dbo.shipment_serial_link AS l ON l.message_id = p.message_id
            """;

    private final JdbcTemplate jdbc;

    public ShipmentSerialService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 임시 시리얼로 shot 찾기 (최근 것부터). */
    public List<Candidate> candidatesBySerial(String provisionalSerial) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE p.serial_no = ? ORDER BY p.shot_ts DESC",
                (rs, n) -> candidate(rs), provisionalSerial.trim());
    }

    List<Candidate> candidateByMessage(String messageId) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE p.message_id = ?", (rs, n) -> candidate(rs), messageId.trim());
    }

    /** 확정 요청 결과: 판단 + (연결했으면) 연결 번호. */
    public record ConfirmResult(Decision decision, Long linkId) {
    }

    /**
     * 확정 시리얼을 shot 에 연결한다. messageId 가 있으면 그 shot, 없으면 임시 시리얼로 찾아 하나일 때만 연결.
     */
    public ConfirmResult confirm(String confirmedSerial, String provisionalSerial, String messageId,
            String shipmentNo, String confirmedBy) {
        String bad = SerialLinkRule.validateConfirmedSerial(confirmedSerial);
        if (bad == null && blank(messageId) && blank(provisionalSerial)) {
            bad = "임시 시리얼이나 messageId 중 하나는 있어야 합니다";
        }
        if (bad != null) {
            return new ConfirmResult(new Decision(Outcome.BAD_REQUEST, null, List.of(), bad, null), null);
        }
        String serial = confirmedSerial.trim();
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.shipment_serial_link WHERE confirmed_serial = ?",
                Integer.class, serial);
        if (used != null && used > 0) {
            return new ConfirmResult(new Decision(Outcome.REJECTED, null, List.of(),
                    "확정 시리얼 " + serial + " 은(는) 이미 다른 shot 에 연결돼 있습니다", null), null);
        }

        boolean byMessage = !blank(messageId);
        Decision d = SerialLinkRule.choose(byMessage ? candidateByMessage(messageId) : candidatesBySerial(provisionalSerial),
                byMessage);
        if (d.outcome() != Outcome.LINK) {
            return new ConfirmResult(d, null);
        }
        Candidate c = d.chosen();
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO dbo.shipment_serial_link
                        (confirmed_serial, message_id, equipment_id, shot_no, shot_ts, provisional_serial, shipment_no, confirmed_by)
                    OUTPUT INSERTED.link_id
                    SELECT ?, p.message_id, p.equipment_id, p.shot_no, p.shot_ts, p.serial_no, ?, ?
                    FROM dbo.process_shot AS p WHERE p.message_id = ?
                    """, Long.class, serial, blank(shipmentNo) ? null : shipmentNo.trim(), confirmedBy, c.messageId());
            log.info("[출하 확정] {} → shot {} {} (임시 {})", serial, c.equipmentId(), c.shotNo(), c.provisionalSerial());
            return new ConfirmResult(d, id);
        } catch (DuplicateKeyException e) {
            // 두 사람이 동시에 확정한 경우: 고유 인덱스가 막은 것
            return new ConfirmResult(new Decision(Outcome.REJECTED, null, List.of(c),
                    "방금 다른 확정과 겹쳤습니다 (확정 시리얼 또는 shot 이 이미 연결됨). 다시 조회해 주세요", null), null);
        }
    }

    /** 최근 연결 목록. */
    public List<Map<String, Object>> recentLinks(int top) {
        return jdbc.queryForList("""
                SELECT TOP (?) l.link_id, l.confirmed_serial, l.provisional_serial, l.equipment_id, l.shot_no,
                       CONVERT(VARCHAR(40), l.shot_ts, 127) AS shot_ts, l.shipment_no,
                       CONVERT(VARCHAR(40), l.confirmed_at, 127) AS confirmed_at, l.confirmed_by,
                       p.inspection_result
                FROM dbo.shipment_serial_link AS l
                JOIN dbo.process_shot AS p ON p.message_id = l.message_id
                ORDER BY l.link_id DESC
                """, top);
    }

    /**
     * 확정 시리얼 → 그 제품을 만든 shot 의 공정 값, 그 무렵(앞 30분~뒤 5분) 같은 설비의 경보와 연결된 부적합.
     * 고객 클레임·리콜 때 "이 제품을 만들 때 설비에 무슨 일이 있었나" 를 찾는 용도.
     */
    public Optional<Map<String, Object>> trace(String confirmedSerial) {
        List<Map<String, Object>> shot = jdbc.queryForList("""
                SELECT l.confirmed_serial, l.provisional_serial, l.shipment_no, l.confirmed_by,
                       CONVERT(VARCHAR(40), l.confirmed_at, 127) AS confirmed_at,
                       p.message_id, p.equipment_id, p.shot_no, CONVERT(VARCHAR(40), p.shot_ts, 127) AS shot_ts,
                       p.mold_temp, p.barrel_temp, p.injection_pressure, p.holding_pressure, p.cushion, p.cycle_time,
                       p.motor_current, p.inspection_result, p.defect_type, p.invalid_sensors, p.arrival_late, p.late_status
                FROM dbo.shipment_serial_link AS l
                JOIN dbo.process_shot AS p ON p.message_id = l.message_id
                WHERE l.confirmed_serial = ?
                """, confirmedSerial.trim());
        if (shot.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> s = shot.get(0);
        List<Map<String, Object>> anomalies = jdbc.queryForList("""
                SELECT a.anomaly_id, a.shot_no, CONVERT(VARCHAR(40), a.detected_at, 127) AS detected_at, a.rule_code,
                       a.sensor_name, a.message, a.status,
                       (SELECT STRING_AGG(n.ncr_no, ',') FROM dbo.ncr_anomaly AS x JOIN dbo.ncr AS n ON n.ncr_id = x.ncr_id
                         WHERE x.anomaly_id = a.anomaly_id) AS ncr_nos
                FROM dbo.anomaly_event AS a
                JOIN dbo.process_shot AS p ON p.message_id = ?
                WHERE a.equipment_id = p.equipment_id
                  AND a.detected_at BETWEEN DATEADD(MINUTE, ?, p.shot_ts) AND DATEADD(MINUTE, ?, p.shot_ts)
                ORDER BY a.detected_at
                """, s.get("message_id"), -TRACE_BEFORE_MIN, TRACE_AFTER_MIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shot", s);
        out.put("anomalies", anomalies);
        out.put("window", "shot 시각 앞 " + TRACE_BEFORE_MIN + "분 ~ 뒤 " + TRACE_AFTER_MIN + "분, 같은 설비");
        return Optional.of(out);
    }

    private static Candidate candidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candidate(rs.getString("message_id"), rs.getString("equipment_id"), rs.getObject("shot_no", Long.class),
                rs.getString("shot_ts"), rs.getString("serial_no"), trim(rs.getString("inspection_result")),
                rs.getString("defect_type"), rs.getString("confirmed_serial"));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
