package com.djusw.api.security;

import com.djusw.api.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, CurrentUserService users,
                                             @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-Token");
        var corsFilter = new CorsFilter(corsSource);
        http.addFilterAt(corsFilter, CorsFilter.class)
            .addFilterBefore(new GithubSessionExpiryFilter(), CsrfFilter.class)
            .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/version",
                        "/auth/github/login", "/auth/github/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/admin/login").permitAll()
                .anyRequest().authenticated())
            .logout(logout -> logout.disable())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                    .csrfTokenRequestHandler(new HeaderCsrfTokenRequestHandler()).ignoringRequestMatchers(
                    org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
                            .withDefaults().matcher(HttpMethod.POST, "/auth/admin/login")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                        writeError(response, mapper, 401, "unauthorized", "로그인이 필요합니다."))
                .accessDeniedHandler((request, response, exception) -> {
                    if (exception instanceof CsrfException) {
                        if (!users.isLoggedIn()) {
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

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        URI origin;
        try {
            origin = URI.create(allowedOrigin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("CORS_ALLOWED_ORIGIN must be a single HTTP(S) origin", exception);
        }
        if ((!"http".equals(origin.getScheme()) && !"https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getUserInfo() != null
                || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null || origin.getRawFragment() != null
                || origin.getPort() > 65535 || origin.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("CORS_ALLOWED_ORIGIN must be a single HTTP(S) origin");
        }

        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(new ApiErrorResponse(code, message)));
    }
}
