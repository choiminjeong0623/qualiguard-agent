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

    /** 처리 결과: 중복 여부, 정제된 값, 측정 범위를 벗어나 제외한 센서, 발생한 이상 목록 */
    public record Result(boolean duplicate, Map<String, Double> cleanValues, List<String> invalidSensors,
            List<Anomaly> anomalies) {
    }

    public Result process(EquipmentState s, Shot shot) {
        // ── 중복 제거 (MQTT QoS 1 재전송, 브리지 재시작 후 중복 등) ──
        if (s.recentMessageIds.contains(shot.messageId())) {
            return new Result(true, Map.of(), List.of(), List.of());
        }
        s.recentMessageIds.add(shot.messageId());
        trim(s.recentMessageIds, DEDUP_KEEP);
        s.count++;

        Map<String, Double> clean = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        List<Anomaly> out = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();

        // ── 센서별 처리 ──
        for (SensorCatalog.Sensor def : SensorCatalog.SENSORS.values()) {
            Double v = shot.values().get(def.key());
            if (v == null || v.isNaN() || v < def.min() || v > def.max()) {
                invalid.add(def.key());                 // 전처리: 물리적으로 불가능한 값은 판정에서 제외
                continue;
            }
            clean.put(def.key(), v);
            EquipmentState.SensorState ss = s.sensors.computeIfAbsent(def.key(), k -> new EquipmentState.SensorState());
            ss.recent.add(v);
            trim(ss.recent, RECENT_KEEP);

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

        // ── 검사 결과(불량률) 처리 ──
        s.recentDefects.add(shot.ng() ? nz(shot.defectType(), "UNKNOWN") : "");
        trim(s.recentDefects, P_WINDOW);
        if (s.baselineNgRate < 0) {
            if (shot.ng()) {
                s.baselineNg++;
            }
            if (s.count == BASELINE_SHOTS) {
                s.baselineNgRate = (double) s.baselineNg / BASELINE_SHOTS;
            }
        } else {
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

        // ── 경보 생성 (반복 억제 적용) ──
        for (Pending p : pending) {
            String key = p.rule + "|" + (p.def == null ? "inspection" : p.def.key());
            Long last = s.lastAlertAt.get(key);
            if (last != null && s.count - last < COOLDOWN_SHOTS) {
                continue;
            }
            s.lastAlertAt.put(key, s.count);
            out.add(toAnomaly(s, shot, p));
        }
        return new Result(false, clean, invalid, out);
    }

    private record Pending(String rule, SensorCatalog.Sensor def, double observed, double lower, double upper) {
    }

    private Anomaly toAnomaly(EquipmentState s, Shot shot, Pending p) {
        String sensorKey = p.def == null ? "inspection" : p.def.key();
        String sensorName = p.def == null ? "검사 불량률" : p.def.name();
        String unit = p.def == null ? "ratio" : p.def.unit();
        EquipmentState.SensorState ss = p.def == null ? null : s.sensors.get(sensorKey);

        String id = UUID.nameUUIDFromBytes((shot.messageId() + "|" + p.rule + "|" + sensorKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new Anomaly(id, shot.lineId(), shot.equipmentId(), shot.shotNo(), shot.ts(),
                p.rule, ruleName(p.rule), sensorKey, sensorName, unit,
                round(p.observed), ss == null ? round(Math.max(s.baselineNgRate, 0)) : round(ss.mean),
                ss == null ? 0 : round(ss.sigma), round(p.lower), round(p.upper),
                ss == null ? List.of() : ss.recent.stream().map(SpcEngine::round).toList(),
                zScores(s), round(recentNgRate(s)), defectCounts(s),
                describe(shot.equipmentId(), p, sensorName, unit));
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
