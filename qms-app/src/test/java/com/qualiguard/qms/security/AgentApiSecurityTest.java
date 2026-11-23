package com.qualiguard.qms.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qualiguard.qms.agent.AgentController;
import com.qualiguard.qms.agent.AgentProperties;
import com.qualiguard.qms.agent.IncidentAnalyzer;
import com.qualiguard.qms.agent.TokenBudget;
import com.qualiguard.qms.agent.pipeline.AgentPipeline;

/** 에이전트 API·MCP 인증 (로드맵 Q2): 토큰도 로그인도 없으면 401, 맞는 토큰이나 검토자 로그인이면 통과. */
@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "qg.security.api-token=test-token")
class AgentApiSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IncidentAnalyzer analyzer;
    @MockitoBean
    AgentPipeline pipeline;
    @MockitoBean
    JdbcTemplate jdbc;
    @MockitoBean
    AgentProperties props;
    @MockitoBean
    TokenBudget budget;
    @MockitoBean
    AppUserDetailsService users;

    @Test
    void 인증_없이_분석_요청하면_401() throws Exception {
        mvc.perform(post("/api/agent/analyze-next")).andExpect(status().isUnauthorized());
    }

    @Test
    void 틀린_토큰이면_401() throws Exception {
        mvc.perform(post("/api/agent/analyze-next").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 맞는_토큰이면_통과() throws Exception {
        when(analyzer.nextSeed()).thenReturn(Optional.empty());
        mvc.perform(post("/api/agent/analyze-next").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "REVIEWER")
    void 검토자_로그인이면_통과() throws Exception {
        when(analyzer.nextSeed()).thenReturn(Optional.empty());
        mvc.perform(post("/api/agent/analyze-next")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void 조회_전용_로그인은_403() throws Exception {
        mvc.perform(post("/api/agent/analyze-next")).andExpect(status().isForbidden());
    }

    @Test
    void MCP_는_토큰_없으면_401() throws Exception {
        mvc.perform(post("/mcp")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuator_는_health_만_공개() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
