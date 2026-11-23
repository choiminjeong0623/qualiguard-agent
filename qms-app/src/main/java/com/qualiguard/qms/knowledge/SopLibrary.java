package com.qualiguard.qms.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.qualiguard.qms.quality.SensorCatalog;

/**
 * 현장 표준 절차(SOP)·매뉴얼 문서 검색 (RAG 의 검색 부분).
 *
 * 폐쇄망에서도 돌도록 외부 서비스(임베딩 API·벡터 DB) 없이 앱 안에서 BM25 로 찾는다.
 * - 문서: classpath sop/*.md (가상 SOP 10개) + qg.knowledge.sop.dir 의 *.md (회사 문서를 넣는 자리)
 * - 조각: "## " 제목마다 한 조각. 조각마다 문서 제목·머리 정보(관련 센서·원인 코드)를 함께 색인한다
 * - 토큰: 영문·숫자·밑줄은 단어 그대로(barrelTemp, SHORT_SHOT), 한글은 두 글자씩(조사가 붙어도 맞도록)
 * - 질의의 센서 키는 한글 이름도 함께 찾는다 (barrelTemp → 실린더 온도)
 */
@Component
public final class SopLibrary {

    private static final Logger log = LoggerFactory.getLogger(SopLibrary.class);
    static final double K1 = 1.2;
    static final double B = 0.75;
    /** 조각 본문을 도구 결과로 돌려줄 때 최대 글자 수 */
    static final int MAX_TEXT = 900;

    /** 문서 조각 하나. */
    public record Chunk(String docId, String title, String section, String text) {
    }

    /** 검색 결과 하나. */
    public record Hit(String docId, String title, String section, String text, double score) {
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Map<String, Integer>> tf = new ArrayList<>();
    private final int[] lengths;
    private final Map<String, Integer> df = new HashMap<>();
    private final double avgLength;

    /** 스프링: 기본 문서 + 설정한 폴더. */
    public SopLibrary(@Value("${qg.knowledge.sop.dir:}") String dir) {
        this(load(dir));
    }

    /** 문서 이름 → 마크다운 본문. */
    public SopLibrary(Map<String, String> documents) {
        documents.forEach((name, md) -> chunks.addAll(split(name, md)));
        lengths = new int[chunks.size()];
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            List<String> tokens = tokens(c.title() + "\n" + c.section() + "\n" + c.text());
            Map<String, Integer> f = new HashMap<>();
            tokens.forEach(t -> f.merge(t, 1, Integer::sum));
            tf.add(f);
            f.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
            lengths[i] = tokens.size();
            total += tokens.size();
        }
        avgLength = chunks.isEmpty() ? 1 : (double) total / chunks.size();
        log.info("SOP 문서 조각 {}개 색인", chunks.size());
    }

    public int size() {
        return chunks.size();
    }

    public List<String> documentIds() {
        return chunks.stream().map(Chunk::docId).distinct().toList();
    }

    /** BM25 상위 k 개 (점수 0 은 빼고, 같은 문서는 가장 잘 맞는 조각 하나만). */
    public List<Hit> search(String query, int k) {
        List<String> q = tokens(expand(query == null ? "" : query));
        if (q.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        int n = chunks.size();
        double[] score = new double[n];
        for (String t : q.stream().distinct().toList()) {
            Integer d = df.get(t);
            if (d == null) {
                continue;
            }
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            for (int i = 0; i < n; i++) {
                Integer f = tf.get(i).get(t);
                if (f != null) {
                    score[i] += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * lengths[i] / avgLength));
                }
            }
        }
        Map<String, Integer> bestByDoc = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (score[i] <= 0) {
                continue;
            }
            Integer cur = bestByDoc.get(chunks.get(i).docId());
            if (cur == null || score[i] > score[cur]) {
                bestByDoc.put(chunks.get(i).docId(), i);
            }
        }
        return bestByDoc.values().stream().sorted(Comparator.comparingDouble((Integer i) -> -score[i]))
                .limit(Math.max(1, k)).map(i -> {
                    Chunk c = chunks.get(i);
                    String text = c.text().length() <= MAX_TEXT ? c.text() : c.text().substring(0, MAX_TEXT) + "…";
                    return new Hit(c.docId(), c.title(), c.section(), text, Math.round(score[i] * 100) / 100.0);
                }).toList();
    }

    /** 질의 속 센서 키에 한글 이름을 덧붙인다. */
    static String expand(String query) {
        StringBuilder sb = new StringBuilder(query);
        SensorCatalog.SENSORS.forEach((key, s) -> {
            if (query.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT))) {
                sb.append(' ').append(s.name());
            }
        });
        return sb.toString();
    }

    /** 영문·숫자·밑줄 단어는 소문자 그대로, 한글은 두 글자씩. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder hangul = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : ' ';
            boolean ascii = ch < 128 && (Character.isLetterOrDigit(ch) || ch == '_');
            boolean ko = ch >= '가' && ch <= '힣';
            if (!ascii && word.length() > 0) {
                out.add(word.toString().toLowerCase(Locale.ROOT));
                word.setLength(0);
            }
            if (!ko && hangul.length() > 0) {
                if (hangul.length() == 1) {
                    out.add(hangul.toString());
                }
                for (int j = 0; j + 1 < hangul.length(); j++) {
                    out.add(hangul.substring(j, j + 2));
                }
                hangul.setLength(0);
            }
            if (ascii) {
                word.append(ch);
            } else if (ko) {
                hangul.append(ch);
            }
        }
        return out;
    }

    /** "# 제목" 다음 머리 정보 줄들, 그리고 "## " 마다 조각. */
    static List<Chunk> split(String name, String md) {
        String[] lines = md.replace("\r", "").split("\n");
        String title = name;
        StringBuilder head = new StringBuilder();
        List<Chunk> out = new ArrayList<>();
        String section = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("# ")) {
                title = line.substring(2).strip();
            } else if (line.startsWith("## ")) {
                if (section != null) {
                    out.add(new Chunk(docId(title, name), title, section, head + body.toString().strip()));
                }
                section = line.substring(3).strip();
                body.setLength(0);
            } else if (section == null) {
                if (!line.isBlank()) {
                    head.append(line.strip()).append('\n');
                }
            } else {
                body.append(line).append('\n');
            }
        }
        if (section != null) {
            out.add(new Chunk(docId(title, name), title, section, head + body.toString().strip()));
        } else if (head.length() > 0) {
            out.add(new Chunk(docId(title, name), title, "", head.toString().strip()));
        }
        return out;
    }

    /** 제목 첫 단어가 문서 번호 모양(SOP-INJ-010)이면 그것, 아니면 파일 이름. */
    private static String docId(String title, String name) {
        String first = title.split("\\s+")[0];
        return first.matches("[A-Za-z]+(-[A-Za-z0-9]+)+") ? first : name.replaceFirst("\\.md$", "");
    }

    private static Map<String, String> load(String dir) {
        Map<String, String> docs = new LinkedHashMap<>();
        try {
            Resource[] rs = new PathMatchingResourcePatternResolver().getResources("classpath*:sop/*.md");
            Arrays.sort(rs, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : rs) {
                try (InputStream in = r.getInputStream()) {
                    docs.put(String.valueOf(r.getFilename()), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (dir != null && !dir.isBlank()) {
                try (Stream<Path> s = Files.list(Path.of(dir))) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().toList()) {
                        docs.put(String.valueOf(p.getFileName()), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("SOP 문서를 읽지 못했습니다: " + e.getMessage(), e);
        }
        return docs;
    }
}
