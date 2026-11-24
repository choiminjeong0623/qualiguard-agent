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
 *
 * 분석 1회에 한 번만 실제로 찾는다. E3 재평가(2026-11-24)에서 모델이 질의만 바꿔 searchSop 를 연달아
 * 3번 부르며 왕복(회당 3~4초)과 입력 토큰을 늘린 일이 있어, 두 번째부터는 DB·색인을 보지 않고
 * "앞 결과를 쓰라"는 짧은 안내만 돌려준다 (도구 호출 상한도 세지 않음). LLM 재시도 때는 reset() 으로 처음부터.
 */
public class SopTools {

    static final int TOP_K = 3;
    /** 분석 1회 안의 첫 검색 결과 문서 번호 (null 이면 아직 검색 안 함). */
    private List<String> firstDocIds;

    private final SopLibrary library;
    private final List<String> trace;
    private final long startNanos;
    private ToolCallLimit limit = ToolCallLimit.NONE;

    public SopTools(SopLibrary library, List<String> trace, long startNanos) {
        this.library = library;
        this.trace = trace;
        this.startNanos = startNanos;
    }

    /** QualityTools 와 같은 상한을 나눠 쓴다. */
    public SopTools withLimit(ToolCallLimit limit) {
        this.limit = limit == null ? ToolCallLimit.NONE : limit;
        return this;
    }

    /** LLM 재시도는 조사를 처음부터 다시 하므로 "이미 검색함" 표시도 지운다. */
    public void reset() {
        firstDocIds = null;
    }

    @Tool(description = "현장 표준 절차(SOP)·설비 매뉴얼에서 관련 절차를 찾는다. 결과는 문서 번호(docId), 제목, 절(section), 본문이다. "
            + "추정 원인이나 현상(센서 키, 불량 유형, 증상)을 넣으면 점검 절차·판단 기준·조치·격리 방법을 찾는다. "
            + "분석당 한 번만 부를 수 있으니 원인 후보를 정한 뒤 센서 키·불량 유형·증상을 한 질의에 모두 넣는다. "
            + "recommendedAction 을 쓸 때 이 결과의 절차를 따르고 문서 번호를 함께 적는다.")
    public List<Map<String, Object>> searchSop(
            @ToolParam(description = "찾을 내용, 예: \"barrelTemp 하락 사출 압력 상승 미성형 히터\"") String query) {
        if (firstDocIds != null) {
            trace.add("searchSop(\"" + shorten(query, 80) + "\") → 분석당 1번: 앞 결과 " + firstDocIds + " 재사용 @"
                    + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
            String previous = firstDocIds.isEmpty() ? "앞 검색에서 맞는 절차가 없었습니다"
                    : "앞에서 받은 결과(" + String.join(", ", firstDocIds) + ")를 그대로 쓰세요";
            return List.of(Map.of("note", "SOP 검색은 분석당 한 번입니다. " + previous + ". 더 찾지 말고 결론을 내세요."));
        }
        if (!limit.tryAcquire()) {
            trace.add("searchSop(\"" + shorten(query, 80) + "\") @" + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
            return limit.rejected(trace, "searchSop");
        }
        List<SopLibrary.Hit> hits = library.search(query, TOP_K);
        firstDocIds = hits.stream().map(SopLibrary.Hit::docId).toList();
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
