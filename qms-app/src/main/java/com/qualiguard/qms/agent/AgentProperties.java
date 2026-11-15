package com.qualiguard.qms.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 qg.agent 설정.
 *
 * @param autoEnabled          true 면 새 경보를 주기적으로 자동 분석 (LLM 비용이 들므로 기본 false)
 * @param pollMs               자동 분석 주기
 * @param incidentShotWindow   첫 경보부터 몇 shot 안의 경보를 한 사건으로 묶을지
 * @param incidentMinutes      첫 경보부터 몇 분 안의 경보를 한 사건으로 묶을지 (설비 카운터 리셋 대비)
 * @param mergeMinutes         같은 설비·같은 원인의 초안이 이 시간 안에 있으면 새 초안 대신 기존 초안에 연결
 * @param recheckShots         관찰(OBSERVE) 판단 뒤 새 경보가 없어도 이 shot 수가 지나면 다시 분석 (0 이면 끔)
 * @param recheckMax           새 경보 없이 이어지는 재확인 최대 횟수
 */
@ConfigurationProperties(prefix = "qg.agent")
public record AgentProperties(boolean autoEnabled, long pollMs, int incidentShotWindow, int incidentMinutes,
        int mergeMinutes, int recheckShots, int recheckMax) {
}
