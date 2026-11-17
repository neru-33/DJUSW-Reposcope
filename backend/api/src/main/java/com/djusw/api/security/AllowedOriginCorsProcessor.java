package com.djusw.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

final class AllowedOriginCorsProcessor extends DefaultCorsProcessor {
    @Override
    public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        boolean allowed = configuration != null && configuration.checkOrigin(origin) != null;
        if (allowed) {
            return super.processRequest(configuration, request, response);
        }

        // API 응답은 no-store이므로 허용하지 않은 출처에는 출처별 캐시 구분이 필요 없다.
        var filteredResponse = new HttpServletResponseWrapper(response) {
            @Override
            public void addHeader(String name, String value) {
                if (HttpHeaders.VARY.equalsIgnoreCase(name) && HttpHeaders.ORIGIN.equalsIgnoreCase(value)) {
                    return;
                }
                super.addHeader(name, value);
            }
        };
        return super.processRequest(configuration, request, filteredResponse);
    }
}
