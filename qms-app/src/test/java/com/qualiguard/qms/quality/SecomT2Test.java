package com.qualiguard.qms.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** 다변량 관리도(로드맵 A4 후반) 계산 확인. 실제 SECOM 실행은 데이터가 있는 곳에서 ./gradlew secomT2. */
class SecomT2Test {

    @Test
    void 야코비_고유값이_알려진_값과_같다() {
        double[][] a = {{2, 1, 0}, {1, 2, 0}, {0, 0, 5}};
        double[] vals = SecomT2.jacobi(a, new double[3][3]);
        Arrays.sort(vals);
        assertThat(vals[0]).isCloseTo(1, within(1e-9));
        assertThat(vals[1]).isCloseTo(3, within(1e-9));
        assertThat(vals[2]).isCloseTo(5, within(1e-9));
    }

    /** 센서 40개가 잠재 요인 3개로 움직이는 공정. 센서 수 > 기준 건수(30) 라 PCA 가 필요하다. */
    private static List<double[]> process(Random r, int n, double shiftLatent, boolean breakCorrelation) {
        double[][] load = new double[3][40];
        Random lr = new Random(7);
        for (double[] row : load) {
            for (int f = 0; f < row.length; f++) {
                row[f] = lr.nextGaussian();
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] z = {r.nextGaussian() + shiftLatent, r.nextGaussian(), r.nextGaussian()};
            double[] x = new double[40];
            for (int f = 0; f < 40; f++) {
                x[f] = 0.1 * r.nextGaussian();
                for (int k = 0; k < 3; k++) {
                    x[f] += z[k] * load[k][f];
                }
            }
            if (breakCorrelation) {
                x[5] += 3;                                  // 센서 하나만 혼자 움직임 (상관 구조 깨짐)
            }
            out.add(x);
        }
        return out;
    }

    @Test
    void 주성분_방향의_이동은_T2_가_상관이_깨지면_SPE_가_잡는다() {
        Random r = new Random(1);
        SecomT2.Model m = SecomT2.fit(process(r, 30, 0, false));
        assertThat(m.components().length).isBetween(1, 3);

        List<Double> normalT2 = new ArrayList<>();
        List<Double> normalSpe = new ArrayList<>();
        for (double[] x : process(r, 200, 0, false)) {
            double[] s = m.score(x);
            normalT2.add(s[0]);
            normalSpe.add(s[1]);
        }
        List<Double> shiftT2 = new ArrayList<>();
        for (double[] x : process(r, 200, 4, false)) {
            shiftT2.add(m.score(x)[0]);
        }
        List<Double> brokenSpe = new ArrayList<>();
        for (double[] x : process(r, 200, 0, true)) {
            brokenSpe.add(m.score(x)[1]);
        }
        assertThat(SecomT2.auc(shiftT2, normalT2)).isGreaterThan(0.9);
        assertThat(SecomT2.auc(brokenSpe, normalSpe)).isGreaterThan(0.9);
    }

    @Test
    void AUC_는_완벽하면_1_뒤집히면_0_같으면_반() {
        assertThat(SecomT2.auc(List.of(3.0, 4.0), List.of(1.0, 2.0))).isEqualTo(1.0);
        assertThat(SecomT2.auc(List.of(1.0), List.of(2.0))).isEqualTo(0.0);
        assertThat(SecomT2.auc(List.of(1.0), List.of(1.0))).isEqualTo(0.5);
    }
}
