package com.qualiguard.qms.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MCP 서버 도구 등록 (ADR-013). Spring AI MCP 서버가 ToolCallbackProvider 빈을 MCP 도구로 노출한다 (기본 /mcp, streamable HTTP).
 * 에이전트(IncidentAnalyzer)는 분석마다 QualityTools 를 직접 넘기므로 이 빈의 영향을 받지 않는다.
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider qmsMcpTools(JdbcTemplate jdbc) {
        return MethodToolCallbackProvider.builder().toolObjects(new McpQualityTools(jdbc)).build();
    }
}
