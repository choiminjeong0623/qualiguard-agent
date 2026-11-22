package com.qualiguard.qms.quality;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SPC(통계적 공정 관리) 기반 이상 탐지 엔진. 외부 라이브러리에 의존하지 않는 순수 로직이라 단독으로 시험할 수 있다.
 *
 * 1) 기준 구간(Phase I): 설비별 처음 100 shot 으로 센서별 평균(μ)·표준편차(σ)와 기준 불량률을 추정한다.
 * 2) 감시 구간(Phase II): 아래 규칙으로 판정한다. 오경보를 줄이기 위해 '연속 2회'처럼 확인 조건을 둔다.
 *    - INDIVIDUAL : 개별값이 μ ± 4σ 를 벗어남                    → 갑작스러운 튐(센서 이상·급변)
 *    - XBAR       : 5 shot 부분군 평균이 μ ± 3σ/√5 를 연속 2회 벗어남 → 서서히 움직이는 평균(드리프트)
 *    - S_CHART    : 부분군 표준편차가 1.964σ(B6, n=5)를 연속 2회 넘음 → 산포(흔들림) 증가
 *    - P_CHART    : 50 shot 구간 불량률이 p̄ + 4√(p̄(1-p̄)/50) 를 넘음 → 불량률 증가
 * 3) 같은 규칙·센서의 경보는 100 shot 동안 다시 내지 않는다 (경보 폭주 방지, 에이전트 호출 비용 절감).
 *
 * 결측 처리(ADR-017): 값이 없는 센서는 그 shot 에서 invalid 로 빼고 나머지 센서만 판정한다.
 * 검사 결과가 없는 shot 은 불량률(기준 불량률·p 관리도)에 넣지 않는다. 기준 불량률은 검사 결과가 있는
 * 처음 100 shot 으로 정한다.
 *
 * 전송 이상(ADR-017):
 * - 중복(같은 messageId)은 최근 200개 안에서 한 번만 반영한다.
 * - 늦게 온 shot(그 설비에서 이미 판정한 shot 보다 ts 가 앞섬)은 실시간 상태(부분군·연속 횟수·기준값)를 건드리지 않고
 *   따로 다시 판정한다(ADR-019, processLate). 부분군·연속 판정은 시간 순서를 전제로 하므로 실시간 흐름에는 넣지 않는다.
 * - shot 이 통째로 빠져 shotNo 가 건너뛰어도 받은 shot 만으로 판정한다(빈 shot 을 채우지 않음).
 * - 센서 값이 OUTAGE_SHOTS 번 연속 비면 '센서 끊김'으로 알린다(경보가 아니라 결과의 outageStarted, 회복은 outageEnded).
 */
public class SpcEngine {

    public static final int BASELINE_SHOTS = 100;
    public static final int SUBGROUP_SIZE = 5;
    public static final double INDIVIDUAL_K = 4.0;
    public static final double XBAR_K = 3.0;
    public static final int CONFIRM_RUNS = 2;
    public static final double S_CHART_B6 = 1.964;
    public static final int P_WINDOW = 50;
    public static final double P_K = 4.0;
    public static final double P_FLOOR = 0.01;
    public static final int COOLDOWN_SHOTS = 100;
    public static final int RECENT_KEEP = 30;
    public static final int DEDUP_KEEP = 200;
    public static final int OUTAGE_SHOTS = 5;

    private final Map<String, SensorCatalog.Sensor> sensors;

    /** 사출성형기 센서 7종(SensorCatalog)으로 판정한다. */
    public SpcEngine() {
        this(SensorCatalog.SENSORS);
    }

    /**
     * 다른 센서 목록으로 판정한다 (예: 공개 데이터 UCI SECOM 보조 검증). 판정 규칙은 같다.
     *
     * @param sensors 센서 키 → 정의(이름·단위·물리적으로 가능한 범위)
     */
    public SpcEngine(Map<String, SensorCatalog.Sensor> sensors) {
        this.sensors = sensors;
    }

    /**
     * 처리 결과: 중복 여부, 늦게 와서 판정을 건너뛰었는지, 정제된 값, 값이 없거나 측정 범위를 벗어나 제외한 센서,
     * 발생한 이상 목록, 이번 shot 에서 끊김이 시작된·회복된 센서.
     */
    public record Result(boolean duplicate, boolean late, Map<String, Double> cleanValues, List<String> invalidSensors,
            List<Anomaly> anomalies, List<String> outageStarted, List<String> outageEnded, String lateStatus) {
    }

