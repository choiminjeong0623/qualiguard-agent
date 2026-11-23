package com.qualiguard.qms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** API 토큰 필터 (로드맵 Q2): 맞는 토큰일 때만 AGENT 역할로 표시하고, 나머지는 그대로 넘긴다(통과 여부는 SecurityConfig). */
class ApiTokenFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(String configured, String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agent/analyze-next");
        if (header != null) {
            req.addHeader("Authorization", header);
        }
        MockFilterChain chain = new MockFilterChain();
        new ApiTokenFilter(configured).doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("다음 필터로 항상 넘긴다").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void 맞는_토큰이면_AGENT_역할() throws Exception {
        Authentication a = run("s3cret", "Bearer s3cret");
        assertThat(a).isNotNull();
        assertThat(a.isAuthenticated()).isTrue();
        assertThat(a.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_AGENT");
    }

    @Test
    void 틀린_토큰이나_헤더_없음은_인증하지_않는다() throws Exception {
        assertThat(run("s3cret", "Bearer wrong")).isNull();
        assertThat(run("s3cret", "Bearer s3cre")).isNull();
        assertThat(run("s3cret", "Basic s3cret")).isNull();
        assertThat(run("s3cret", null)).isNull();
    }

    @Test
    void 설정이_비어_있으면_토큰_인증을_쓰지_않는다() throws Exception {
        assertThat(run("", "Bearer ")).isNull();
        assertThat(run(null, "Bearer anything")).isNull();
    }
}
