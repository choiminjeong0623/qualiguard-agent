package com.qualiguard.qms.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.knowledge.SopLibrary;

/** SOP 검색은 분석 1회에 한 번만 실제로 찾는다 (E3 재평가에서 연달아 3번 부른 일 → 분석 시간 개선). */
class SopToolsOnceTest {

    private static final SopLibrary LIB = new SopLibrary("");

    @Test
    void 두_번째_검색은_찾지_않고_앞_결과를_쓰라고_안내한다() {
        List<String> trace = new ArrayList<>();
        ToolCallLimit limit = new ToolCallLimit(10);
        SopTools sop = new SopTools(LIB, trace, System.nanoTime()).withLimit(limit);

        List<Map<String, Object>> first = sop.searchSop("barrelTemp 하락 미성형 히터");
        List<Map<String, Object>> second = sop.searchSop("히터 밴드 저항 측정");

        assertTrue(first.get(0).containsKey("docId"));
        assertEquals(1, second.size());
        String note = String.valueOf(second.get(0).get("note"));
        assertTrue(note.contains("분석당 한 번") && note.contains(String.valueOf(first.get(0).get("docId"))), note);
        assertEquals(1, limit.used(), "두 번째 호출은 도구 호출 상한도 세지 않음");
        assertTrue(trace.get(1).contains("분석당 1번: 앞 결과"), trace.get(1));
    }

    @Test
    void reset_하면_다시_찾는다() {
        List<String> trace = new ArrayList<>();
        SopTools sop = new SopTools(LIB, trace, System.nanoTime());
        sop.searchSop("cushion 감소 체크 링");
        sop.reset();
        List<Map<String, Object>> again = sop.searchSop("cushion 감소 체크 링");
        assertTrue(again.get(0).containsKey("docId"));
    }
}
