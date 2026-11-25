package com.djusw.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class GithubSessionExpiryFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(GithubSession.EXPIRES_AT) instanceof Instant expiresAt
                && !Instant.now().isBefore(expiresAt)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
