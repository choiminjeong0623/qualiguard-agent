package com.qualiguard.qms.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** 유사 사례 의미 검색(ADR-014): 사례 글·해시·벡터 문자열, 모델이 없으면 키워드 검색만 하도록 null. */
class NcrEmbeddingServiceTest {

    @Test
    void 사례_글은_제목_현상_확정원인_근거를_잇는다() {
        Map<String, Object> r = new HashMap<>();
        r.put("title", "제목");
        r.put("phenomenon", "현상");
        r.put("root_cause", null);
        r.put("evidence", " 근거 ");
        assertEquals("제목\n현상\n근거", NcrEmbeddingService.caseText(r));
    }

    @Test
    void 내용이_바뀌면_해시가_바뀐다() {
        assertEquals(64, NcrEmbeddingService.sha256("a").length());
        assertNotEquals(NcrEmbeddingService.sha256("a"), NcrEmbeddingService.sha256("b"));
    }

    @Test
    void 벡터는_768차원만_받는다() {
        float[] v = new float[768];
        v[0] = 0.5f;
        String json = NcrEmbeddingService.toJson(v);
        assertTrue(json.startsWith("[0.5,0.0") && json.endsWith("]"));
        assertThrows(IllegalStateException.class, () -> NcrEmbeddingService.toJson(new float[3]));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모델이_없거나_설명이_없으면_키워드_검색만() {
        ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> none = org.mockito.Mockito.mock(ObjectProvider.class);
        NcrEmbeddingService s = new NcrEmbeddingService(new JdbcTemplate(), none, true, "m");
        assertNull(s.queryVector(" "));
        assertNull(s.queryVector("현상"), "모델이 없으면 실패하지 않고 null");
        assertNull(new NcrEmbeddingService(new JdbcTemplate(), none, false, "m").queryVector("현상"), "꺼져 있으면 null");
    }
}
