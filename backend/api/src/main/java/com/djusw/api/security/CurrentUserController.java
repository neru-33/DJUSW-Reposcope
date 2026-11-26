package com.djusw.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.RequestBody;
import com.djusw.api.error.ApiException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {
    private final CurrentUserService currentUsers;
    private final GithubUserRepository users;
    private final CsrfTokenService csrf;
    private final boolean secureCookie;
    private final OperatorSessionService operatorSessions;

    CurrentUserController(CurrentUserService currentUsers, GithubUserRepository users, CsrfTokenService csrf,
                          @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                          OperatorSessionService operatorSessions) {
        this.currentUsers = currentUsers;
        this.users = users;
        this.csrf = csrf;
        this.secureCookie = secureCookie;
        this.operatorSessions = operatorSessions;
    }

    @GetMapping("/api/me")
    public MeResponse me(HttpServletRequest request) {
        CurrentUser current = currentUsers.requireCurrentUser();
        var profile = users.profile(current.id());
        var session = request.getSession(false);
        String auth = session != null && session.getAttribute(GithubSession.EXPIRES_AT) != null ? "github" : "operator";
        return new MeResponse(new UserResponse(profile.id(), profile.githubId(), profile.login(), current.role(), profile.name()),
                auth, csrf.tokenFor(request), !profile.consented(), current.role() == CurrentUser.Role.GUEST);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        currentUsers.requireCurrentUser();
        var session = request.getSession(false);
        if (session != null) {
            if (session.getAttribute(OperatorLoginController.PENDING) instanceof OperatorLoginController.Pending)
                operatorSessions.remove(session.getId());
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("JSESSIONID", "")
                .path(path).maxAge(0).httpOnly(true).secure(secureCookie).sameSite("Lax").build().toString());
    }

    @PostMapping("/api/me/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeProfile(@RequestBody ProfileRequest input, HttpServletRequest request, HttpServletResponse response) {
        CurrentUser current = currentUsers.requireCurrentUser();
        if (current.role() != CurrentUser.Role.GUEST || request.getSession(false) == null
                || request.getSession(false).getAttribute(GithubSession.EXPIRES_AT) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "가입 정보를 저장할 수 없습니다.");
        }
        if (input == null) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "가입 정보를 확인하세요.");
        if (!Boolean.TRUE.equals(input.consent())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "consent_required", "개인정보 수집·이용에 동의해 주세요.");
        }
        if (input.name() == null || input.name().isBlank() || input.student_id() == null
                || !input.student_id().matches("[0-9]{6,12}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "이름과 학번을 확인하세요.");
        }
        String department = input.department() == null || input.department().isBlank() ? null : input.department().strip();
        users.completeProfile(current.id(), input.name().strip(), input.student_id(), department);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(current.id(), CurrentUser.Role.STUDENT), null, List.of()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }

    record UserResponse(long id, Long github_id, String login, CurrentUser.Role role, String name) {}
    record MeResponse(UserResponse user, String auth, String csrf_token, boolean consent_required, boolean profile_required) {}
    record ProfileRequest(String name, String student_id, String department, Boolean consent) {}
}
