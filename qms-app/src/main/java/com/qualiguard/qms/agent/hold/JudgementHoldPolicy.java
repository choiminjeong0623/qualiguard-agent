package com.qualiguard.qms.agent.hold;

import java.util.Optional;

/**
 * 판단 보류 규칙 (로드맵 F2): 에이전트가 끝내 결론을 내지 못한 분석을 사람 검토로 넘길지 정한다.
 *
 * 자동화가 더 할 수 있는 일이 없는데 경보가 조용히 닫히는 경우만 보류한다.
 *  1) RECHECK_LIMIT: 관찰(OBSERVE) 뒤 새 경보 없는 재확인을 상한(recheck-max)까지 했는데 여전히 관찰
 *     → 스케줄러가 더 재확인하지 않으므로, 사람이 보지 않으면 원인 미확정인 채 끝난다.
 *  2) VERIFY_WEAK: 검증 에이전트 지적이 재분석 뒤에도 남았는데(WEAK) 결론이 부적합 초안이 아님
 *     → 부적합 초안은 어차피 검토자가 보지만, 관찰·센서 점검은 경보를 종결하므로 근거 부족한 종결이 된다.
 *
 * DB·LLM 없이 시험할 수 있게 순수 함수로 둔다.
 */
public final class JudgementHoldPolicy {

    public static final String RECHECK_LIMIT = "RECHECK_LIMIT";
    public static final String VERIFY_WEAK = "VERIFY_WEAK";

    /** 보류 사유 코드와 검토자에게 보여 줄 설명. */
    public record Hold(String reason, String detail) {
    }

    private JudgementHoldPolicy() {
    }

    /**
     * @param trigger      ALARM / RECHECK
     * @param decision     NCR_DRAFT / SENSOR_CHECK / OBSERVE
     * @param recheckCount 이 분석이 몇 번째 재확인인지 (경보 분석은 0)
     * @param recheckMax   재확인 상한 (0 이면 재확인을 쓰지 않으므로 1번 규칙도 쓰지 않음)
     * @param verifyStatus PASS / WEAK / null(검증 안 함)
     */
    public static Optional<Hold> evaluate(String trigger, String decision, int recheckCount, int recheckMax,
            String verifyStatus) {
        if ("RECHECK".equals(trigger) && "OBSERVE".equals(decision) && recheckMax > 0 && recheckCount >= recheckMax) {
            return Optional.of(new Hold(RECHECK_LIMIT,
                    "새 경보 없는 재확인 " + recheckCount + "회(상한 " + recheckMax + ") 뒤에도 원인을 정하지 못했습니다."));
        }
        if ("WEAK".equals(verifyStatus) && !"NCR_DRAFT".equals(decision)) {
            return Optional.of(new Hold(VERIFY_WEAK,
                    "검증 에이전트 지적이 재분석 뒤에도 남았는데 " + decision + " 로 경보가 종결됐습니다."));
        }
        return Optional.empty();
    }
}