    /** 늦게 온 shot 재판정 결과 (CleanShot.lateStatus, process_shot.late_status). */
    public static final String LATE_REJUDGED = "REJUDGED";
    public static final String LATE_OUT_OF_WINDOW = "OUT_OF_WINDOW";

    public Result process(EquipmentState s, Shot shot) {
        // ── 중복 제거 (MQTT QoS 1 재전송, 브리지 재시작 후 중복 등) ──
        if (s.recentMessageIds.contains(shot.messageId())) {
            return new Result(true, false, Map.of(), List.of(), List.of(), List.of(), List.of(), null);
        }
        s.recentMessageIds.add(shot.messageId());
        trim(s.recentMessageIds, DEDUP_KEEP);

        // ── 늦게 온 shot: 실시간 판정 상태는 건드리지 않고 따로 다시 판정한다 ──
        Long tsMillis = epochMillis(shot.ts());
        if (tsMillis != null && s.lastTsMillis != null && tsMillis < s.lastTsMillis) {
            return processLate(s, shot, tsMillis);
        }
        Map<String, Double> clean = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        if (tsMillis != null) {
            s.lastTsMillis = tsMillis;
        }
        s.count++;

        List<Anomaly> out = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        List<String> outageStarted = new ArrayList<>();
        List<String> outageEnded = new ArrayList<>();

        // ── 센서별 처리 ──
        for (SensorCatalog.Sensor def : sensors.values()) {
            Double v = shot.values().get(def.key());
            EquipmentState.SensorState ss = s.sensors.computeIfAbsent(def.key(), k -> new EquipmentState.SensorState());
            if (v == null || v.isNaN()) {
                invalid.add(def.key());                 // 값 없음(결측): 이 shot 에서는 판정하지 않는다
                if (++ss.missingRun == OUTAGE_SHOTS) {
                    outageStarted.add(def.key());
                }
                continue;
            }
            if (ss.missingRun >= OUTAGE_SHOTS) {
                outageEnded.add(def.key());
            }
            ss.missingRun = 0;
            if (v < def.min() || v > def.max()) {
                invalid.add(def.key());                 // 전처리: 물리적으로 불가능한 값은 판정에서 제외
                continue;
            }
            clean.put(def.key(), v);
            addRecent(ss, v, tsMillis);

            if (!ss.ready) {
                ss.n++;
                ss.sum += v;
                ss.sumSq += v * v;
                if (ss.n == BASELINE_SHOTS) {
                    ss.mean = ss.sum / ss.n;
                    double var = (ss.sumSq - ss.n * ss.mean * ss.mean) / (ss.n - 1);
                    ss.sigma = Math.max(Math.sqrt(Math.max(var, 0)), 1e-6);
                    ss.ready = true;
                }
                continue;
            }

            // INDIVIDUAL: 개별값 μ ± 4σ
            double lo = ss.mean - INDIVIDUAL_K * ss.sigma;
            double hi = ss.mean + INDIVIDUAL_K * ss.sigma;
            if (v < lo || v > hi) {
                pending.add(new Pending("INDIVIDUAL", def, v, lo, hi));
            }

            // 부분군(5 shot) 판정
            ss.subgroup.add(v);
            if (ss.subgroup.size() == SUBGROUP_SIZE) {
                double m = mean(ss.subgroup);
                double sd = stdev(ss.subgroup, m);
                ss.subgroup.clear();

                double xLo = ss.mean - XBAR_K * ss.sigma / Math.sqrt(SUBGROUP_SIZE);
                double xHi = ss.mean + XBAR_K * ss.sigma / Math.sqrt(SUBGROUP_SIZE);
                if (m > xHi) {
                    ss.xbarHighRun++;
                    ss.xbarLowRun = 0;
                } else if (m < xLo) {
                    ss.xbarLowRun++;
                    ss.xbarHighRun = 0;
                } else {
                    ss.xbarHighRun = 0;
                    ss.xbarLowRun = 0;
                }
                if (ss.xbarHighRun >= CONFIRM_RUNS || ss.xbarLowRun >= CONFIRM_RUNS) {
                    pending.add(new Pending("XBAR", def, m, xLo, xHi));
                }

                double sUcl = S_CHART_B6 * ss.sigma;
                ss.sHighRun = sd > sUcl ? ss.sHighRun + 1 : 0;
                if (ss.sHighRun >= CONFIRM_RUNS) {
                    pending.add(new Pending("S_CHART", def, sd, 0, sUcl));
                }
            }
        }

        judgeInspection(s, shot, pending);

        out.addAll(emit(s, shot, pending, false));
        return new Result(false, false, clean, invalid, out, outageStarted, outageEnded, null);
    }

