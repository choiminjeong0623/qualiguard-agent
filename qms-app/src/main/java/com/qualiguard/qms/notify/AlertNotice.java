package com.qualiguard.qms.notify;

import java.util.Locale;

/**
 * 담당자 휴대폰으로 보낼 경보 알림 한 건.
 *
 * @param kind     DRAFT(부적합 초안 검토 대기) / HOLD(판단 보류, 사람 확인 필요)
 * @param title    알림 제목 (잠금 화면 첫 줄)
 * @param message  본문
 * @param path     누르면 열 QMS 화면 경로 (앞의 기본 주소는 설정 qg.notify.qms-base-url)
 * @param priority ntfy 우선순위 1~5 (판단 보류는 사람이 꼭 봐야 하므로 높게)
 */
public record AlertNotice(String kind, String equipmentId, String title, String message, String path, int priority) {

    public static final String DRAFT = "DRAFT";
    public static final String HOLD = "HOLD";

    /** 새 부적합 초안 (기존 초안에 연결된 경보는 이미 알렸으므로 보내지 않는다). */
    public static AlertNotice draft(String equipmentId, String ncrNo, String title, Integer confidence) {
        String conf = confidence == null ? "" : " · 확신도 " + confidence + "%";
        return new AlertNotice(DRAFT, equipmentId,
                "[QualiGuard] " + equipmentId + " 부적합 초안 검토 대기",
                shorten(title, 80) + conf + " · " + ncrNo,
                "/qms/index.html#" + ncrNo, 4);
    }

    /** 판단 보류: 자동화가 더 할 수 없으니 사람이 봐야 한다. */
    public static AlertNotice hold(String equipmentId, Integer analysisId, String reasonCode, String detail) {
        return new AlertNotice(HOLD, equipmentId,
                "[QualiGuard] " + equipmentId + " 판단 보류 · 사람 확인 필요",
                reasonLabel(reasonCode) + " · " + shorten(detail, 120),
                "/qms/actions.html#hold/" + analysisId, 5);
    }

    static String reasonLabel(String code) {
        if (code == null) {
            return "판단 보류";
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "RECHECK_LIMIT" -> "재확인 상한 도달";
            case "VERIFY_WEAK" -> "검증 근거 부족";
            default -> code;
        };
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
