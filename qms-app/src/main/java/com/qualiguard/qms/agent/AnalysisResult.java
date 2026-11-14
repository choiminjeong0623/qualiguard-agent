package com.qualiguard.qms.agent;

import java.util.List;

/**
 * 에이전트가 돌려주는 분석 결과 (LLM 구조화 출력).
 *
 * @param decision          NCR_DRAFT(부적합 초안 작성) / SENSOR_CHECK(측정 계통 점검, 제품 영향 없음) / OBSERVE(단발성 변동, 관찰)
 * @param causeCode         HEATER_DEGRADATION / COOLING_ISSUE / CHECK_RING_WEAR / SENSOR_FAULT / MATERIAL / NOISE / UNKNOWN
 * @param confidence        확신도 0~100
 * @param evidence          판단 근거 (도구로 확인한 수치를 포함한 문장)
 * @param similarCases      참고한 과거 부적합 번호 (도구 결과에 있는 번호만)
 * @param watchItems        다음에 확인할 센서·조건 (OBSERVE 일 때 필수, 다음 분석에 이전 판단으로 넘겨준다)
 */
public record AnalysisResult(
        String decision,
        String causeCode,
        Integer confidence,
        String title,
        String phenomenon,
        String suspectedCause,
        List<String> evidence,
        List<String> similarCases,
        String recommendedAction,
        String reasoningSummary,
        List<String> watchItems) {
}
