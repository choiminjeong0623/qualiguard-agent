package com.qualiguard.qms.ncr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 초안을 사람이 얼마나 고쳤는지 (로드맵 P3): 반려율, 그대로 승인한 비율, 항목별 수정률.
 *
 * 초안 원문을 따로 저장하지 않고 검토 이력(ncr_history 의 EDIT)에서 되살린다.
 *   - 항목마다 "처음 고친 이력의 before" 가 AI 가 쓴 원문이다 (수정은 DRAFT 에서만 되므로 그 전에 바뀔 일이 없다).
 *   - 한 번도 고치지 않은 항목은 원문 = 지금 값.
 *   - 원문과 최종값이 다르면 "수정됨". 고쳤다가 원래대로 되돌렸으면 수정 안 한 것으로 본다.
 * 대상은 AI 가 만든(created_by = 'AGENT') 부적합 중 결정이 난 것(APPROVED·CLOSED = 승인, REJECTED = 반려).
 */
public final class ReviewStats {

    /** 부적합 1건: 상태, 지금 값(화면 이름 → 값), EDIT 이력의 changes_json (오래된 순). */
    public record Ncr(String ncrNo, String status, Map<String, String> current, List<String> editChanges) {
    }

    /** 부적합 1건에서 AI 원문과 달라진 항목들. */
    static List<String> changedFields(Ncr n, ObjectMapper om) {
        Map<String, String> original = new LinkedHashMap<>();
        for (String json : n.editChanges()) {
            if (json == null || json.isBlank()) {
                continue;
            }
            JsonNode changes = om.readTree(json);
            for (Map.Entry<String, JsonNode> e : changes.properties()) {
                original.putIfAbsent(e.getKey(), e.getValue().path("before").asString(""));
            }
        }
        List<String> out = new ArrayList<>();
        original.forEach((field, before) -> {
            if (!Objects.equals(norm(before), norm(n.current().get(field)))) {
                out.add(field);
            }
        });
        return out;
    }

    /** 결정이 난 부적합 목록으로 지표를 낸다. 비율은 분모가 0 이면 null. */
    public static Map<String, Object> compute(List<Ncr> ncrs, int pending, ObjectMapper om) {
        int approved = 0;
        int rejected = 0;
        int approvedAsIs = 0;
        int fieldsChangedTotal = 0;
        Map<String, Integer> perField = new LinkedHashMap<>();
        NcrReviewService.EDITABLE.keySet().forEach(f -> perField.put(f, 0));
        for (Ncr n : ncrs) {
            if ("REJECTED".equals(n.status())) {
                rejected++;
                continue;
            }
            if (!"APPROVED".equals(n.status()) && !"CLOSED".equals(n.status())) {
                continue;
            }
            approved++;
            List<String> changed = changedFields(n, om);
            if (changed.isEmpty()) {
                approvedAsIs++;
            }
            fieldsChangedTotal += changed.size();
            changed.forEach(f -> perField.merge(f, 1, Integer::sum));
        }
        int decided = approved + rejected;
        Map<String, Object> rate = new LinkedHashMap<>();
        int a = approved;
        perField.forEach((f, c) -> rate.put(f, ratio(c, a)));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decided", decided);
        m.put("pending", pending);
        m.put("approved", approved);
        m.put("rejected", rejected);
        m.put("rejectionRate", ratio(rejected, decided));
        m.put("approvedAsIs", approvedAsIs);
        m.put("asIsRate", ratio(approvedAsIs, approved));        // 승인 가운데 한 글자도 안 고친 비율
        m.put("editRate", ratio(approved - approvedAsIs, approved)); // 승인 가운데 하나라도 고친 비율
        m.put("avgFieldsChanged", approved == 0 ? null : Math.round(100.0 * fieldsChangedTotal / approved) / 100.0);
        m.put("fieldChangeRate", rate);
        return m;
    }

    private static Double ratio(int a, int b) {
        return b == 0 ? null : Math.round(1000.0 * a / b) / 1000.0;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip();
    }

    private ReviewStats() {
    }
}
