package com.qualiguard.qms.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 스크립트·MCP 클라이언트용 API 토큰 인증 (로드맵 Q2).
 *
 * 요청 헤더 {@code Authorization: Bearer <토큰>} 이 설정의 토큰(qg.security.api-token, .env 의 QG_AGENT_API_TOKEN)과
 * 같으면 이 요청을 "AGENT" 역할로 로그인한 것으로 표시한다. 다르거나 없으면 아무것도 하지 않고 넘긴다.
 * 그 뒤 통과 여부는 SecurityConfig 의 경로별 규칙이 정한다(없거나 틀리면 401).
 *
 * - 토큰 설정이 비어 있으면 토큰 인증을 쓰지 않는다(어떤 값도 통과하지 못함).
 * - 비교는 MessageDigest.isEqual 로 한다: 글자가 어디서 달라지는지에 따라 걸리는 시간이 달라지지 않게(타이밍 공격 방지).
 * - 토큰 값은 로그에 남기지 않는다.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    static final String ROLE = "ROLE_AGENT";
    private static final String PREFIX = "Bearer ";

    private final byte[] expected;

    public ApiTokenFilter(String token) {
        this.expected = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(PREFIX)) {
                byte[] given = header.substring(PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(expected, given)) {
                    var auth = UsernamePasswordAuthenticationToken.authenticated("api-token", null,
                            List.of(new SimpleGrantedAuthority(ROLE)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
