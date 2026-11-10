package com.djusw.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/** Supplies the request token to the future /api/me and operator login responses. */
@Component
public class CsrfTokenService {
    public String tokenFor(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new IllegalStateException("CSRF token is unavailable for this request");
        }
        return token.getToken();
    }
}
