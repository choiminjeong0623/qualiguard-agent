package com.qualiguard.qms.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 다변량 관리도 시험 (로드맵 A4 후반): UCI SECOM 에 PCA 기반 Hotelling T² 와 SPE(Q) 를 적용한다.
 * 센서별 관리도(SecomValidation)가 경보를 쏟아 내고 불합격을 가려내지 못한 것과 비교하려는 것이다.
 *
 * 실행: ./scripts/fetch_secom.sh → cd qms-app && ./gradlew secomT2   (결과: data/secom/secom_t2_report.md)
 *
 * 방법
 *  - 시간 순 정렬, 처음 baseline 건(100: 센서별 엔진과 같음 / 300: 기준을 늘렸을 때)을 기준 구간으로 쓴다.
 *  - 센서 고르기는 SecomValidation 과 같다 (기준 구간에서 절반 넘게 비었거나 값이 하나뿐이면 제외).
 *  - 기준 구간 평균·표준편차로 표준화, 빈 값은 0(= 기준 평균)으로 채운다.
 *  - 센서 수(수백)가 기준 건수보다 많아 공분산 역행렬이 없으므로, PCA 로 분산 90% 를 설명하는 주성분 k 개만 쓴다.
 *    T² = Σ t_j² / λ_j (주성분 안의 이상), SPE = 주성분으로 설명되지 않는 나머지 제곱합 (상관 구조가 깨진 이상).
 *  - 관리 한계: 기준 구간 값의 99 백분위 (기준 구간으로 PCA 를 맞췄으므로 한계가 낙관적일 수 있다).
 *  - 채점: 감시 구간(기준 뒤)에서 불합격·합격 건의 경보 비율, 경보 건 중 불합격 비율, T²·SPE 점수의 AUC.
 *    AUC 0.5 = 무작위, 1.0 = 완벽 구분.
 */
public final class SecomT2 {

    static final double VARIANCE = 0.90;
    static final double PERCENTILE = 0.99;

    /** 기준 구간으로 맞춘 PCA 모형. */
    record Model(double[] mean, double[] sd, double[][] components, double[] eigen, double t2Limit, double speLimit) {

        double[] standardize(double[] raw) {
            double[] z = new double[mean.length];
            for (int f = 0; f < z.length; f++) {
                double v = raw[f];
                z[f] = Double.isNaN(v) ? 0 : (v - mean[f]) / sd[f];
            }
            return z;
        }

        /** {T², SPE} */
        double[] score(double[] raw) {
            double[] z = standardize(raw);
            double t2 = 0;
            double[] recon = new double[z.length];
            for (int j = 0; j < components.length; j++) {
                double t = dot(z, components[j]);
                t2 += t * t / eigen[j];
                for (int f = 0; f < z.length; f++) {
                    recon[f] += t * components[j][f];
                }
            }
            double spe = 0;
            for (int f = 0; f < z.length; f++) {
                double r = z[f] - recon[f];
                spe += r * r;
            }
            return new double[] {t2, spe};
        }
    }

