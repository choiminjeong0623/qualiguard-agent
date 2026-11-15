package com.qualiguard.qms.security;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * QMS 화면·API 로그인과 권한 (ADR-011).
 *
 *   /qms/**            로그인 필요 (로그인 화면·스타일만 예외)
 *   GET  /api/qms/**   REVIEWER, VIEWER
 *   쓰기 /api/qms/**   REVIEWER (수정·승인·반려·조치 완료)
 *   /api/agent/**, /actuator/**   열어 둠 — 로컬 평가 스크립트·모니터링용. 운영에서는 내부망 제한 또는 별도 인증 필요
 *
 * CSRF: 화면은 쿠키(XSRF-TOKEN)에서 토큰을 읽어 X-XSRF-TOKEN 헤더로 보낸다. 에이전트 API 는 스크립트용이라 제외.
 * 로그인하지 않은 API 요청은 로그인 화면으로 보내지 않고 401 을 돌려준다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();   // 새 비밀번호는 {bcrypt}
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);   // 쿠키 값을 그대로 헤더로 받는다 (화면 JS 가 읽어 보냄)
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/qms/login.html", "/qms/qms.css", "/login", "/error").permitAll()
                .requestMatchers("/qms/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/qms/**").hasAnyRole("REVIEWER", "VIEWER")
                .requestMatchers("/api/qms/**").hasRole("REVIEWER")
                .anyRequest().permitAll())
            .formLogin(f -> f
                .loginPage("/qms/login.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/qms/index.html", true)
                .failureUrl("/qms/login.html?error")
                .permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/qms/login.html?logout"))
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(path.matcher("/api/agent/**")))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), path.matcher("/api/**"))
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/qms/login.html"), path.matcher("/qms/**")));
        return http.build();
    }

    /** 요청마다 CSRF 토큰을 읽어 쿠키(XSRF-TOKEN)가 항상 내려가게 한다 (화면 JS 가 첫 요청부터 쓸 수 있도록). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
