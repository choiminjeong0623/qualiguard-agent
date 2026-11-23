package com.qualiguard.qms.ncr;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qualiguard.qms.security.AppUserDetailsService;
import com.qualiguard.qms.security.SecurityConfig;

/** QMS 로그인·권한 (ADR-011): 로그인 없이는 401, 조회 전용은 쓰기 403, 검토자는 로그인 사용자로 이력. */
@WebMvcTest(NcrController.class)
@Import(SecurityConfig.class)
class NcrSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NcrReviewService service;

    @MockitoBean
    AppUserDetailsService users;

    @Test
    void 로그인하지_않으면_API_는_401() throws Exception {
        mvc.perform(get("/api/qms/ncrs")).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인하지_않으면_화면은_로그인_페이지로() throws Exception {
        mvc.perform(get("/qms/index.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/qms/login.html"));
    }

    @Test
    void 로그인_화면의_스타일_글꼴은_로그인_전에도_받을_수_있다() throws Exception {
        mvc.perform(get("/qms/qg-tokens.css")).andExpect(status().isOk());
        mvc.perform(get("/qms/vendor/tabler/tabler.min.css")).andExpect(status().isOk());
        mvc.perform(get("/qms/vendor/pretendard/PretendardVariable.woff2")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void 조회_전용은_목록을_볼_수_있다() throws Exception {
        when(service.list(any(), any(), eq(100))).thenReturn(List.of());
        mvc.perform(get("/api/qms/ncrs")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void 조회_전용은_승인할_수_없다() throws Exception {
        mvc.perform(post("/api/qms/ncrs/AI-2026-0001/approve").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(service, never()).approve(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "REVIEWER")
    void 검토자는_요청_본문의_이름이_아니라_로그인_사용자로_기록된다() throws Exception {
        when(service.approve(any(), any(), any())).thenReturn(Map.of("status", "APPROVED"));
        mvc.perform(post("/api/qms/ncrs/AI-2026-0001/approve").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"다른 사람\"}"))
                .andExpect(status().isOk());
        verify(service).approve("AI-2026-0001", "reviewer", null);
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "REVIEWER")
    void CSRF_토큰_없는_쓰기는_거절한다() throws Exception {
        mvc.perform(post("/api/qms/ncrs/AI-2026-0001/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "REVIEWER")
    void 로그인_사용자_정보를_돌려준다() throws Exception {
        mvc.perform(get("/api/qms/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reviewer"))
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test
    void 에이전트_API_도_로그인이나_토큰이_없으면_401() throws Exception {
        // 로드맵 Q2 전에는 열려 있었다(404 = 보안 필터 통과). 이제는 검토자 로그인 또는 API 토큰이 필요하다 (AgentApiSecurityTest)
        mvc.perform(post("/api/agent/analyze-next")).andExpect(status().isUnauthorized());
    }
}
