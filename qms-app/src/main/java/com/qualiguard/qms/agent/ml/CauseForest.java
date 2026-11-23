package com.qualiguard.qms.agent.ml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 원인 분류 랜덤 포레스트 (scikit-learn 에서 학습해 JSON 으로 내보낸 나무들, scripts/ml_cascade_eval.py).
 *
 * 앱 안에서 파이썬 없이 확률을 계산한다. 계산은 scikit-learn predict_proba 와 같다:
 * 나무마다 잎까지 내려가 잎의 클래스 비율을 얻고, 모든 나무의 평균을 낸다.
 * scikit-learn 은 입력을 float32 로 바꿔 비교하므로 여기서도 같은 방식으로 비교한다 (경계 값에서 갈림이 같도록).
 */
public final class CauseForest {

    private record Tree(int[] left, int[] right, int[] feature, double[] threshold, double[][] value) {
    }

    /** 원인 하나의 학습 경보 특징 평균·척도와 거리 한계 (낯선 경보 검사). */
    private record NoveltyStats(double[] mean, double[] scale, double limit) {
    }

    private final List<String> features;
    private final List<String> classes;
    private final List<Tree> trees;
    private final String createdAt;
    private final Map<String, NoveltyStats> novelty;
    private final double noveltyClip;

    private CauseForest(List<String> features, List<String> classes, List<Tree> trees, String createdAt,
            Map<String, NoveltyStats> novelty, double noveltyClip) {
        this.features = features;
        this.classes = classes;
        this.trees = trees;
        this.createdAt = createdAt;
        this.novelty = novelty;
        this.noveltyClip = noveltyClip;
    }

    public static CauseForest load(InputStream in, ObjectMapper mapper) throws IOException {
        JsonNode root = mapper.readTree(in);
        if (!"random-forest".equals(root.path("kind").asString(""))) {
            throw new IOException("원인 분류 모델 파일이 아닙니다 (kind=random-forest 필요)");
        }
        List<String> features = strings(root.path("features"));
        List<String> classes = strings(root.path("classes"));
        List<Tree> trees = new ArrayList<>();
        for (JsonNode t : root.path("trees")) {
            int n = t.path("left").size();
            int[] left = new int[n], right = new int[n], feature = new int[n];
            double[] threshold = new double[n];
            double[][] value = new double[n][];
            for (int k = 0; k < n; k++) {
                left[k] = t.path("left").get(k).asInt();
                right[k] = t.path("right").get(k).asInt();
                feature[k] = t.path("feature").get(k).asInt();
                threshold[k] = t.path("threshold").get(k).asDouble();
                if (left[k] < 0) {
                    JsonNode v = t.path("value").get(k);
                    value[k] = new double[classes.size()];
                    for (int c = 0; c < classes.size(); c++) {
                        value[k][c] = v.get(c).asDouble();
                    }
                }
            }
            trees.add(new Tree(left, right, feature, threshold, value));
        }
        if (trees.isEmpty() || features.isEmpty() || classes.isEmpty()) {
            throw new IOException("원인 분류 모델에 나무·특징·클래스가 없습니다");
        }
        Map<String, NoveltyStats> novelty = new HashMap<>();
        JsonNode nov = root.path("novelty");
        nov.path("classes").properties().forEach(e -> novelty.put(e.getKey(), new NoveltyStats(
                doubles(e.getValue().path("mean")), doubles(e.getValue().path("scale")),
                e.getValue().path("limit").asDouble())));
        return new CauseForest(List.copyOf(features), List.copyOf(classes), List.copyOf(trees),
                root.path("createdAt").asString(""), Map.copyOf(novelty), nov.path("clip").asDouble(100.0));
    }

    private static double[] doubles(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = arr.get(k).asDouble();
        }
        return out;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }

    /** 클래스별 확률 ({@link #classes()} 순서). x 는 {@link CauseFeatures#NAMES} 순서의 특징. */
    public double[] proba(double[] x) {
        if (x.length != features.size()) {
            throw new IllegalArgumentException("특징 수가 다릅니다: 모델 " + features.size() + ", 입력 " + x.length);
        }
        double[] sum = new double[classes.size()];
        for (Tree t : trees) {
            int node = 0;
            while (t.left[node] >= 0) {
                double v = (float) x[t.feature[node]];   // scikit-learn 은 float32 로 비교한다
                node = v <= t.threshold[node] ? t.left[node] : t.right[node];
            }
            double[] leaf = t.value[node];
            for (int c = 0; c < sum.length; c++) {
                sum[c] += leaf[c];
            }
        }
        for (int c = 0; c < sum.length; c++) {
            sum[c] /= trees.size();
        }
        return sum;
    }

    /**
     * 낯선 정도: 이 원인으로 분류된 학습 경보들의 특징 평균에서 얼마나 먼가 (표준화 거리, sqrt(mean(min(z², clip)))).
     * 모델 파일에 낯선 경보 검사 정보가 없으면 NaN.
     */
    public double novelty(double[] x, String cls) {
        NoveltyStats st = novelty.get(cls);
        if (st == null || st.mean().length != x.length) {
            return Double.NaN;
        }
        double sum = 0;
        for (int k = 0; k < x.length; k++) {
            double z = (x[k] - st.mean()[k]) / st.scale()[k];
            sum += Math.min(z * z, noveltyClip);
        }
        return Math.sqrt(sum / x.length);
    }

    /** 이 원인의 낯선 경보 기준 거리 (학습 경보 거리의 99.5 백분위). 없으면 NaN. */
    public double noveltyLimit(String cls) {
        NoveltyStats st = novelty.get(cls);
        return st == null ? Double.NaN : st.limit();
    }

    public List<String> features() {
        return features;
    }

    public List<String> classes() {
        return classes;
    }

    public int treeCount() {
        return trees.size();
    }

    public String createdAt() {
        return createdAt;
    }
}
