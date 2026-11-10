package com.djusw.api.security;

import com.djusw.api.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-Token");
        http.authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/version",
                        "/auth/github/login", "/auth/github/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/admin/login").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                    .csrfTokenRequestHandler(new HeaderCsrfTokenRequestHandler()).ignoringRequestMatchers(
                    org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
                            .withDefaults().matcher(HttpMethod.POST, "/auth/admin/login")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                        writeError(response, mapper, 401, "unauthorized", "로그인이 필요합니다."))
                .accessDeniedHandler((request, response, exception) -> {
                    if (exception instanceof CsrfException) {
                        var authentication = SecurityContextHolder.getContext().getAuthentication();
                        var trustResolver = new AuthenticationTrustResolverImpl();
                        if (authentication == null || !authentication.isAuthenticated()
                                || trustResolver.isAnonymous(authentication)) {
                            writeError(response, mapper, 401, "unauthorized", "로그인이 필요합니다.");
                        } else {
                            writeError(response, mapper, 403, "csrf", "CSRF 토큰을 확인하세요.");
                        }
                    } else {
                        writeError(response, mapper, 403, "forbidden", "접근 권한이 없습니다.");
                    }
                }));
        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(new ApiErrorResponse(code, message)));
    }
}
