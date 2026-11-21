package com.qualiguard.qms.agent.action;

/**
 * 조치 효과 판정: 조치 전후 센서 평균과 조치 뒤 경보 수로 "이상이 해소됐나"를 정한다. 순수 함수.
 *
 * 해소(RESOLVED) = 조치 뒤 경보가 없고, 목표 센서가 있으면 목표값과의 차이가 조치 전의 절반 이하로 줄었음.
 */
public class EffectEvaluator {

    public enum Verdict { RESOLVED, NOT_RESOLVED, NOT_ENOUGH_DATA }

    /**
     * @param meanBefore  조치 전 목표 센서 평균 (목표 센서가 없으면 null)
     * @param meanAfter   조치 뒤 목표 센서 평균
     * @param target      그 센서의 규격 목표값 (sensor_spec.target)
     * @param alarmsAfter 조치 뒤 확인 구간의 경보 수
     * @param shotsAfter  조치 뒤 확인 구간에 실제로 들어온 shot 수
     */
    public record Window(Double meanBefore, Double meanAfter, Double target, int alarmsAfter, int shotsAfter) {
    }

    public record Effect(Verdict verdict, String detail) {
    }

    private final int minShots;
    private final double recoverRatio;

    /**
     * @param minShots     판정에 필요한 최소 shot 수 (결측으로 모자라면 판정을 미룬다)
     * @param recoverRatio 목표값과의 차이가 조치 전의 몇 배 이하면 회복으로 볼지 (0.5 = 절반)
     */
    public EffectEvaluator(int minShots, double recoverRatio) {
        this.minShots = minShots;
        this.recoverRatio = recoverRatio;
    }

    public Effect evaluate(Window w) {
        if (w.shotsAfter() < minShots) {
            return new Effect(Verdict.NOT_ENOUGH_DATA, "조치 뒤 shot " + w.shotsAfter() + "개 < " + minShots);
        }
        if (w.alarmsAfter() > 0) {
            return new Effect(Verdict.NOT_RESOLVED, "조치 뒤에도 경보 " + w.alarmsAfter() + "건");
        }
        if (w.target() == null || w.meanBefore() == null || w.meanAfter() == null) {
            return new Effect(Verdict.RESOLVED, "조치 뒤 경보 없음");
        }
        double gapBefore = Math.abs(w.meanBefore() - w.target());
        double gapAfter = Math.abs(w.meanAfter() - w.target());
        String numbers = String.format("목표 %.2f, 조치 전 평균 %.2f(차이 %.2f) → 조치 뒤 %.2f(차이 %.2f)",
                w.target(), w.meanBefore(), gapBefore, w.meanAfter(), gapAfter);
        if (gapAfter <= gapBefore * recoverRatio) {
            return new Effect(Verdict.RESOLVED, "경보 없음, " + numbers);
        }
        return new Effect(Verdict.NOT_RESOLVED, "경보는 없지만 회복 부족, " + numbers);
    }
}