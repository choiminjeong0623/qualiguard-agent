package com.qualiguard.qms.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.shipment.SerialLinkRule.Candidate;
import com.qualiguard.qms.shipment.SerialLinkRule.Outcome;

/** 출하 확정 때 shot 고르기 규칙 — 임시 시리얼은 겹칠 수 있다 (ADR-017, ADR-019). */
class SerialLinkRuleTest {

    private static Candidate shot(String id, String result, String linked) {
        return new Candidate(id, "IMM-01", 10L, "2026-11-22T09:00:00+09:00", "TMP-0001", result, null, linked);
    }

    @Test
    void 연결할_수_있는_shot_이_하나면_그_shot_에_연결한다() {
        var d = SerialLinkRule.choose(List.of(shot("m-1", "OK", null), shot("m-2", "OK", "SN-9")), false);
        assertEquals(Outcome.LINK, d.outcome());
        assertEquals("m-1", d.chosen().messageId());
        assertNull(d.warning());
    }

    @Test
    void 임시_시리얼이_여러_shot_에_겹치면_사람이_고르게_후보를_돌려준다() {
        var d = SerialLinkRule.choose(List.of(shot("m-1", "OK", null), shot("m-2", "OK", null),
                shot("m-3", "NG", null)), false);
        assertEquals(Outcome.AMBIGUOUS, d.outcome());
        assertEquals(List.of("m-1", "m-2"), d.candidates().stream().map(Candidate::messageId).toList());  // NG 는 후보에서 뺌
    }

    @Test
    void NG_shot_과_이미_연결된_shot_은_연결하지_않는다() {
        assertEquals(Outcome.REJECTED, SerialLinkRule.choose(List.of(shot("m-1", "NG", null)), true).outcome());
        var linked = SerialLinkRule.choose(List.of(shot("m-1", "OK", "SN-1")), true);
        assertEquals(Outcome.REJECTED, linked.outcome());
        assertEquals("이미 확정 시리얼에 연결된 shot 입니다 (SN-1)", linked.message());
    }

    @Test
    void 검사_결과가_없는_shot_은_연결하되_경고한다() {
        var d = SerialLinkRule.choose(List.of(shot("m-1", null, null)), false);
        assertEquals(Outcome.LINK, d.outcome());
        assertNotNull(d.warning());
    }

    @Test
    void 찾은_shot_이_없으면_NOT_FOUND() {
        assertEquals(Outcome.NOT_FOUND, SerialLinkRule.choose(List.of(), false).outcome());
    }

    @Test
    void 확정_시리얼_형식을_검사한다() {
        assertNull(SerialLinkRule.validateConfirmedSerial("SN-2026-000123"));
        assertNotNull(SerialLinkRule.validateConfirmedSerial(" "));
        assertNotNull(SerialLinkRule.validateConfirmedSerial("tmp-0001"));
        assertNotNull(SerialLinkRule.validateConfirmedSerial("X".repeat(41)));
    }
}
