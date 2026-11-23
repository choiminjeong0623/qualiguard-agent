package com.qualiguard.qms.agent.ml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원인 분류 특징 66개 (scripts/ml_cause_baseline.py 의 features() 와 같은 계산).
 *
 * 경보 shot 까지의 데이터만 쓰고, 설비 이름·shot 번호는 넣지 않는다.
 * 센서 7개 × (기준 100 shot 대비 최근 5·20·60 shot 평균 z, 20 shot 기울기, 20 shot 표준편차, 20 shot 최대 |z|, |z|>3 비율)
 * + 최근 50 shot 불량률·기준 대비 차이 + 불량 유형 비율 4개 + 경보 규칙 4개 + 경보 센서 7개.
 * 파이썬과 값이 같은지는 CauseFeaturesParityTest 가 같은 시뮬레이터 파일로 확인한다.
 */
public final class CauseFeatures {

    public static final List<String> SENSORS = List.of("moldTemp", "barrelTemp", "injectionPressure",
            "holdingPressure", "cushion", "cycleTime", "motorCurrent");
    public static final List<String> RULES = List.of("INDIVIDUAL", "XBAR", "S_CHART", "P_CHART");
    public static final List<String> DEFECTS = List.of("SHORT_SHOT", "FLASH", "DIMENSION", "WARPAGE");
    public static final int BASELINE = 100;
    public static final List<String> NAMES = names();
    /** 센서 하나당 특징 수 (z5, z20, z60, slope20, sd20, spike20, spikeFrac20) */
    static final int PER_SENSOR = 7;

    private CauseFeatures() {
    }

    private static List<String> names() {
        List<String> n = new ArrayList<>();
        for (String k : SENSORS) {
            for (String f : List.of("z5", "z20", "z60", "slope20", "sd20", "spike20", "spikeFrac20")) {
                n.add(k + "." + f);
            }
        }
        n.add("ng50");
        n.add("ng50Diff");
        DEFECTS.forEach(d -> n.add("defect." + d));
        RULES.forEach(r -> n.add("rule." + r));
        SENSORS.forEach(k -> n.add("alarmSensor." + k));
        return List.copyOf(n);
    }

    /** 설비 하나의 shot 번호별 값 (같은 shot 이 다시 오면 나중 값). 없는 값은 NaN. */
    public static final class Series {
        final Map<Long, double[]> values = new HashMap<>();
        final Map<Long, Boolean> ng = new HashMap<>();
        final Map<Long, String> defect = new HashMap<>();

        /**
         * @param sensorValues SENSORS 순서, 없는 값은 null 또는 NaN
         * @param inspection   "OK" / "NG" / null(검사 결과 없음)
         */
        public void put(long shot, Double[] sensorValues, String inspection, String defectType) {
            double[] v = new double[SENSORS.size()];
            for (int j = 0; j < v.length; j++) {
                Double d = j < sensorValues.length ? sensorValues[j] : null;
                v[j] = d == null ? Double.NaN : d;
            }
            values.put(shot, v);
            if ("OK".equals(inspection) || "NG".equals(inspection)) {
                boolean bad = "NG".equals(inspection);
                ng.put(shot, bad);
                if (bad && defectType != null && !defectType.isBlank()) {
                    defect.put(shot, defectType);
                } else {
                    defect.remove(shot);
                }
            }   // 검사 결과가 없는 중복 메시지는 앞선 검사 결과를 지우지 않는다 (파이썬 기준선과 같음)
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        double value(long shot, int sensor) {
            double[] v = values.get(shot);
            return v == null ? Double.NaN : v[sensor];
        }

        double ngValue(long shot) {
            Boolean b = ng.get(shot);
            return b == null ? Double.NaN : b ? 1.0 : 0.0;
        }
    }

