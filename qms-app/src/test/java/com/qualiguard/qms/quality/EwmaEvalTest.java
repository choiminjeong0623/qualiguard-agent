package com.qualiguard.qms.quality;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** EWMA 비교(로드맵 A4)의 관리도 계산. */
class EwmaEvalTest {

    private static EwmaEval.Ewma baseline(double lambda) {
        EwmaEval.Ewma e = new EwmaEval.Ewma(lambda);
        for (int i = 0; i < SpcEngine.BASELINE_SHOTS; i++) {
            assertThat(e.add(i % 2 == 0 ? 9 : 11)).as("기준 구간에서는 경보 없음").isFalse();   // μ 10, σ ≈ 1.005
        }
        return e;
    }

    @Test
    void 기준값_그대로면_경보가_없다() {
        EwmaEval.Ewma e = baseline(0.2);
        for (int i = 0; i < 200; i++) {
            assertThat(e.add(10)).isFalse();
        }
    }

    @Test
    void 작은_평균_이동이_쌓이면_경보를_낸다() {
        // 1σ 이동: 개별값 ±4σ 규칙으로는 안 걸리지만 EWMA(λ 0.2, L 3) 의 정상 상태 한계 ≈ 1.0σ 를 넘는다
        EwmaEval.Ewma e = baseline(0.2);
        int firstAlarm = -1;
        for (int i = 1; i <= 50 && firstAlarm < 0; i++) {
            if (e.add(11.2)) {
                firstAlarm = i;
            }
        }
        assertThat(firstAlarm).isBetween(3, 20);
    }

    @Test
    void 처음에는_한계가_좁다() {
        // t = 1 일 때 한계 폭 = L·σ·λ (= 0.6σ), 정상 상태(1.0σ)보다 좁다 → 첫 값이 3.5σ 벗어나면(z 는 0.7σ 이동) 바로 경보
        EwmaEval.Ewma e = baseline(0.2);
        assertThat(e.add(10 + 3.5)).isTrue();
    }
}