    /**
     * 늦게 온 shot 다시 판정 (ADR-019). 실시간 상태(부분군·연속 횟수·기준값·최근 값)는 바꾸지 않는다.
     *
     * - 개별값(INDIVIDUAL)은 순서와 무관하므로 언제 와도 판정한다.
     * - 부분군 규칙(XBAR·S_CHART)은 보관 중인 최근 30 shot 안이면 그 값을 시간 순서 자리에 끼워 넣고,
     *   그 값을 포함하는 5 shot 구간마다 바로 앞·뒤 5 shot 구간과 함께 '연속 2회'를 다시 본다 (REJUDGED).
     *   그보다 더 늦으면 순서를 맞출 이웃 값이 없어 부분군 규칙은 보지 않는다 (OUT_OF_WINDOW).
     * - 불량률(P_CHART)은 50 shot 구간의 개수 비율이라 순서와 무관하므로 지금 구간에 넣는다(불량을 놓치지 않게).
     *   단 기준 불량률을 정하는 처음 100 shot 에는 넣지 않는다.
     * - 경보는 실시간 경보와 같은 반복 억제(규칙·센서별 100 shot)를 거친다 → 이미 낸 경보를 다시 내지 않는다.
     */
    private Result processLate(EquipmentState s, Shot shot, long tsMillis) {
        s.lateCount++;
        Map<String, Double> clean = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        boolean inWindow = false;
        for (SensorCatalog.Sensor def : sensors.values()) {
            Double v = shot.values().get(def.key());
            if (v == null || v.isNaN() || v < def.min() || v > def.max()) {
                invalid.add(def.key());
                continue;
            }
            clean.put(def.key(), v);
            EquipmentState.SensorState ss = s.sensors.get(def.key());
            if (ss == null) {
                continue;
            }
            int pos = positionByTime(ss, tsMillis);
            inWindow |= pos >= 0;
            if (!ss.ready) {
                continue;                               // 기준 구간 중: 순서가 어긋난 값으로 기준값을 만들지 않는다
            }
            double lo = ss.mean - INDIVIDUAL_K * ss.sigma;
            double hi = ss.mean + INDIVIDUAL_K * ss.sigma;
            if (v < lo || v > hi) {
                pending.add(new Pending("INDIVIDUAL", def, v, lo, hi));
            }
            if (pos >= 0) {
                List<Double> merged = new ArrayList<>(ss.recent);
                merged.add(pos, v);
                rejudgeSubgroups(ss, def, merged, pos, pending);
            }
        }
        if (s.baselineNgRate >= 0) {
            judgeInspection(s, shot, pending);          // 기준 불량률이 정해진 뒤에만 (기준 구간에는 넣지 않음)
        }
        List<Anomaly> out = emit(s, shot, pending, true);
        return new Result(false, true, clean, invalid, out, List.of(), List.of(),
                inWindow ? LATE_REJUDGED : LATE_OUT_OF_WINDOW);
    }

    /**
     * 늦은 값이 들어갈 시간 순서 자리 (최근 값 목록의 몇 번째). 순서를 맞출 수 없으면 -1:
     * 최근 값의 시각을 모르는 것이 섞여 있거나(이전 버전 상태), 보관한 30 shot 보다도 더 앞선 값일 때.
     */
    static int positionByTime(EquipmentState.SensorState ss, long tsMillis) {
        List<Long> ts = ss.recentTs;
        if (ts.isEmpty() || ts.size() != ss.recent.size()) {
            return -1;
        }
        int pos = 0;
        for (Long t : ts) {
            if (t == null) {
                return -1;
            }
            if (t <= tsMillis) {
                pos++;
            }
        }
        if (pos == 0 && ts.size() >= RECENT_KEEP) {
            return -1;
        }
        return pos;
    }

