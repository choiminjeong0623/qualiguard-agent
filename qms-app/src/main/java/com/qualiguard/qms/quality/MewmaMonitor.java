package com.qualiguard.qms.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다변량 이상 탐지: MEWMA (Multivariate EWMA, Lowry 1992). 센서 7개를 한꺼번에 보고 '함께 조금씩 움직이는' 드리프트를 잡는다.
 *
 * SPC 규칙은 센서를 하나씩 본다. 히터 열화처럼 실린더 온도가 조금 내리고 사출 압력·모터 전류가 조금 오르는 고장은
 * 센서 하나하나로는 한계 안이라도, 센서 사이의 정상적인 관계(공분산)에서 벗어난 것은 더 일찍 보일 수 있다.
 *
 * 계산 (설비별)
 *  - 기준 구간(처음 100 shot): 센서 평균 μ 와 공분산 Σ
 *  - z_t = λ(x_t − μ) + (1 − λ) z_{t−1}, 값이 빠진 센서는 그 자리 z 를 그대로 둔다
 *  - T²_t = z_tᵀ Σ_z⁻¹ z_t, Σ_z = λ/(2−λ)·(1 − (1−λ)^{2t})·Σ
 *  - T² 가 한계 h 를 넘으면 경보. 같은 설비에서 cooldown shot 안의 반복 경보는 억제 (SPC 와 같은 100 shot)
 * 경보에는 기여도(T² 를 센서별로 나눈 값)가 큰 센서를 붙인다.
 *
 * 운영 SpcEngine 과 별개로 동작한다 (qg.quality.mewma.mode = off / shadow, QualityStreamsConfig). 경보 판정은 바꾸지 않는다.
 */
public final class MewmaMonitor {

    /** 센서 순서 (SensorCatalog 순서) */
    public static final List<String> SENSORS = List.copyOf(SensorCatalog.SENSORS.keySet());

    /**
     * 경보 1건.
     *
     * @param t2           T² 값
     * @param limit        한계 h
     * @param contributions 센서별 기여도 (큰 순서)
     */
    public record Alarm(String equipmentId, long shotNo, double t2, double limit, Map<String, Double> contributions) {

        /** 기여도가 가장 큰 센서 */
        public String topSensor() {
            return contributions.keySet().iterator().next();
        }
    }

    /** 설비 하나의 상태. */
    static final class State {
        final List<double[]> baseline = new ArrayList<>();
        double[] mean;
        double[][] covInv;
        double[] z;
        long t;
        long count;
        Long lastAlarm;
    }

    private final double lambda;
    private final double limit;
    private final int baselineShots;
    private final int cooldown;
    private final Map<String, State> states = new LinkedHashMap<>();

    public MewmaMonitor(double lambda, double limit) {
        this(lambda, limit, SpcEngine.BASELINE_SHOTS, SpcEngine.COOLDOWN_SHOTS);
    }

    public MewmaMonitor(double lambda, double limit, int baselineShots, int cooldown) {
        if (lambda <= 0 || lambda > 1) {
            throw new IllegalArgumentException("λ 는 0~1: " + lambda);
        }
        this.lambda = lambda;
        this.limit = limit;
        this.baselineShots = baselineShots;
        this.cooldown = cooldown;
    }

    public double lambda() {
        return lambda;
    }

    public double limit() {
        return limit;
    }

    /**
     * 깨끗한 값(SpcEngine 이 중복·범위 밖·늦은 shot 을 걸러 낸 값) 하나를 넣는다.
     *
     * @return 경보면 Alarm, 아니면 null
     */
    public synchronized Alarm add(String equipmentId, long shotNo, Map<String, Double> cleanValues) {
        State s = states.computeIfAbsent(equipmentId, k -> new State());
        int p = SENSORS.size();
        double[] x = new double[p];
        for (int j = 0; j < p; j++) {
            Double v = cleanValues.get(SENSORS.get(j));
            x[j] = v == null ? Double.NaN : v;
        }
        s.count++;
        if (s.mean == null) {
            s.baseline.add(x);
            if (s.baseline.size() == baselineShots) {
                fit(s);
            }
            return null;
        }
        s.t++;
        for (int j = 0; j < p; j++) {
            if (!Double.isNaN(x[j])) {
                s.z[j] = lambda * (x[j] - s.mean[j]) + (1 - lambda) * s.z[j];
            }
        }
        double factor = lambda / (2 - lambda) * (1 - Math.pow(1 - lambda, 2 * s.t));
        double t2 = quad(s.covInv, s.z) / factor;
        if (t2 <= limit || (s.lastAlarm != null && s.count - s.lastAlarm < cooldown)) {
            return null;
        }
        s.lastAlarm = s.count;
        return new Alarm(equipmentId, shotNo, t2, limit, contributions(s, factor));
    }

