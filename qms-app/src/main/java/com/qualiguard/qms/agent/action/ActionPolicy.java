package com.qualiguard.qms.agent.action;

import java.util.ArrayList;
import java.util.List;

import com.qualiguard.qms.agent.action.ActionCatalog.Action;
import com.qualiguard.qms.agent.action.ActionCatalog.Risk;

/**
 * 안전장치: 이 조치를 에이전트가 바로 실행해도 되는지, 사람 승인을 받아야 하는지 정한다.
 * DB·LLM 없이 입력만 보고 정하는 순수 함수라, 같은 입력엔 항상 같은 답이 나오고 시험하기 쉽다.
 *
 * 하나라도 걸리면 사람 승인 (이유를 모두 남긴다):
 *  1. 위험도 HIGH (라인 정지·자재 처분)
 *  2. 검증 에이전트가 통과시키지 않은 분석
 *  3. 확신도가 80 미만 (위험도와 관계없이 같은 기준. E7: v2 홀드아웃에서 확신도 50~69 의 실제 적중 27%,
 *     70~79 80%, 80 이상 97~100% — eval/calibration_report.md)
 *  4. 이미 제품에 영향이 난 사건(심각도 HIGH)에서 공정 설정을 바꾸는 MEDIUM 조치
 *  5. 같은 설비에서 최근 24시간 자동 실행 횟수가 한도 이상 (에이전트 폭주 방지)
 */
public class ActionPolicy {

    public enum Mode { AUTO, APPROVAL }

    /**
     * @param severity         분류 에이전트의 심각도 (HIGH / MEDIUM / LOW)
     * @param confidence       분석 확신도 0~100
     * @param verified         검증 에이전트 PASS 여부
     * @param autoCountLast24h 같은 설비에서 최근 24시간 자동 실행한 조치 수
     */
    public record Context(Action action, String severity, int confidence, boolean verified, int autoCountLast24h) {
    }

    public record Decision(Mode mode, List<String> reasons) {
    }

    /** 자동 실행에 필요한 최소 확신도. 이보다 낮으면 위험도가 LOW 여도 사람 승인. */
    static final int MIN_CONFIDENCE = 80;

    private final int maxAutoPerDay;

    public ActionPolicy(int maxAutoPerDay) {
        this.maxAutoPerDay = maxAutoPerDay;
    }

    public Decision decide(Context c) {
        List<String> reasons = new ArrayList<>();
        Risk risk = c.action().risk();
        if (risk == Risk.HIGH) {
            reasons.add("위험도 HIGH 조치");
        }
        if (!c.verified()) {
            reasons.add("검증 에이전트 미통과");
        }
        if (c.confidence() < MIN_CONFIDENCE) {
            reasons.add("확신도 " + c.confidence() + " < 기준 " + MIN_CONFIDENCE);
        }
        if (risk == Risk.MEDIUM && "HIGH".equals(c.severity())) {
            reasons.add("품질 영향 사건의 공정 설정 변경");
        }
        if (c.autoCountLast24h() >= maxAutoPerDay) {
            reasons.add("24시간 자동 실행 한도 " + maxAutoPerDay + "회 도달");
        }
        if (reasons.isEmpty()) {
            return new Decision(Mode.AUTO, List.of("위험도 " + risk + ", 확신도 " + c.confidence() + ", 검증 통과"));
        }
        return new Decision(Mode.APPROVAL, List.copyOf(reasons));
    }
}