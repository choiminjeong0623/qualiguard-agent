package com.qualiguard.qms.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.quality.DetectionMetrics.Confusion;
import com.qualiguard.qms.quality.DetectionMetrics.Episode;
import com.qualiguard.qms.quality.DetectionMetrics.Truth;

/** 탐지 채점 (로드맵 E2): 혼동행렬·고장 구간·평균과 표준편차를 손으로 센 값과 맞춰 본다. */
class DetectionMetricsTest {

    /** shot 1~(n) 정답. [faultFrom, faultTo] 는 고장, defectAt 은 불량. */
    private static NavigableMap<Long, Truth> truth(int n, long faultFrom, long faultTo, long defectAt) {
        NavigableMap<Long, Truth> t = DetectionMetrics.truthMap();
        for (long s = 1; s <= n; s++) {
            boolean f = s >= faultFrom && s <= faultTo;
            t.put(s, new Truth(f ? List.of("heater") : List.of(), s == defectAt));
        }
        return t;
    }

    private static NavigableSet<Long> alarms(long... shots) {
        NavigableSet<Long> a = DetectionMetrics.alarmSet();
        for (long s : shots) {
            a.add(s);
        }
        return a;
    }

    @Test
    void 창_네_종류를_센다() {
        // 기준 100 shot 뒤 300 shot = 창 6개: 101-150, 151-200, 201-250, 251-300, 301-350, 351-400
        // 고장 201~300 → 창 3·4 가 고장 창
        NavigableMap<Long, Truth> t = truth(400, 201, 300, -1);
        // 창 4(TP), 창 6(FP). 창 3 은 경보 없음(FN), 창 1·2·5 는 TN
        Confusion c = DetectionMetrics.confusion(t, alarms(260, 399), 100);
        assertThat(c).isEqualTo(new Confusion(1, 1, 1, 3));
        assertThat(c.precision()).isEqualTo(0.5);
        assertThat(c.recall()).isEqualTo(0.5);
        assertThat(c.f1()).isEqualTo(0.5);
        assertThat(c.fpr()).isEqualTo(0.25);
        assertThat(c.fnr()).isEqualTo(0.5);
    }

    @Test
    void 기준_구간의_경보와_자투리_창은_세지_않는다() {
        NavigableMap<Long, Truth> t = truth(180, 999, 999, -1);   // 고장 없음, 창 1개(101-150) + 자투리 30 shot
        Confusion c = DetectionMetrics.confusion(t, alarms(50, 170), 100);
        assertThat(c).isEqualTo(new Confusion(0, 0, 0, 1));
        assertThat(c.precision()).isNaN();   // 경보 창이 없으면 정밀도를 계산할 수 없다
        assertThat(c.recall()).isNaN();
        assertThat(c.fpr()).isZero();
    }

    @Test
    void 고장_구간마다_첫_경보와_첫_불량을_찾는다() {
        NavigableMap<Long, Truth> t = truth(400, 201, 300, 250);
        List<Episode> eps = DetectionMetrics.episodes("IMM-01", t, alarms(150, 230, 260));
        assertThat(eps).hasSize(1);
        Episode e = eps.get(0);
        assertThat(e.start()).isEqualTo(201);
        assertThat(e.end()).isEqualTo(300);
        assertThat(e.firstAlarm()).isEqualTo(230);   // 고장 전 경보(150)는 탐지로 치지 않는다
        assertThat(e.delay()).isEqualTo(29);
        assertThat(e.beforeFirstDefect()).isTrue();  // 230 < 첫 불량 250
    }

    @Test
    void 고장_구간_안에_경보가_없으면_놓침() {
        NavigableMap<Long, Truth> t = truth(400, 201, 300, 220);
        Episode e = DetectionMetrics.episodes("IMM-01", t, alarms(350)).get(0);
        assertThat(e.detected()).isFalse();
        assertThat(e.delay()).isNull();
        assertThat(e.beforeFirstDefect()).isFalse();
    }

    @Test
    void 같은_고장이_끊겼다_다시_나오면_구간이_둘() {
        NavigableMap<Long, Truth> t = DetectionMetrics.truthMap();
        for (long s = 1; s <= 10; s++) {
            boolean f = s <= 3 || s >= 7;
            t.put(s, new Truth(f ? List.of("spike") : List.of(), false));
        }
        List<Episode> eps = DetectionMetrics.episodes("IMM-02", t, alarms(8));
        assertThat(eps).extracting(Episode::start, Episode::end).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, 3L), org.assertj.core.groups.Tuple.tuple(7L, 10L));
        assertThat(eps.get(0).detected()).isFalse();
        assertThat(eps.get(1).delay()).isEqualTo(1);
    }

    @Test
    void 평균과_표본_표준편차() {
        assertThat(DetectionMetrics.mean(List.of(1.0, 2.0, 3.0, Double.NaN))).isEqualTo(2.0);
        assertThat(DetectionMetrics.stdev(List.of(1.0, 2.0, 3.0))).isCloseTo(1.0, within(1e-9));
        assertThat(DetectionMetrics.stdev(List.of(5.0))).isZero();
        assertThat(DetectionMetrics.mean(List.of())).isNaN();
    }
}