    /**
     * 센서별 기여도: z_j · (Σ_z⁻¹ z)_j. 더하면 T² 가 된다.
     * 센서 하나만 보는 z_j² / σ_j² 와 달리 '함께 움직여야 할 센서와 따로 움직인' 센서도 크게 나온다 (상관 관계가 깨진 경우).
     */
    private static Map<String, Double> contributions(State s, double factor) {
        int p = SENSORS.size();
        List<Map.Entry<String, Double>> e = new ArrayList<>();
        for (int j = 0; j < p; j++) {
            double row = 0;
            for (int k = 0; k < p; k++) {
                row += s.covInv[j][k] * s.z[k];
            }
            double c = s.z[j] * row / factor;
            e.add(Map.entry(SENSORS.get(j), Math.round(c * 100) / 100.0));
        }
        e.sort(Comparator.comparingDouble((Map.Entry<String, Double> x) -> -x.getValue()));
        Map<String, Double> out = new LinkedHashMap<>();
        e.forEach(x -> out.put(x.getKey(), x.getValue()));
        return out;
    }

    private static void fit(State s) {
        int p = SENSORS.size();
        int n = s.baseline.size();
        double[] mean = new double[p];
        int[] cnt = new int[p];
        for (double[] x : s.baseline) {
            for (int j = 0; j < p; j++) {
                if (!Double.isNaN(x[j])) {
                    mean[j] += x[j];
                    cnt[j]++;
                }
            }
        }
        for (int j = 0; j < p; j++) {
            mean[j] = cnt[j] == 0 ? 0 : mean[j] / cnt[j];
        }
        double[][] cov = new double[p][p];
        for (double[] x : s.baseline) {
            for (int a = 0; a < p; a++) {
                double da = Double.isNaN(x[a]) ? 0 : x[a] - mean[a];   // 빠진 값은 평균으로 채운 것과 같다
                for (int b = 0; b < p; b++) {
                    double db = Double.isNaN(x[b]) ? 0 : x[b] - mean[b];
                    cov[a][b] += da * db;
                }
            }
        }
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < p; b++) {
                cov[a][b] /= Math.max(1, n - 1);
            }
            cov[a][a] = Math.max(cov[a][a], 1e-9) * (1 + 1e-6);   // 수치 안정
        }
        s.mean = mean;
        s.covInv = invert(cov);
        s.z = new double[p];
        s.baseline.clear();
    }

    static double quad(double[][] a, double[] z) {
        double sum = 0;
        for (int i = 0; i < z.length; i++) {
            double row = 0;
            for (int j = 0; j < z.length; j++) {
                row += a[i][j] * z[j];
            }
            sum += z[i] * row;
        }
        return sum;
    }

    /** 가우스-조르당 역행렬 (7×7 이라 충분). */
    static double[][] invert(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, a[i], 0, n);
            a[i][n + i] = 1;
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) {
                    pivot = r;
                }
            }
            double[] tmp = a[c];
            a[c] = a[pivot];
            a[pivot] = tmp;
            double d = a[c][c];
            if (Math.abs(d) < 1e-15) {
                throw new IllegalStateException("공분산 행렬을 뒤집을 수 없습니다");
            }
            for (int k = 0; k < 2 * n; k++) {
                a[c][k] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r != c && a[r][c] != 0) {
                    double f = a[r][c];
                    for (int k = 0; k < 2 * n; k++) {
                        a[r][k] -= f * a[c][k];
                    }
                }
            }
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            out[i] = Arrays.copyOfRange(a[i], n, 2 * n);
        }
        return out;
    }
}
