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
    private final OperatorSessionService operatorSessions;
    private final CurrentUserService users;

    GithubSessionExpiryFilter(OperatorSessionService operatorSessions, CurrentUserService users) {
        this.operatorSessions = operatorSessions;
        this.users = users;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(GithubSession.EXPIRES_AT) instanceof Instant expiresAt
                && !Instant.now().isBefore(expiresAt)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        } else if (session != null
                && session.getAttribute(OperatorLoginController.PENDING) instanceof OperatorLoginController.Pending pending
                && (!Instant.now().isBefore(pending.expiresAt())
                    || !operatorSessions.valid(session.getId(), pending, users.currentUser().orElse(null)))) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
