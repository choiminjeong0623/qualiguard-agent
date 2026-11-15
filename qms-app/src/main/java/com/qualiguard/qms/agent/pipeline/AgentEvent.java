package com.qualiguard.qms.agent.pipeline;

import java.util.Map;

/**
 * 에이전트 사이에 오가는 이벤트. 사건의 무거운 내용(경보 목록, 도구 기록, 분석 결과 전문)은 사건 저장소에 두고,
 * 이벤트에는 사건 ID 와 다음 에이전트가 판단에 쓰는 요약만 싣는다.
 *
 * @param incidentId 사건 ID (Kafka 키)
 * @param attempt    분석 시도 번호 (1 + 검증 반려 재분석)
 * @param data       단계별 요약 (예: decision·causeCode, 검증 status·fails, 작성 mode)
 */
public record AgentEvent(String incidentId, int attempt, Map<String, Object> data) {
}
