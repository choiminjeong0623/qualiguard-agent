package com.qualiguard.qms.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.qualiguard.qms.knowledge.SopLibrary;

/**
 * 에이전트 도구: 현장 표준 절차(SOP)·매뉴얼 검색. 분석 1건마다 만들고 호출을 trace 에 남긴다.
 * 결과는 공정 데이터가 아니므로 검증 에이전트의 수치 대조(ToolObservations)에는 넣지 않는다.
 */
public class SopTools {

    static final int TOP_K = 3;

    private final SopLibrary library;
    private final List<String> trace;
    private final long startNanos;

    public SopTools(SopLibrary library, List<String> trace, long startNanos) {
        this.library = library;
        this.trace = trace;
        this.startNanos = startNanos;
    }

    @Tool(description = "현장 표준 절차(SOP)·설비 매뉴얼에서 관련 절차를 찾는다. 결과는 문서 번호(docId), 제목, 절(section), 본문이다. "
            + "추정 원인이나 현상(센서 키, 불량 유형, 증상)을 넣으면 점검 절차·판단 기준·조치·격리 방법을 찾는다. "
            + "recommendedAction 을 쓸 때 이 결과의 절차를 따르고 문서 번호를 함께 적는다.")
    public List<Map<String, Object>> searchSop(
            @ToolParam(description = "찾을 내용, 예: \"barrelTemp 하락 사출 압력 상승 미성형 히터\"") String query) {
        List<SopLibrary.Hit> hits = library.search(query, TOP_K);
        trace.add("searchSop(\"" + shorten(query, 80) + "\") → "
                + hits.stream().map(SopLibrary.Hit::docId).toList() + " @"
                + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
        return hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", h.docId());
            m.put("title", h.title());
            m.put("section", h.section());
            m.put("text", h.text());
            m.put("score", h.score());
            return m;
        }).toList();
    }

    private static String shorten(String s, int max) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
