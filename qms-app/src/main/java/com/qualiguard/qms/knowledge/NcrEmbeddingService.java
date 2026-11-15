package com.qualiguard.qms.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 유사 사례 의미 검색 (ADR-014): 지식저장소(APPROVED·CLOSED 부적합)의 임베딩을 SQL Server VECTOR 에 쌓고,
 * 에이전트가 설명한 현재 현상을 같은 모델로 임베딩해 usp_FindSimilarNcr 에 넘긴다.
 *
 * - 임베딩이 없거나 실패하면 기존 키워드(센서·불량 유형) 검색만 한다 (분석은 계속).
 * - 1분마다 벡터가 없거나 내용이 바뀐 사례만 새로 만든다 (승인·조치 완료와 분리, 승인 트랜잭션에 외부 호출을 넣지 않음).
 */
@Service
public class NcrEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(NcrEmbeddingService.class);
    static final int DIMENSIONS = 768;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final boolean enabled;
    private final String modelName;
    /** 같은 현상 설명은 다시 임베딩하지 않는다 (재확인처럼 비슷한 질문이 반복될 때 비용 절감) */
    private final Map<String, String> queryCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 200;
        }
    };

    public NcrEmbeddingService(JdbcTemplate jdbc, ObjectProvider<EmbeddingModel> embeddingModel,
            @Value("${qg.knowledge.vector-enabled:true}") boolean enabled,
            @Value("${spring.ai.google.genai.embedding.text.options.model:gemini-embedding-001}") String modelName) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.modelName = modelName;
    }

    /** 현재 현상 설명 → '[...]' 벡터 문자열. 쓸 수 없으면 null (키워드 검색만). */
    public String queryVector(String situation) {
        if (!enabled || situation == null || situation.isBlank()) {
            return null;
        }
        String key = situation.strip();
        synchronized (queryCache) {
            String hit = queryCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        try {
            String v = toJson(model().embed(key));
            synchronized (queryCache) {
                queryCache.put(key, v);
            }
            return v;
        } catch (RuntimeException e) {
            log.warn("[지식저장소] 현상 설명 임베딩 실패 — 키워드 검색만 사용: {}", e.toString());
            return null;
        }
    }

    @Scheduled(fixedDelayString = "${qg.knowledge.refresh-ms:60000}", initialDelayString = "${qg.knowledge.initial-delay-ms:10000}")
    public void scheduledRefresh() {
        if (enabled) {
            refresh();
        }
    }

    /** 벡터가 없거나 내용이 바뀐 지식저장소 사례의 벡터를 만든다. 만든 건수를 돌려준다. */
    public int refresh() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT n.ncr_id, n.title, n.phenomenon, n.root_cause, n.evidence, e.text_hash
                    FROM dbo.ncr AS n LEFT JOIN dbo.ncr_embedding AS e ON e.ncr_id = n.ncr_id
                    WHERE n.status IN ('APPROVED', 'CLOSED')
                    """);
        } catch (RuntimeException e) {
            log.warn("[지식저장소] 벡터 표를 읽지 못함 (11_ncr_vector.sql 적용 여부 확인): {}", e.getMessage());
            return 0;
        }
        int made = 0;
        for (Map<String, Object> r : rows) {
            String text = caseText(r);
            String hash = sha256(text);
            if (hash.equals(r.get("text_hash"))) {
                continue;
            }
            try {
                String v = toJson(model().embed(text));
                jdbc.update("""
                        MERGE dbo.ncr_embedding AS t
                        USING (SELECT ? AS ncr_id) AS s ON t.ncr_id = s.ncr_id
                        WHEN MATCHED THEN UPDATE SET embedding = CAST(? AS VECTOR(768)), model = ?, text_hash = ?,
                                                    created_at = SYSDATETIMEOFFSET()
                        WHEN NOT MATCHED THEN INSERT (ncr_id, embedding, model, text_hash)
                                              VALUES (s.ncr_id, CAST(? AS VECTOR(768)), ?, ?);
                        """, r.get("ncr_id"), v, modelName, hash, v, modelName, hash);
                made++;
            } catch (RuntimeException e) {
                log.warn("[지식저장소] 사례 벡터 생성 실패 (다음 주기에 다시): {}", e.toString());
                break;
            }
        }
        if (made > 0) {
            log.info("[지식저장소] 사례 벡터 {}건 생성", made);
        }
        return made;
    }

    /** 사례를 대표하는 글: 제목·현상·확정 원인·근거. */
    static String caseText(Map<String, Object> r) {
        StringJoiner j = new StringJoiner("\n");
        for (String k : List.of("title", "phenomenon", "root_cause", "evidence")) {
            Object v = r.get(k);
            if (v != null && !v.toString().isBlank()) {
                j.add(v.toString().strip());
            }
        }
        return j.toString();
    }

    static String toJson(float[] v) {
        if (v.length != DIMENSIONS) {
            throw new IllegalStateException("임베딩 차원이 " + v.length + " 입니다 (" + DIMENSIONS + " 이어야 함)");
        }
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private EmbeddingModel model() {
        EmbeddingModel m = embeddingModel.getIfAvailable();
        if (m == null) {
            throw new IllegalStateException("임베딩 모델이 설정되지 않았습니다");
        }
        return m;
    }
}
