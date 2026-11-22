package com.qualiguard.qms.shipment;

import java.util.List;
import java.util.Locale;

/**
 * 출하 확정 때 "확정 시리얼 → 어느 shot" 을 고르는 규칙 (ADR-019). DB 없이 시험할 수 있는 순수 로직.
 *
 * 공정 중 시리얼(임시 TMP-…)은 재작업·라벨 재출력으로 여러 shot 에 겹칠 수 있다.
 * - 연결할 수 있는 shot = 아직 다른 확정 시리얼에 연결되지 않았고, 검사 결과가 NG 가 아닌 shot
 * - 그런 shot 이 하나면 자동으로 연결, 여럿이면 사람이 고르게 후보를 돌려준다(추측해서 붙이지 않음)
 * - 검사 결과가 없는 shot 은 연결하되 경고를 남긴다 (결측 허용, ADR-017)
 */
public final class SerialLinkRule {

    /** 공정 중 shot 1건 (process_shot + 이미 연결된 확정 시리얼). */
    public record Candidate(String messageId, String equipmentId, Long shotNo, String shotTs,
            String provisionalSerial, String inspectionResult, String defectType, String linkedSerial) {

        boolean linked() {
            return linkedSerial != null;
        }

        boolean ng() {
            return "NG".equals(inspectionResult);
        }
    }

    public enum Outcome {
        /** 연결할 shot 이 정해짐 */
        LINK,
        /** 연결할 수 있는 shot 이 여럿 → 사람이 messageId 로 골라야 함 */
        AMBIGUOUS,
        /** 그 임시 시리얼·messageId 로 찾은 shot 이 없음 */
        NOT_FOUND,
        /** 찾았지만 연결할 수 없음 (이미 연결됨, NG) */
        REJECTED,
        /** 요청 자체가 잘못됨 (확정 시리얼 형식) */
        BAD_REQUEST
    }

    public record Decision(Outcome outcome, Candidate chosen, List<Candidate> candidates, String message,
            String warning) {
    }

    public static final int SERIAL_MAX = 40;

    private SerialLinkRule() {
    }

    /** 확정 시리얼 형식: 비어 있지 않고 40자 이하, 임시값(TMP-)이 아님. 문제가 없으면 null. */
    public static String validateConfirmedSerial(String serial) {
        if (serial == null || serial.isBlank()) {
            return "확정 시리얼이 비어 있습니다";
        }
        if (serial.trim().length() > SERIAL_MAX) {
            return "확정 시리얼은 " + SERIAL_MAX + "자 이하여야 합니다";
        }
        if (serial.trim().toUpperCase(Locale.ROOT).startsWith("TMP-")) {
            return "임시 시리얼(TMP-…)은 확정 시리얼로 쓸 수 없습니다";
        }
        return null;
    }

    /**
     * @param found      임시 시리얼 또는 messageId 로 찾은 shot 들
     * @param byMessage  사람이 messageId 로 직접 고른 경우 true
     */
    public static Decision choose(List<Candidate> found, boolean byMessage) {
        if (found.isEmpty()) {
            return new Decision(Outcome.NOT_FOUND, null, found,
                    byMessage ? "해당 messageId 의 shot 이 없습니다" : "해당 임시 시리얼의 shot 이 없습니다", null);
        }
        List<Candidate> usable = found.stream().filter(c -> !c.linked() && !c.ng()).toList();
        if (usable.isEmpty()) {
            Candidate c = found.get(0);
            String why = found.stream().allMatch(Candidate::linked)
                    ? "이미 확정 시리얼에 연결된 shot 입니다" + (found.size() == 1 ? " (" + c.linkedSerial() + ")" : "")
                    : "연결할 수 있는 shot 이 없습니다 (이미 연결됐거나 검사 결과 NG)";
            if (found.size() == 1 && c.ng() && !c.linked()) {
                why = "검사 결과 NG 인 shot 은 출하 확정할 수 없습니다";
            }
            return new Decision(Outcome.REJECTED, null, found, why, null);
        }
        if (usable.size() > 1) {
            return new Decision(Outcome.AMBIGUOUS, null, usable,
                    "같은 임시 시리얼의 shot 이 " + usable.size() + "개입니다. messageId 로 하나를 골라 주세요", null);
        }
        Candidate chosen = usable.get(0);
        String warning = chosen.inspectionResult() == null ? "검사 결과가 없는 shot 입니다 (결측). 출하 전 확인 필요" : null;
        return new Decision(Outcome.LINK, chosen, found, "연결할 shot 을 정했습니다", warning);
    }
}
