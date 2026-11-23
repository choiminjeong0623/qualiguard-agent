package com.qualiguard.qms.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qualiguard.qms.agent.SopTools;

/**
 * SOP 검색이 맞는 문서를 첫째로 찾는가.
 * 질의는 문서 문장을 그대로 베끼지 않고, 에이전트가 쓸 법한 센서 키·현상 요약으로 만들었다.
 */
class SopLibraryTest {

    static final SopLibrary LIB = new SopLibrary("");

    /** {질의, 정답 문서} — 고장 4종 + 새 고장 3종 + 불량·격리 */
    static final String[][] CASES = {
            {"barrelTemp 서서히 하락, injectionPressure motorCurrent 상승, 미성형 증가", "SOP-INJ-010"},
            {"HEATER_DEGRADATION 히터 열화 의심", "SOP-INJ-010"},
            {"moldTemp 상승 추세 cycleTime 증가 휨 불량", "SOP-INJ-011"},
            {"COOLING_ISSUE 금형 냉각수 유량 확인", "SOP-INJ-011"},
            {"cushion 감소와 변동 증가, 사출 압력 산포 커짐", "SOP-INJ-012"},
            {"CHECK_RING_WEAR 역류", "SOP-INJ-012"},
            {"injectionPressure 단발 스파이크, 다른 센서와 불량률 정상", "SOP-INJ-013"},
            {"사출 압력이 내려가고 흔들림, 실린더 온도 정상, 버 증가, 원료 로트 변경", "SOP-INJ-020"},
            {"사출 압력 모터 전류 상승, 실린더 온도는 정상, 쿠션량 증가", "SOP-INJ-021"},
            {"센서 변화 거의 없이 FLASH 버 불량만 증가, holdingPressure 흔들림", "SOP-MLD-030"},
            {"의심 로트 격리 보류 NCR 발행 기한", "SOP-QC-040"},
            {"WARPAGE 휨 불량 1차 조치", "SOP-QC-041"},
    };

    @Test
    void 내장_가상_SOP_10개를_조각으로_나눠_색인한다() {
        assertThat(LIB.documentIds()).hasSize(10).contains("SOP-INJ-010", "SOP-QC-041");
        assertThat(LIB.size()).isGreaterThan(30);
    }

    @Test
    void 증상_질의로_맞는_SOP_를_첫째로_찾는다() {
        List<String> misses = new ArrayList<>();
        int top3 = 0;
        for (String[] c : CASES) {
            List<SopLibrary.Hit> hits = LIB.search(c[0], 3);
            if (hits.isEmpty() || !hits.get(0).docId().equals(c[1])) {
                misses.add(c[0] + " → " + hits.stream().map(SopLibrary.Hit::docId).toList() + " (정답 " + c[1] + ")");
            }
            top3 += hits.stream().anyMatch(h -> h.docId().equals(c[1])) ? 1 : 0;
        }
        assertThat(misses).as("첫째가 틀린 질의").isEmpty();
        assertThat(top3).isEqualTo(CASES.length);
    }

    @Test
    void 한글은_두_글자씩_자르고_센서_키는_한글_이름을_덧붙인다() {
        assertThat(SopLibrary.tokens("쿠션량이 SHORT_SHOT")).containsExactly("쿠션", "션량", "량이", "short_shot");
        assertThat(SopLibrary.expand("barrelTemp")).contains("실린더 온도");
        assertThat(LIB.search("", 3)).isEmpty();
        assertThat(LIB.search("zzzz", 3)).isEmpty();
    }

    @Test
    void 같은_문서는_한_번만_나오고_머리_정보로_문서_번호를_정한다() {
        List<SopLibrary.Hit> hits = LIB.search("체크 링 쿠션 쿠션량 역류 계량", 5);
        assertThat(hits.stream().map(SopLibrary.Hit::docId).distinct().count()).isEqualTo(hits.size());
        SopLibrary own = new SopLibrary(Map.of("a.md", "# 사내 매뉴얼\n머리\n## 1. 범위\n본문 히터"));
        assertThat(own.search("히터", 1).get(0).docId()).isEqualTo("a");
        assertThat(own.search("히터", 1).get(0).text()).contains("머리").contains("본문");
    }

    @Test
    void 도구는_결과_문서_번호를_trace_에_남긴다() {
        List<String> trace = new ArrayList<>();
        List<Map<String, Object>> rows = new SopTools(LIB, trace, System.nanoTime()).searchSop("cushion 감소 체크 링");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).containsKeys("docId", "title", "section", "text", "score");
        assertThat(trace.get(0)).startsWith("searchSop(\"cushion 감소 체크 링\") → [SOP-INJ-012");
    }
}
