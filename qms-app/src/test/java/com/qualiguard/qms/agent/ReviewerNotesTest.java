package com.qualiguard.qms.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 판단 보류 처리 메모를 다음 분석 입력(reviewerNotes)으로 바꾼다. */
class ReviewerNotesTest {

    @Test
    void 메모와_에이전트_판단을_담고_처리자_이름은_넣지_않는다() {
        Map<String, Object> row = Map.of("as_of_shot", 445, "decision", "OBSERVE", "cause_code", "UNKNOWN",
                "hold_reason", "RECHECK_LIMIT", "hold_note", "히터 3번 존 전류 낮음, 교체 예약",
                "resolved_at", "2026-11-23T03:00:00.000+09:00", "hold_resolved_by", "김검토");
        var notes = IncidentAnalyzer.toReviewerNotes(List.of(row));
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).containsEntry("reviewerNote", "히터 3번 존 전류 낮음, 교체 예약")
                .containsEntry("agentDecision", "OBSERVE")
                .containsEntry("holdReason", "RECHECK_LIMIT")
                .containsEntry("shotNo", 445)
                .doesNotContainValue("김검토");
    }

    @Test
    void 메모가_없으면_빈_목록() {
        assertThat(IncidentAnalyzer.toReviewerNotes(List.of())).isEmpty();
    }
}
