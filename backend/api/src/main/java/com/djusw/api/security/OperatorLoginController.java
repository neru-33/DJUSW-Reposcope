package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorLoginController {
    static final String PENDING = OperatorLoginController.class.getName() + ".pending";
    record Pending(long userId, String stage, Instant expiresAt) implements Serializable {}
    record LoginRequest(String username, String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    record LoginResponse(String stage, String csrf_token) {
        @Override public String toString() { return "LoginResponse[stage=" + stage + ", csrf_token=redacted]"; }
    }
    record PasswordRequest(String current_password, String new_password, String new_username) {
        @Override public String toString() { return "PasswordRequest[redacted]"; }
    }
    record OtpRequest(String code) {
        @Override public String toString() { return "OtpRequest[redacted]"; }
    }

    private final OperatorLoginService login;
    private final CsrfTokenService csrf;
    private final OperatorSessionService sessions;
    private final OperatorPasswordService passwords;
    private final CurrentUserService currentUsers;
    private final OperatorOtpSetupService otpSetup;
    private final OperatorOtpConfirmationService otpConfirmation;

    OperatorLoginController(OperatorLoginService login, CsrfTokenService csrf, OperatorSessionService sessions,
                            OperatorPasswordService passwords, CurrentUserService currentUsers, OperatorOtpSetupService otpSetup,
                            OperatorOtpConfirmationService otpConfirmation) {
        this.login = login;
        this.csrf = csrf;
        this.sessions = sessions;
        this.passwords = passwords;
        this.currentUsers = currentUsers;
        this.otpSetup = otpSetup;
        this.otpConfirmation = otpConfirmation;
    }

    @PostMapping("/auth/admin/otp/setup")
    LoginResponse confirmOtpSetup(@RequestBody OtpRequest input, HttpServletRequest request, HttpServletResponse response) {
        return confirmOtp(input, request, response, "OTP_SETUP");
    }

    @PostMapping("/auth/admin/otp")
    LoginResponse confirmOtp(@RequestBody OtpRequest input, HttpServletRequest request, HttpServletResponse response) {
        return confirmOtp(input, request, response, "OTP");
    }

    private LoginResponse confirmOtp(OtpRequest input, HttpServletRequest request, HttpServletResponse response, String expectedStage) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        if (!expectedStage.equals(pending.stage()))
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "OTP를 확인할 수 없는 단계입니다.");
        if (input == null || input.code() == null || input.code().length() > 64)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "OTP 코드를 확인하세요.");
        var result = otpConfirmation.confirm(pending, session.getId(), input.code(), request.getRemoteAddr());
        switch (result.status()) {
            case UNAUTHORIZED -> throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
            case FORBIDDEN -> throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "OTP를 확인할 수 없는 계정 상태입니다.");
            case INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "OTP 코드가 올바르지 않습니다.");
            case LOCKED -> throw new ApiException(HttpStatus.LOCKED, "locked", "계정이 잠겨 있습니다.");
            case RATE_LIMITED -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "로그인 시도 횟수를 초과했습니다.");
            case OK -> { }
        }
        String oldId = session.getId();
        String newId = request.changeSessionId();
        if (!sessions.rotateActive(oldId, newId, pending.userId())) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        }
        session.setAttribute(PENDING, new Pending(pending.userId(), "ACTIVE", pending.expiresAt()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(result.user(), null, List.of()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        response.setHeader("Cache-Control", "no-store");
        return new LoginResponse("ACTIVE", csrf.tokenFor(request));
    }

    @GetMapping("/auth/admin/otp/setup")
    OperatorOtpSetupService.Setup otpSetup(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        if (!"OTP_SETUP".equals(pending.stage()))
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "OTP를 등록할 수 없는 단계입니다.");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return otpSetup.setup(pending, session.getId());
    }

    @PostMapping("/auth/admin/password")
    LoginResponse password(@RequestBody PasswordRequest input, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        if (!pending.stage().equals("PASSWORD_CHANGE") && !pending.stage().equals("ACTIVE"))
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "비밀번호를 변경할 수 없는 단계입니다.");
        if (pending.stage().equals("ACTIVE") && currentUsers.requireCurrentUser().id() != pending.userId())
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다.");
        if (input == null || input.current_password() == null || input.current_password().isEmpty()
                || input.current_password().length() > 1024 || input.new_password() == null
                || input.new_password().isBlank() || input.new_password().length() > 1024)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "비밀번호를 확인하세요.");
        String stage = passwords.change(pending, session.getId(), input.current_password(), input.new_password(), input.new_username());
        session.setAttribute(PENDING, new Pending(pending.userId(), stage, pending.expiresAt()));
        response.setHeader("Cache-Control", "no-store");
        return new LoginResponse(stage, csrf.tokenFor(request));
    }

    @GetMapping("/auth/admin/state")
    LoginResponse state(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending)
                || !Instant.now().isBefore(pending.expiresAt())
                || !java.util.Set.of("PASSWORD_CHANGE", "OTP_SETUP", "OTP").contains(pending.stage())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        }
        response.setHeader("Cache-Control", "no-store");
        return new LoginResponse(pending.stage(), csrf.tokenFor(request));
    }

    @PostMapping("/auth/admin/login")
    LoginResponse login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        if (input == null || input.username() == null || input.username().isBlank() || input.username().length() > 255
                || input.password() == null || input.password().isEmpty() || input.password().length() > 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "ID와 비밀번호를 확인하세요.");
        }
        var result = login.login(input.username(), input.password(), request.getRemoteAddr());
        switch (result.type()) {
            case INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "ID 또는 비밀번호가 올바르지 않습니다.");
            case EXPIRED -> throw new ApiException(HttpStatus.UNAUTHORIZED, "temp_password_expired", "임시 비밀번호가 만료되었습니다.");
            case LOCKED -> throw new ApiException(HttpStatus.LOCKED, "locked", "계정이 잠겨 있습니다.");
            case RATE_LIMITED -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "로그인 시도 횟수를 초과했습니다.");
            case OK -> { }
        }
        var session = request.getSession(true);
        request.changeSessionId();
        // 이전 인증 정보와 CSRF 토큰을 재사용하지 않는다.
        for (String name : Collections.list(session.getAttributeNames())) session.removeAttribute(name);
        SecurityContextHolder.clearContext();
        new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        session.setMaxInactiveInterval(30 * 60);
        Instant expiresAt = Instant.now().plusSeconds(8 * 60 * 60);
        if (!sessions.register(result, session.getId(), expiresAt, request.getRemoteAddr())) {
            session.invalidate();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "ID 또는 비밀번호가 올바르지 않습니다.");
        }
        session.setAttribute(PENDING, new Pending(result.userId(), result.stage(), expiresAt));
        response.setHeader("Cache-Control", "no-store");
        return new LoginResponse(result.stage(), csrf.tokenFor(request));
    }
}
