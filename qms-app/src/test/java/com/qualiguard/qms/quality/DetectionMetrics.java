package com.qualiguard.qms.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 이상 탐지 채점 (로드맵 E2). 설비 하나의 정답(shot 마다 고장 중인지)과 경보 shot 목록으로 점수를 낸다.
 *
 * 1) 창(window) 단위 혼동행렬
 *    기준 구간(처음 100 shot) 뒤를 50 shot 씩 자른다. 창 안에 고장 중인 shot 이 하나라도 있으면 "고장 창",
 *    경보가 하나라도 있으면 "경보 창". shot 단위로 세면 경보 1건이 고장 수백 shot 을 덮지 못해 재현율이 무의미하게
 *    낮아지므로, 사람이 "그 무렵에 알람이 울렸나" 로 보는 단위에 맞춘다.
 *        TP 고장 창 + 경보      FN 고장 창 + 경보 없음 (놓침)
 *        FP 정상 창 + 경보 (오경보)  TN 정상 창 + 경보 없음
 *    마지막에 50 shot 이 안 되는 자투리 창은 버린다.
 *
 * 2) 고장 구간(episode) 단위 탐지 지연
 *    같은 고장 이름이 이어지는 shot 구간마다, 구간 안의 첫 경보 shot - 구간 시작 shot.
 *    구간 안에 경보가 없으면 놓침. 첫 불량(NG) shot 보다 먼저 울렸는지도 함께 본다.
 */
public final class DetectionMetrics {

    public static final int WINDOW = 50;

    /** 혼동행렬. 비율은 분모가 0 이면 NaN (그 seed 에서 계산할 수 없음). */
    public record Confusion(int tp, int fp, int fn, int tn) {

        public Confusion plus(Confusion o) {
            return new Confusion(tp + o.tp, fp + o.fp, fn + o.fn, tn + o.tn);
        }

        public double precision() {
            return ratio(tp, tp + fp);
        }

        public double recall() {
            return ratio(tp, tp + fn);
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return Double.isNaN(p) || Double.isNaN(r) || p + r == 0 ? Double.NaN : 2 * p * r / (p + r);
        }

        /** 오경보율: 정상 창 가운데 경보가 울린 비율. */
        public double fpr() {
            return ratio(fp, fp + tn);
        }

        /** 놓침률: 고장 창 가운데 경보가 없던 비율 (= 1 - 재현율). */
        public double fnr() {
            return ratio(fn, tp + fn);
        }

        private static double ratio(int a, int b) {
            return b == 0 ? Double.NaN : (double) a / b;
        }
    }

    /**
     * 고장 구간 하나의 탐지 결과.
     *
     * @param firstAlarm  구간 안 첫 경보 shot (없으면 null = 놓침)
     * @param firstDefect 구간 안 첫 불량 shot (없으면 null)
     */
    public record Episode(String equipmentId, String fault, long start, long end, Long firstAlarm, Long firstDefect) {

        public boolean detected() {
            return firstAlarm != null;
        }

        /** 탐지 지연(shot). 놓쳤으면 null. */
        public Long delay() {
            return firstAlarm == null ? null : firstAlarm - start;
        }

        /** 첫 불량보다 먼저(또는 같은 shot 에) 울렸는지. 불량이 없던 구간은 탐지만 되면 true. */
        public boolean beforeFirstDefect() {
            return firstAlarm != null && (firstDefect == null || firstAlarm <= firstDefect);
        }
    }

    /**
     * shot 1건의 정답.
     *
     * @param faults 그 shot 에 진행 중인 고장 이름들 (없으면 빈 목록)
     */
    public record Truth(List<String> faults, boolean defect) {

        public boolean faulty() {
            return !faults.isEmpty();
        }
    }

    private DetectionMetrics() {
    }

    /** 기준 구간 뒤 shot 들을 50 shot 창으로 나눠 혼동행렬을 센다. */
    public static Confusion confusion(NavigableMap<Long, Truth> truth, NavigableSet<Long> alarmShots, int baseline) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        if (truth.isEmpty()) {
            return new Confusion(0, 0, 0, 0);
        }
        long last = truth.lastKey();
        for (long from = baseline + 1L; from + WINDOW - 1 <= last; from += WINDOW) {
            long to = from + WINDOW - 1;
            boolean faulty = truth.subMap(from, true, to, true).values().stream().anyMatch(Truth::faulty);
            boolean alarmed = !alarmShots.subSet(from, true, to, true).isEmpty();
            if (faulty && alarmed) {
                tp++;
            } else if (faulty) {
                fn++;
            } else if (alarmed) {
                fp++;
            } else {
                tn++;
            }
        }
        return new Confusion(tp, fp, fn, tn);
    }

    /** 고장 이름별로 이어지는 shot 구간을 찾아, 구간마다 첫 경보·첫 불량을 기록한다. */
    public static List<Episode> episodes(String equipmentId, NavigableMap<Long, Truth> truth,
            NavigableSet<Long> alarmShots) {
        Map<String, long[]> open = new TreeMap<>();   // 고장 이름 → {시작, 마지막으로 본 shot}
        List<long[]> closedRanges = new ArrayList<>();
        List<String> closedNames = new ArrayList<>();
        for (Map.Entry<Long, Truth> e : truth.entrySet()) {
            long shot = e.getKey();
            for (String f : e.getValue().faults()) {
                long[] r = open.get(f);
                if (r != null && r[1] == shot - 1) {
                    r[1] = shot;
                } else {
                    if (r != null) {
                        closedRanges.add(r);
                        closedNames.add(f);
                    }
                    open.put(f, new long[] {shot, shot});
                }
            }
        }
        open.forEach((f, r) -> {
            closedRanges.add(r);
            closedNames.add(f);
        });
        List<Episode> out = new ArrayList<>();
        for (int i = 0; i < closedRanges.size(); i++) {
            long start = closedRanges.get(i)[0];
            long end = closedRanges.get(i)[1];
            Long firstAlarm = alarmShots.ceiling(start);
            if (firstAlarm != null && firstAlarm > end) {
                firstAlarm = null;
            }
            Long firstDefect = truth.subMap(start, true, end, true).entrySet().stream()
                    .filter(x -> x.getValue().defect()).map(Map.Entry::getKey).findFirst().orElse(null);
            out.add(new Episode(equipmentId, closedNames.get(i), start, end, firstAlarm, firstDefect));
        }
        out.sort((a, b) -> Long.compare(a.start(), b.start()));
        return out;
    }

    /** 평균 (NaN 은 뺀다). 값이 없으면 NaN. */
    public static double mean(List<Double> xs) {
        List<Double> v = xs.stream().filter(x -> x != null && !Double.isNaN(x)).toList();
        return v.isEmpty() ? Double.NaN : v.stream().mapToDouble(Double::doubleValue).sum() / v.size();
    }

    /** 표본 표준편차 (n-1, NaN 은 뺀다). 값이 2개 미만이면 0. */
    public static double stdev(List<Double> xs) {
        List<Double> v = xs.stream().filter(x -> x != null && !Double.isNaN(x)).toList();
        if (v.size() < 2) {
            return v.isEmpty() ? Double.NaN : 0;
        }
        double m = mean(v);
        double ss = v.stream().mapToDouble(x -> (x - m) * (x - m)).sum();
        return Math.sqrt(ss / (v.size() - 1));
    }

    /** 시험·평가기에서 정답 맵을 만들 때 쓰는 편의 함수. */
    public static NavigableMap<Long, Truth> truthMap() {
        return new TreeMap<>();
    }

    public static NavigableSet<Long> alarmSet() {
        return new TreeSet<>();
    }
}
