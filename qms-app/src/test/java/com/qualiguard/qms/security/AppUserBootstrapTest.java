package com.qualiguard.qms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** .env 를 properties 로 읽을 때 깨진 한글 표시 이름을 바로잡는지 (ISO-8859-1 로 읽힌 UTF-8). */
class AppUserBootstrapTest {

    @Test
    void ISO_8859_1_로_깨진_한글을_UTF_8_로_되돌린다() {
        String broken = new String("품질 담당자".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertEquals("품질 담당자", AppUserBootstrap.utf8FromLatin1(broken));
    }

    @Test
    void 처음부터_올바른_값은_그대로_둔다() {
        assertEquals("품질 담당자", AppUserBootstrap.utf8FromLatin1("품질 담당자"));
        assertEquals("reviewer", AppUserBootstrap.utf8FromLatin1("reviewer"));
        assertEquals("José", AppUserBootstrap.utf8FromLatin1("José"), "UTF-8 로 읽히지 않는 라틴 문자는 그대로");
    }
}
