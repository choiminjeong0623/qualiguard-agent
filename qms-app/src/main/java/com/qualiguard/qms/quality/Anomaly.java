package com.qualiguard.qms.quality;

import java.util.List;
import java.util.Map;

/**
 * 이상 탐지 결과 1건 (Kafka quality.anomaly 메시지). 다음 단계의 에이전트가 원인 추정에 쓰는 근거를 함께 담는다.
 *
 * @param rule          판정 규칙 코드 (INDIVIDUAL, XBAR, S_CHART, P_CHART)
 * @param observed      규칙에 걸린 값 (개별값, 부분군 평균, 부분군 표준편차, 불량률)
 * @param recentValues  해당 센서의 최근 값 (최대 30개, 오래된 것부터)
 * @param sensorZScores 모든 센서의 '최근 5 shot 평균'이 기준 대비 몇 σ 떨어져 있는지 (원인 추정 단서)
 * @param shotNo        경보를 낸 shot 번호. 그 메시지에 shotNo 가 없었으면 그 설비에서 마지막으로 받은
 *                      shot 번호를 쓰고 shotNoEstimated 를 true 로 둔다 (ADR-017)
 */
public record Anomaly(String anomalyId, String lineId, String equipmentId, long shotNo, String ts,
        String rule, String ruleName, String sensor, String sensorName, String unit,
        double observed, double baselineMean, double baselineSigma, double lowerLimit, double upperLimit,
        List<Double> recentValues, Map<String, Double> sensorZScores, double recentNgRate,
        Map<String, Integer> recentDefectTypes, String message, boolean shotNoEstimated) {
}
