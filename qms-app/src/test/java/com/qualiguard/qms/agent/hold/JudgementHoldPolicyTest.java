package com.qualiguard.qms.agent.hold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 판단 보류 규칙: 자동화가 더 할 일이 없는데 경보가 조용히 닫히는 경우만 사람 검토로 넘긴다. */
class JudgementHoldPolicyTest {

    @Test
    void 재확인_상한까지_관찰이면_보류() {
        var hold = JudgementHoldPolicy.evaluate("RECHECK", "OBSERVE", 3, 3, "PASS");
        assertTrue(hold.isPresent());
        assertEquals(JudgementHoldPolicy.RECHECK_LIMIT, hold.get().reason());
        assertTrue(hold.get().detail().contains("3회"));
    }

    @Test
    void 상한_전의_재확인_관찰은_보류하지_않음_다음_재확인이_있으므로() {
        assertTrue(JudgementHoldPolicy.evaluate("RECHECK", "OBSERVE", 2, 3, "PASS").isEmpty());
    }

    @Test
    void 경보로_시작한_관찰은_보류하지_않음() {
        assertTrue(JudgementHoldPolicy.evaluate("ALARM", "OBSERVE", 0, 3, "PASS").isEmpty());
    }

    @Test
    void 재확인을_끈_설정에서는_상한_규칙을_쓰지_않음() {
        assertTrue(JudgementHoldPolicy.evaluate("RECHECK", "OBSERVE", 0, 0, "PASS").isEmpty());
    }

    @Test
    void 상한_재확인에서_원인을_정하면_보류하지_않음() {
        assertTrue(JudgementHoldPolicy.evaluate("RECHECK", "NCR_DRAFT", 3, 3, "PASS").isEmpty());
        assertTrue(JudgementHoldPolicy.evaluate("RECHECK", "SENSOR_CHECK", 3, 3, "PASS").isEmpty());
    }

    @Test
    void 검증_지적이_남은_채_경보를_닫으면_보류() {
        var hold = JudgementHoldPolicy.evaluate("ALARM", "SENSOR_CHECK", 0, 3, "WEAK");
        assertTrue(hold.isPresent());
        assertEquals(JudgementHoldPolicy.VERIFY_WEAK, hold.get().reason());
        assertEquals(JudgementHoldPolicy.VERIFY_WEAK,
                JudgementHoldPolicy.evaluate("ALARM", "OBSERVE", 0, 3, "WEAK").get().reason());
    }

    @Test
    void 검증_지적이_남아도_부적합_초안이면_검토자가_보므로_보류하지_않음() {
        assertTrue(JudgementHoldPolicy.evaluate("ALARM", "NCR_DRAFT", 0, 3, "WEAK").isEmpty());
    }

    @Test
    void 검증을_안_했거나_통과면_보류하지_않음() {
        assertTrue(JudgementHoldPolicy.evaluate("ALARM", "SENSOR_CHECK", 0, 3, null).isEmpty());
        assertTrue(JudgementHoldPolicy.evaluate("ALARM", "SENSOR_CHECK", 0, 3, "PASS").isEmpty());
    }
}
