package com.qualiguard.qms.quality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설비 1대의 이상 탐지 상태. Kafka Streams 상태 저장소에 JSON 으로 저장된다.
 * (직렬화가 단순하도록 public 필드만 두고 메서드는 두지 않는다.)
 */
public class EquipmentState {

    /** 지금까지 처리한 shot 수 (설비 카운터가 리셋돼도 계속 증가) */
    public long count;
    /** 최근 처리한 messageId (중복 제거용) */
    public List<String> recentMessageIds = new ArrayList<>();
    public Map<String, SensorState> sensors = new LinkedHashMap<>();

    /** 기준 구간 불량 수 / 불량률 (기준 구간이 끝나기 전에는 -1) */
    public long baselineNg;
    public double baselineNgRate = -1;
    /** p 관리도용 현재 구간(50 shot)의 불량 여부 */
    public List<Integer> ngWindow = new ArrayList<>();
    /** 최근 50 shot 불량 유형 (에이전트 참고용, 정상은 빈 문자열) */
    public List<String> recentDefects = new ArrayList<>();

    /** 규칙|센서 → 마지막 경보를 낸 count (같은 경보 반복 억제) */
    public Map<String, Long> lastAlertAt = new HashMap<>();

    public static class SensorState {
        /** 기준 구간 누적 */
        public int n;
        public double sum;
        public double sumSq;
        public boolean ready;
        public double mean;
        public double sigma;
        /** 최근 값 (최대 30개) */
        public List<Double> recent = new ArrayList<>();
        /** 진행 중인 부분군 (5개 모이면 판정) */
        public List<Double> subgroup = new ArrayList<>();
        /** 부분군 평균이 연속으로 상한/하한을 벗어난 횟수 */
        public int xbarHighRun;
        public int xbarLowRun;
        /** 부분군 표준편차가 연속으로 상한을 넘은 횟수 */
        public int sHighRun;
    }
}
