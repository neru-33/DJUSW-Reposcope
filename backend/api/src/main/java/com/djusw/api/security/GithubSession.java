package com.djusw.api.security;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

final class GithubSession {
    static final String EXPIRES_AT = GithubSession.class.getName() + ".expiresAt";
    static final Duration LIFETIME = Duration.ofHours(8);

    private GithubSession() {}

    static void start(HttpSession session) {
        session.setMaxInactiveInterval((int) LIFETIME.toSeconds());
        session.setAttribute(EXPIRES_AT, Instant.now().plus(LIFETIME));
    }
}
