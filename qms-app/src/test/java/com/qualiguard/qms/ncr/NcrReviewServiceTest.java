package com.qualiguard.qms.ncr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/** 부적합 검토 서비스(F-08)의 입력 검증·변경 감지·프로시저 오류 변환을 DB 없이 검증한다. */
class NcrReviewServiceTest {

    static class FakeJdbc extends JdbcTemplate {
        Map<String, Object> row = new HashMap<>();
        List<Object[]> updates = new ArrayList<>();
        SQLException fail;

        FakeJdbc() {
            row.put("ncr_id", 1);
            row.put("ncr_no", "AI-2026-0001");
            row.put("status", "DRAFT");
            row.put("title", "1호기 미성형");
            row.put("root_cause", null);
            row.put("cause_code", "HEATER_DEGRADATION");
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            return new HashMap<>(row);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (fail != null) {
                throw new UncategorizedSQLException("exec", sql, fail);
            }
            Object[] call = new Object[args.length + 1];
            call[0] = sql;
            System.arraycopy(args, 0, call, 1, args.length);
            updates.add(call);
            return 1;
        }
    }

    private static NcrReviewService service(FakeJdbc jdbc) {
        return new NcrReviewService(jdbc, JsonMapper.builder().build());
    }

    @Test
    void 검토자_이름이_없으면_거절한다() {
        FakeJdbc jdbc = new FakeJdbc();
        NcrReviewService.ReviewException e = assertThrows(NcrReviewService.ReviewException.class,
                () -> service(jdbc).approve("AI-2026-0001", " ", null));
        assertEquals(400, e.status());
        assertTrue(jdbc.updates.isEmpty());
    }

    @Test
    void 반려에는_사유가_필요하다() {
        FakeJdbc jdbc = new FakeJdbc();
        NcrReviewService.ReviewException e = assertThrows(NcrReviewService.ReviewException.class,
                () -> service(jdbc).reject("AI-2026-0001", "품질팀", "  "));
        assertEquals(400, e.status());
    }

    @Test
    void 바뀐_항목만_저장하고_이력에_전후를_남긴다() {
        FakeJdbc jdbc = new FakeJdbc();
        service(jdbc).update("AI-2026-0001", "품질팀", Map.of(
                "title", "1호기 미성형",                 // 그대로 → 저장 안 함
                "rootCause", "2번 존 히터 밴드 단선"));  // 바뀜
        assertEquals(1, jdbc.updates.size());
        Object[] call = jdbc.updates.get(0);
        assertTrue(((String) call[0]).contains("usp_UpdateNcrDraft"));
        assertNull(call[3], "제목은 바뀌지 않았으므로 NULL(그대로)");
        assertEquals("2번 존 히터 밴드 단선", call[6]);
        String changes = (String) call[12];
        assertTrue(changes.contains("rootCause") && !changes.contains("\"title\""));
    }

    @Test
    void 바뀐_것이_없으면_프로시저를_부르지_않는다() {
        FakeJdbc jdbc = new FakeJdbc();
        service(jdbc).update("AI-2026-0001", "품질팀", Map.of("title", "1호기 미성형"));
        assertTrue(jdbc.updates.isEmpty());
    }

    @Test
    void 알_수_없는_원인_코드는_거절한다() {
        FakeJdbc jdbc = new FakeJdbc();
        NcrReviewService.ReviewException e = assertThrows(NcrReviewService.ReviewException.class,
                () -> service(jdbc).update("AI-2026-0001", "품질팀", Map.of("causeCode", "HEATER")));
        assertEquals(400, e.status());
    }

    @Test
    void 프로시저의_상태_오류는_409로_바꾼다() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.fail = new SQLException("초안(DRAFT) 상태에서만 승인·반려할 수 있습니다.", "S0001", 50409);
        NcrReviewService.ReviewException e = assertThrows(NcrReviewService.ReviewException.class,
                () -> service(jdbc).approve("AI-2026-0001", "품질팀", null));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("DRAFT"));
    }
}
