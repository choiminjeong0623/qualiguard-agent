package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** 영향 로트 목록 다루기 (로드맵 P2). */
class AffectedLotsTest {

    @Test
    void 합칠_때_순서를_지키고_중복을_뺀다() {
        assertThat(IncidentAnalyzer.mergeLots("L-01,L-02", "L-02,L-03")).isEqualTo("L-01,L-02,L-03");
        assertThat(IncidentAnalyzer.mergeLots(null, "L-01")).isEqualTo("L-01");
        assertThat(IncidentAnalyzer.mergeLots(" ", null)).isNull();
    }

    @Test
    void 칸_크기_400자를_넘으면_뒤를_줄인다() {
        List<String> many = IntStream.range(0, 60).mapToObj(i -> String.format("L261123-IMM-01-%02d", i)).toList();
        String joined = IncidentAnalyzer.joinLots(many);
        assertThat(joined.length()).isLessThanOrEqualTo(400);
        assertThat(joined).startsWith("L261123-IMM-01-00,").containsPattern(",외 \\d+개$");
        assertThat(IncidentAnalyzer.joinLots(List.of("A", "B"))).isEqualTo("A,B");
    }
}
