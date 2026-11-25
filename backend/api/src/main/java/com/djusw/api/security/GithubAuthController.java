package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class GithubAuthController {
    private static final String PENDING = GithubAuthController.class.getName() + ".pending";
    private final SecureRandom random = new SecureRandom();
    private final GithubIdentityClient github;
    private final GithubUserRepository users;
    private final String clientId, callbackUrl, frontendUrl;

    GithubAuthController(GithubIdentityClient github, GithubUserRepository users,
                         @Value("${app.github.client-id}") String clientId,
                         @Value("${app.github.callback-url}") String callbackUrl,
                         @Value("${app.frontend-url}") String frontendUrl) {
        this.github = github;
        this.users = users;
        this.clientId = clientId;
        this.callbackUrl = callbackUrl;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @GetMapping("/auth/github/login")
    public void login(@RequestParam(defaultValue = "/projects") String next,
                      HttpServletRequest request, HttpServletResponse response) throws Exception {
        validateNext(next);
        if (clientId.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "internal_error", "GitHub 로그인이 설정되지 않았습니다.");
        String state = randomString();
        String verifier = randomString();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        request.getSession(true).setAttribute(PENDING, new Pending(state, verifier, next, Instant.now().plusSeconds(600)));
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect("https://github.com/login/oauth/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(callbackUrl) + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256");
    }

    @GetMapping("/auth/github/callback")
    public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                         HttpServletRequest request, HttpServletResponse response) throws Exception {
        var session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(PENDING);
        if (session != null) session.removeAttribute(PENDING);
        if (!(value instanceof Pending pending) || state == null || code == null || code.isBlank()
                || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                || Instant.now().isAfter(pending.expiresAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "GitHub 인증 요청을 확인하세요.");
        }
        CurrentUser user = users.saveLogin(github.authenticate(code, pending.verifier()));
        request.changeSessionId();
        GithubSession.start(request.getSession(false));
        var csrf = new HttpSessionCsrfTokenRepository();
        csrf.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(frontendUrl + (user.role() == CurrentUser.Role.GUEST ? "/signup" : pending.next()));
    }

    static void validateNext(String next) {
        try {
            String lower = next.toLowerCase(Locale.ROOT);
            URI uri = URI.create(next);
            if (!next.startsWith("/") || next.startsWith("//") || next.indexOf('\\') >= 0
                    || next.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)
                    || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25")
                    || lower.contains("%0a") || lower.contains("%0d")
                    || uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "이동할 경로를 확인하세요.");
        }
    }

    private String randomString() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private record Pending(String state, String verifier, String next, Instant expiresAt) implements java.io.Serializable {}
}