    /** 늦은 값(merged 의 pos 번째)을 포함하는 5 shot 구간과 그 바로 앞·뒤 구간으로 '연속 2회'를 다시 본다. */
    private static void rejudgeSubgroups(EquipmentState.SensorState ss, SensorCatalog.Sensor def, List<Double> merged,
            int pos, List<Pending> pending) {
        double xLo = ss.mean - XBAR_K * ss.sigma / Math.sqrt(SUBGROUP_SIZE);
        double xHi = ss.mean + XBAR_K * ss.sigma / Math.sqrt(SUBGROUP_SIZE);
        double sUcl = S_CHART_B6 * ss.sigma;
        int n = merged.size();
        boolean xbar = false;
        boolean sChart = false;
        for (int i = Math.max(0, pos - SUBGROUP_SIZE + 1); i <= Math.min(pos, n - SUBGROUP_SIZE); i++) {
            List<Double> w = merged.subList(i, i + SUBGROUP_SIZE);
            double m = mean(w);
            double sd = stdev(w, m);
            for (int j : new int[] { i - SUBGROUP_SIZE, i + SUBGROUP_SIZE }) {
                if (j < 0 || j + SUBGROUP_SIZE > n) {
                    continue;
                }
                List<Double> nb = merged.subList(j, j + SUBGROUP_SIZE);
                double nm = mean(nb);
                if (!xbar && ((m > xHi && nm > xHi) || (m < xLo && nm < xLo))) {
                    pending.add(new Pending("XBAR", def, m, xLo, xHi));
                    xbar = true;
                }
                if (!sChart && sd > sUcl && stdev(nb, nm) > sUcl) {
                    pending.add(new Pending("S_CHART", def, sd, 0, sUcl));
                    sChart = true;
                }
            }
        }
    }

    /** 최근 값과 그 시각을 같은 순서로 보관한다. 이전 버전 상태라 시각 목록이 없으면 '모름(null)'으로 맞춘다. */
    private static void addRecent(EquipmentState.SensorState ss, double v, Long tsMillis) {
        if (ss.recentTs.size() != ss.recent.size()) {
            ss.recentTs = new ArrayList<>(java.util.Collections.nCopies(ss.recent.size(), (Long) null));
        }
        ss.recent.add(v);
        ss.recentTs.add(tsMillis);
        trim(ss.recent, RECENT_KEEP);
        trim(ss.recentTs, RECENT_KEEP);
    }

    /** 검사 결과(불량률) 처리: 검사 결과가 없는 shot 은 OK 로 세지 않고 건너뛴다. */
    private static void judgeInspection(EquipmentState s, Shot shot, List<Pending> pending) {
        if (!shot.inspected()) {
            // 불량률 집계 없음
        } else if (s.baselineNgRate < 0) {
            s.inspectedCount++;
            recordDefect(s, shot);
            if (shot.ng()) {
                s.baselineNg++;
            }
            if (s.inspectedCount == BASELINE_SHOTS) {
                s.baselineNgRate = (double) s.baselineNg / BASELINE_SHOTS;
            }
        } else {
            s.inspectedCount++;
            recordDefect(s, shot);
            s.ngWindow.add(shot.ng() ? 1 : 0);
            if (s.ngWindow.size() == P_WINDOW) {
                double p = s.ngWindow.stream().mapToInt(Integer::intValue).sum() / (double) P_WINDOW;
                s.ngWindow.clear();
                double pBar = Math.max(s.baselineNgRate, P_FLOOR);
                double ucl = pBar + P_K * Math.sqrt(pBar * (1 - pBar) / P_WINDOW);
                if (p > ucl) {
                    pending.add(new Pending("P_CHART", null, p, 0, ucl));
                }
            }
        }
    }

    /** 경보 생성 (반복 억제 적용). 늦게 온 shot 은 마지막 shot 번호를 바꾸지 않는다. */
    private List<Anomaly> emit(EquipmentState s, Shot shot, List<Pending> pending, boolean late) {
        List<Anomaly> out = new ArrayList<>();
        boolean estimated = shot.shotNo() == null;
        long shotNo = estimated ? (s.lastShotNo == null ? s.count : s.lastShotNo) : shot.shotNo();
        if (!estimated && !late) {
            s.lastShotNo = shot.shotNo();
        }
        for (Pending p : pending) {
            String key = p.rule + "|" + (p.def == null ? "inspection" : p.def.key());
            Long last = s.lastAlertAt.get(key);
            if (last != null && s.count - last < COOLDOWN_SHOTS) {
                continue;
            }
            s.lastAlertAt.put(key, s.count);
            out.add(toAnomaly(s, shot, p, shotNo, estimated, late));
        }
        return out;
    }

