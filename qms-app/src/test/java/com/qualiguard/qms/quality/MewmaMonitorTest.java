package com.qualiguard.qms.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class MewmaMonitorTest {

    static final int P = MewmaMonitor.SENSORS.size();

    /** 센서 0·1 이 강하게 함께 움직이는 정상 데이터 + shift */
    static Map<String, Double> shot(Random r, double[] shift) {
        double common = r.nextGaussian();
        Map<String, Double> m = new HashMap<>();
        for (int j = 0; j < P; j++) {
            double v = j < 2 ? common + 0.2 * r.nextGaussian() : r.nextGaussian();
            m.put(MewmaMonitor.SENSORS.get(j), 100 + v + shift[j]);
        }
        return m;
    }

    @Test
    void 역행렬이_맞다() {
        double[][] a = {{4, 1, 0}, {1, 3, 1}, {0, 1, 2}};
        double[][] inv = MewmaMonitor.invert(a);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += a[i][k] * inv[k][j];
                }
                assertThat(s).isCloseTo(i == j ? 1 : 0, within(1e-12));
            }
        }
    }

    @Test
    void 정상_데이터에서는_경보가_드물다() {
        Random r = new Random(1);
        MewmaMonitor m = new MewmaMonitor(0.2, 35.26);
        int alarms = 0;
        for (int t = 0; t < 3000; t++) {
            alarms += m.add("IMM-01", t, shot(r, new double[P])) != null ? 1 : 0;
        }
        assertThat(alarms).isLessThanOrEqualTo(2);
    }

    @Test
    void 센서_관계가_깨지면_센서별_한계_안이라도_잡고_원인_센서를_가리킨다() {
        Random r = new Random(2);
        MewmaMonitor m = new MewmaMonitor(0.2, 35.26);
        for (int t = 0; t < 300; t++) {
            assertThat(m.add("IMM-01", t, shot(r, new double[P]))).isNull();
        }
        // 센서 0 만 +1σ (개별값 ±4σ 규칙으로는 안 보이는 크기), 센서 1 은 그대로 → 상관 관계가 깨짐
        double[] shift = new double[P];
        shift[0] = 1.0;
        MewmaMonitor.Alarm first = null;
        int t = 300;
        for (; t < 400 && first == null; t++) {
            first = m.add("IMM-01", t, shot(r, shift));
        }
        assertThat(first).isNotNull();
        assertThat(t - 300).isLessThan(30);
        assertThat(first.topSensor()).isEqualTo(MewmaMonitor.SENSORS.get(0));
        assertThat(first.t2()).isGreaterThan(first.limit());
        // 같은 설비는 cooldown(100 shot) 동안 다시 울리지 않는다
        for (int k = 0; k < 50; k++, t++) {
            assertThat(m.add("IMM-01", t, shot(r, shift))).isNull();
        }
    }

    @Test
    void 빠진_값은_그_센서_z_를_그대로_두고_설비별로_따로_배운다() {
        Random r = new Random(3);
        MewmaMonitor m = new MewmaMonitor(0.1, 29.88);
        for (int t = 0; t < 200; t++) {
            Map<String, Double> s = shot(r, new double[P]);
            if (t % 7 == 0) {
                s.remove(MewmaMonitor.SENSORS.get(3));
            }
            assertThat(m.add("IMM-01", t, s)).isNull();
        }
        // 다른 설비는 아직 기준 구간
        assertThat(m.add("IMM-02", 0, shot(r, new double[] {50, 50, 50, 50, 50, 50, 50}))).isNull();
        assertThatThrownBy(() -> new MewmaMonitor(0, 30)).isInstanceOf(IllegalArgumentException.class);
    }
}