    /** 경보 하나의 특징 (NAMES 순서). */
    public static double[] compute(Series s, long shot, String rule, String sensor) {
        double[] out = new double[NAMES.size()];
        int o = 0;
        for (int j = 0; j < SENSORS.size(); j++) {
            double[] base = new double[BASELINE];
            for (int k = 0; k < BASELINE; k++) {
                base[k] = s.value(k + 1, j);
            }
            double mu = nanmean(base);
            double sd = nanstd(base);
            if (sd == 0) {
                sd = 1e-9;
            }
            double[] w5 = window(s, j, shot, 5, mu, sd);
            double[] w20 = window(s, j, shot, 20, mu, sd);
            double[] w60 = window(s, j, shot, 60, mu, sd);
            int valid = countValid(w20);
            out[o++] = nanmeanOrZero(w5);
            out[o++] = nanmeanOrZero(w20);
            out[o++] = nanmeanOrZero(w60);
            out[o++] = valid >= 3 ? slope(w20) * 20 : 0.0;
            out[o++] = valid >= 2 ? nanstd(w20) : 1.0;
            out[o++] = valid > 0 ? nanmaxAbs(w20) : 0.0;
            out[o++] = valid > 0 ? fracAbove(w20, 3) : 0.0;
        }
        long from = Math.max(1, shot - 49);
        double[] last = new double[(int) (shot - from + 1)];
        Map<String, Integer> types = new HashMap<>();
        for (long k = from; k <= shot; k++) {
            last[(int) (k - from)] = s.ngValue(k);
            String d = s.defect.get(k);
            if (d != null) {
                types.merge(d, 1, Integer::sum);
            }
        }
        double[] baseNg = new double[BASELINE];
        for (int k = 0; k < BASELINE; k++) {
            baseNg[k] = s.ngValue(k + 1);
        }
        double ng50 = nanmeanOrZero(last);
        out[o++] = ng50;
        out[o++] = ng50 - nanmean(baseNg);
        int total = Math.max(1, countValid(last));
        for (String d : DEFECTS) {
            out[o++] = (double) types.getOrDefault(d, 0) / total;
        }
        for (String r : RULES) {
            out[o++] = r.equals(rule) ? 1.0 : 0.0;
        }
        for (String k : SENSORS) {
            out[o++] = k.equals(sensor) ? 1.0 : 0.0;
        }
        return out;
    }

    /** 최근 n shot (shot 번호 max(1, shot-n+1) ~ shot) 의 z. 빠진 shot 은 NaN 으로 자리를 지킨다 (기울기 계산의 x 위치). */
    private static double[] window(Series s, int sensor, long shot, int n, double mu, double sd) {
        long from = Math.max(1, shot - n + 1);
        double[] w = new double[(int) (shot - from + 1)];
        for (long k = from; k <= shot; k++) {
            w[(int) (k - from)] = (s.value(k, sensor) - mu) / sd;
        }
        return w;
    }

    static int countValid(double[] x) {
        int n = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        return n;
    }

    /** NaN 을 뺀 평균. 값이 하나도 없으면 NaN (numpy nanmean 과 같음). */
    static double nanmean(double[] x) {
        double sum = 0;
        int n = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    static double nanmeanOrZero(double[] x) {
        double m = nanmean(x);
        return Double.isNaN(m) ? 0.0 : m;
    }

    /** NaN 을 뺀 모표준편차 (numpy nanstd, ddof=0). */
    static double nanstd(double[] x) {
        double m = nanmean(x);
        if (Double.isNaN(m)) {
            return Double.NaN;
        }
        double ss = 0;
        int n = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                ss += (v - m) * (v - m);
                n++;
            }
        }
        return Math.sqrt(ss / n);
    }

    static double nanmaxAbs(double[] x) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                m = Math.max(m, Math.abs(v));
            }
        }
        return m;
    }

    static double fracAbove(double[] x, double limit) {
        int n = 0;
        int above = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                n++;
                if (Math.abs(v) > limit) {
                    above++;
                }
            }
        }
        return n == 0 ? 0.0 : (double) above / n;
    }

    /** 최소제곱 직선의 기울기 (x = 창 안의 위치, NaN 은 뺀다. numpy polyfit 1차와 같음). */
    static double slope(double[] y) {
        double mx = 0;
        double my = 0;
        int n = 0;
        for (int i = 0; i < y.length; i++) {
            if (!Double.isNaN(y[i])) {
                mx += i;
                my += y[i];
                n++;
            }
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < y.length; i++) {
            if (!Double.isNaN(y[i])) {
                sxy += (i - mx) * (y[i] - my);
                sxx += (i - mx) * (i - mx);
            }
        }
        return sxx == 0 ? 0.0 : sxy / sxx;
    }
}