    /** ts(ISO-8601) → epoch ms. 시간대가 없으면 UTC 로 보고, 읽을 수 없으면 null (순서 판단 안 함). */
    static Long epochMillis(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(ts).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static void recordDefect(EquipmentState s, Shot shot) {
        s.recentDefects.add(shot.ng() ? nz(shot.defectType(), "UNKNOWN") : "");
        trim(s.recentDefects, P_WINDOW);
    }

    private record Pending(String rule, SensorCatalog.Sensor def, double observed, double lower, double upper) {
    }

    private Anomaly toAnomaly(EquipmentState s, Shot shot, Pending p, long shotNo, boolean shotNoEstimated,
            boolean late) {
        String sensorKey = p.def == null ? "inspection" : p.def.key();
        String sensorName = p.def == null ? "검사 불량률" : p.def.name();
        String unit = p.def == null ? "ratio" : p.def.unit();
        EquipmentState.SensorState ss = p.def == null ? null : s.sensors.get(sensorKey);

        String id = UUID.nameUUIDFromBytes((shot.messageId() + "|" + p.rule + "|" + sensorKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new Anomaly(id, shot.lineId(), shot.equipmentId(), shotNo, shot.ts(),
                p.rule, ruleName(p.rule), sensorKey, sensorName, unit,
                round(p.observed), ss == null ? round(Math.max(s.baselineNgRate, 0)) : round(ss.mean),
                ss == null ? 0 : round(ss.sigma), round(p.lower), round(p.upper),
                ss == null ? List.of() : ss.recent.stream().map(SpcEngine::round).toList(),
                zScores(s), round(recentNgRate(s)), defectCounts(s),
                (late ? "[늦게 도착한 shot 재판정] " : "") + describe(shot.equipmentId(), p, sensorName, unit),
                shotNoEstimated, late);
    }

    /** 모든 센서의 최근 5 shot 평균이 기준 평균에서 몇 σ(평균의 표준오차 기준) 떨어져 있는지. */
    private static Map<String, Double> zScores(EquipmentState s) {
        Map<String, Double> z = new LinkedHashMap<>();
        for (var e : s.sensors.entrySet()) {
            EquipmentState.SensorState ss = e.getValue();
            if (!ss.ready || ss.recent.isEmpty()) {
                continue;
            }
            int k = Math.min(SUBGROUP_SIZE, ss.recent.size());
            double m = mean(ss.recent.subList(ss.recent.size() - k, ss.recent.size()));
            z.put(e.getKey(), round((m - ss.mean) / (ss.sigma / Math.sqrt(k))));
        }
        return z;
    }

    private static double recentNgRate(EquipmentState s) {
        if (s.recentDefects.isEmpty()) {
            return 0;
        }
        return s.recentDefects.stream().filter(d -> !d.isEmpty()).count() / (double) s.recentDefects.size();
    }

    private static Map<String, Integer> defectCounts(EquipmentState s) {
        Map<String, Integer> m = new TreeMap<>();
        for (String d : s.recentDefects) {
            if (!d.isEmpty()) {
                m.merge(d, 1, Integer::sum);
            }
        }
        return m;
    }

    public static String ruleName(String rule) {
        return switch (rule) {
            case "INDIVIDUAL" -> "개별값 관리 한계(±4σ) 이탈";
            case "XBAR" -> "X̄ 관리도: 부분군 평균 연속 2회 관리 한계 이탈";
            case "S_CHART" -> "S 관리도: 부분군 산포 연속 2회 관리 상한 초과";
            case "P_CHART" -> "p 관리도: 불량률 관리 상한 초과";
            default -> rule;
        };
    }

    private static String describe(String equipmentId, Pending p, String sensorName, String unit) {
        String u = "ratio".equals(unit) ? "" : " " + unit;
        return switch (p.rule) {
            case "INDIVIDUAL" -> String.format("%s %s 측정값 %.2f%s 이(가) 관리 범위 %.2f~%.2f%s 을(를) 벗어남",
                    equipmentId, sensorName, p.observed, u, p.lower, p.upper, u);
            case "XBAR" -> String.format("%s %s 의 5 shot 평균 %.2f%s 이(가) 연속으로 관리 범위 %.2f~%.2f%s 을(를) 벗어남 (평균 이동)",
                    equipmentId, sensorName, p.observed, u, p.lower, p.upper, u);
            case "S_CHART" -> String.format("%s %s 의 변동(표준편차 %.3f%s)이 연속으로 관리 상한 %.3f%s 을(를) 넘음 (흔들림 증가)",
                    equipmentId, sensorName, p.observed, u, p.upper, u);
            case "P_CHART" -> String.format("%s 최근 %d shot 불량률 %.1f%% 가 관리 상한 %.1f%% 을(를) 넘음",
                    equipmentId, P_WINDOW, p.observed * 100, p.upper * 100);
            default -> p.rule;
        };
    }

    private static double mean(List<Double> xs) {
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.size();
    }

    private static double stdev(List<Double> xs, double m) {
        double ss = 0;
        for (double x : xs) {
            ss += (x - m) * (x - m);
        }
        return Math.sqrt(ss / (xs.size() - 1));
    }

    private static <T> void trim(List<T> list, int max) {
        while (list.size() > max) {
            list.remove(0);
        }
    }

    private static String nz(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