    /** baseline 행(원래 값, NaN 허용)으로 모형을 맞춘다. */
    static Model fit(List<double[]> baseline) {
        int n = baseline.size();
        int p = baseline.get(0).length;
        double[] mean = new double[p];
        double[] sd = new double[p];
        for (int f = 0; f < p; f++) {
            double s = 0;
            int c = 0;
            for (double[] row : baseline) {
                if (!Double.isNaN(row[f])) {
                    s += row[f];
                    c++;
                }
            }
            mean[f] = c == 0 ? 0 : s / c;
            double ss = 0;
            for (double[] row : baseline) {
                if (!Double.isNaN(row[f])) {
                    ss += (row[f] - mean[f]) * (row[f] - mean[f]);
                }
            }
            sd[f] = c < 2 ? 1 : Math.max(Math.sqrt(ss / (c - 1)), 1e-9);
        }
        Model pre = new Model(mean, sd, new double[0][], new double[0], 0, 0);
        double[][] x = new double[n][];
        for (int i = 0; i < n; i++) {
            x[i] = pre.standardize(baseline.get(i));
        }
        // 그람 행렬 G = X Xᵀ / (n−1) (n×n) 의 고유값 = 공분산 행렬의 고유값. n 이 p 보다 작을 때 훨씬 싸다
        double[][] g = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = i; k < n; k++) {
                g[i][k] = g[k][i] = dot(x[i], x[k]) / (n - 1);
            }
        }
        double[][] vecs = new double[n][n];
        double[] vals = jacobi(g, vecs);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(vals[b], vals[a]));
        double total = Arrays.stream(vals).filter(v -> v > 0).sum();
        List<double[]> comps = new ArrayList<>();
        List<Double> eig = new ArrayList<>();
        double acc = 0;
        for (int r = 0; r < n - 1 && acc < VARIANCE * total; r++) {
            int idx = order[r];
            double lambda = vals[idx];
            if (lambda <= 1e-10) {
                break;
            }
            // 공분산의 고유벡터 = Xᵀ u / ‖Xᵀ u‖
            double[] v = new double[p];
            for (int i = 0; i < n; i++) {
                double u = vecs[i][idx];
                for (int f = 0; f < p; f++) {
                    v[f] += x[i][f] * u;
                }
            }
            double norm = Math.sqrt(dot(v, v));
            for (int f = 0; f < p; f++) {
                v[f] /= norm;
            }
            comps.add(v);
            eig.add(lambda);
            acc += lambda;
        }
        double[] e = eig.stream().mapToDouble(Double::doubleValue).toArray();
        Model m = new Model(mean, sd, comps.toArray(new double[0][]), e, 0, 0);
        double[] t2s = new double[n];
        double[] spes = new double[n];
        for (int i = 0; i < n; i++) {
            double[] s = m.score(baseline.get(i));
            t2s[i] = s[0];
            spes[i] = s[1];
        }
        return new Model(mean, sd, m.components(), e, percentile(t2s, PERCENTILE), percentile(spes, PERCENTILE));
    }

    /** 대칭 행렬의 고유값 (순환 야코비). vecs 의 열이 고유벡터가 된다. a 는 망가진다. */
    static double[] jacobi(double[][] a, double[][] vecs) {
        int n = a.length;
        for (int i = 0; i < n; i++) {
            Arrays.fill(vecs[i], 0);
            vecs[i][i] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    off += a[i][j] * a[i][j];
                }
            }
            if (off < 1e-22) {
                break;
            }
            for (int pI = 0; pI < n; pI++) {
                for (int q = pI + 1; q < n; q++) {
                    if (Math.abs(a[pI][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[pI][pI]) / (2 * a[pI][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][pI];
                        double akq = a[k][q];
                        a[k][pI] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[pI][k];
                        double aqk = a[q][k];
                        a[pI][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = vecs[k][pI];
                        double vkq = vecs[k][q];
                        vecs[k][pI] = c * vkp - s * vkq;
                        vecs[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = a[i][i];
        }
        return d;
    }

    /** 양성(불합격) 점수가 음성보다 클 확률 (Mann-Whitney, 동점은 0.5). */
    static double auc(List<Double> pos, List<Double> neg) {
        if (pos.isEmpty() || neg.isEmpty()) {
            return Double.NaN;
        }
        double wins = 0;
        for (double a : pos) {
            for (double b : neg) {
                wins += a > b ? 1 : a == b ? 0.5 : 0;
            }
        }
        return wins / ((double) pos.size() * neg.size());
    }

    static double percentile(double[] xs, double q) {
        double[] s = xs.clone();
        Arrays.sort(s);
        int i = (int) Math.ceil(q * s.length) - 1;
        return s[Math.max(0, Math.min(s.length - 1, i))];
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "../data/secom");
        List<SecomValidation.Sample> samples = SecomValidation.load(dir);
        StringBuilder sb = new StringBuilder("## UCI SECOM 다변량 관리도 (PCA 기반 Hotelling T² · SPE)\n\n");
        sb.append("`cd qms-app && ./gradlew secomT2` 로 다시 만들 수 있습니다. 센서별 관리도 결과(2-9절)와 비교합니다.\n\n");
        sb.append("| 기준 구간 | 센서 | 주성분(분산 90%) | 감시 구간 (불합격) | 경보 비율 불합격 / 합격 | 경보 중 불합격 | AUC T² | AUC SPE |\n");
        sb.append("|---:|---:|---:|---|---|---:|---:|---:|\n");
        for (int base : new int[] {SpcEngine.BASELINE_SHOTS, 300}) {
            sb.append(run(samples, base));
        }
        sb.append("\n- 경보: T² 또는 SPE 가 기준 구간 99 백분위를 넘은 건. 감시 구간 불합격 비율보다 '경보 중 불합격' 이 높아야 쓸모가 있습니다.\n");
        sb.append("- AUC: 불합격 건의 점수가 합격 건보다 높을 확률. 0.5 면 구분 못함.\n");
        sb.append("- 기준 구간으로 PCA 를 맞추고 같은 구간으로 한계를 정해 한계가 낮게(경보가 많게) 잡힐 수 있습니다.\n");
        System.out.println(sb);
        Files.writeString(dir.resolve("secom_t2_report.md"), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("저장: " + dir.resolve("secom_t2_report.md"));
    }

    static String run(List<SecomValidation.Sample> samples, int base) {
        int features = samples.get(0).values().length;
        List<Integer> keep = new ArrayList<>();
        for (int f = 0; f < features; f++) {
            List<Double> b = new ArrayList<>();
            for (int i = 0; i < base; i++) {
                double v = samples.get(i).values()[f];
                if (!Double.isNaN(v)) {
                    b.add(v);
                }
            }
            if (b.size() >= base / 2 && b.stream().distinct().count() > 1) {
                keep.add(f);
            }
        }
        List<double[]> rows = new ArrayList<>();
        for (SecomValidation.Sample s : samples) {
            double[] r = new double[keep.size()];
            for (int j = 0; j < r.length; j++) {
                r[j] = s.values()[keep.get(j)];
            }
            rows.add(r);
        }
        Model m = fit(rows.subList(0, base));
        int fails = 0;
        int failAl = 0;
        int passAl = 0;
        int alarmed = 0;
        List<Double> t2Pos = new ArrayList<>();
        List<Double> t2Neg = new ArrayList<>();
        List<Double> spePos = new ArrayList<>();
        List<Double> speNeg = new ArrayList<>();
        for (int i = base; i < samples.size(); i++) {
            boolean fail = samples.get(i).fail();
            double[] sc = m.score(rows.get(i));
            boolean al = sc[0] > m.t2Limit() || sc[1] > m.speLimit();
            alarmed += al ? 1 : 0;
            if (fail) {
                fails++;
                failAl += al ? 1 : 0;
                t2Pos.add(sc[0]);
                spePos.add(sc[1]);
            } else {
                passAl += al ? 1 : 0;
                t2Neg.add(sc[0]);
                speNeg.add(sc[1]);
            }
        }
        int n = samples.size() - base;
        int passes = n - fails;
        return String.format(Locale.ROOT, "| %d | %d | %d | %d (%d, %.1f%%) | %.1f%% / %.1f%% | %.1f%% | %.3f | %.3f |%n",
                base, keep.size(), m.components().length, n, fails, 100.0 * fails / n, 100.0 * failAl / fails,
                100.0 * passAl / passes, alarmed == 0 ? 0 : 100.0 * (failAl) / alarmed, auc(t2Pos, t2Neg),
                auc(spePos, speNeg));
    }

    private SecomT2() {
    }
}
