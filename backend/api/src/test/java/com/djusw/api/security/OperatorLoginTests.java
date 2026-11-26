package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class OperatorLoginTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorPasswords passwords;
    @Autowired OperatorLoginService operatorLoginService;
    @Autowired OperatorSessionService operatorSessions;
    @Autowired OperatorOtpCrypto otpCrypto;
    @Autowired OperatorOtpSetupService otpSetup;
    @Autowired OperatorOtpConfirmationService otpConfirmation;
    @Autowired OperatorTotp totp;
    @Autowired ObjectMapper mapper;
    @MockitoBean GithubIdentityClient github;
    private MockMvc mvc;
    private String username, password;
    private long userId;

    @DynamicPropertySource
    static void otpKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String encoded = java.util.Base64.getEncoder().encodeToString(key);
        registry.add("app.operator.otp-key", () -> encoded);
        registry.add("app.github.client-id", () -> "test-client");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        username = "op" + UUID.randomUUID().toString().replace("-", "");
        password = UUID.randomUUID().toString();
        userId = jdbc.queryForObject("INSERT INTO public.users (login, role) VALUES (?, 'SUPER_ADMIN') RETURNING id", Long.class, username);
        jdbc.update("INSERT INTO public.operator_credentials (user_id, username, password_hash, must_change_password, temp_password_expires_at) VALUES (?, ?, ?, true, now() + interval '72 hours')", userId, username, passwords.hash(password));
        jdbc.update("DELETE FROM public.login_attempts WHERE ip = ?", "127.0.0.1");
    }

    private org.springframework.test.web.servlet.ResultActions login(String name, String pass) throws Exception {
        return mvc.perform(post("/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new OperatorLoginController.LoginRequest(name, pass))));
    }

    @Test
    void passwordSuccessReturnsStageAndCsrfWithoutProjectAccess() throws Exception {
        var response = login(username, password).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("PASSWORD_CHANGE"))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty()).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        assertNotNull(session);
        assertEquals(1800, session.getMaxInactiveInterval());
        assertInstanceOf(OperatorLoginController.Pending.class, session.getAttribute(OperatorLoginController.PENDING));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.operator_sessions WHERE id_hash = ?", Integer.class,
                OperatorSessionService.hash(session.getId())));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.operator_sessions WHERE id_hash = ?", Integer.class,
                session.getId()));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void choosesOtpOrOtpSetupAfterPasswordIsChanged() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        login(username, password).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"));
        jdbc.update("UPDATE public.operator_credentials SET totp_enabled_at = now() WHERE user_id = ?", userId);
        login(username, password).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP"));
    }

    @Test
    void stateReturnsEachPendingStageAndCsrfToken() throws Exception {
        for (String stage : new String[] { "PASSWORD_CHANGE", "OTP_SETUP", "OTP" }) {
            boolean temporary = stage.equals("PASSWORD_CHANGE");
            boolean otpEnabled = stage.equals("OTP");
            jdbc.update("UPDATE public.operator_credentials SET must_change_password = ?, "
                    + "totp_enabled_at = CASE WHEN ? THEN now() ELSE NULL END WHERE user_id = ?",
                    temporary, otpEnabled, userId);
            var loggedIn = login(username, password).andExpect(status().isOk()).andReturn();
            var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
            mvc.perform(get("/auth/admin/state").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value(stage))
                    .andExpect(jsonPath("$.csrf_token").isNotEmpty())
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/projects").session(session)).andExpect(status().isUnauthorized());
            String token = csrfFor(session);
            if (!stage.equals("PASSWORD_CHANGE")) {
                mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            }
            if (!stage.equals("OTP_SETUP")) {
                mvc.perform(get("/auth/admin/otp/setup").session(session)).andExpect(status().isForbidden());
                confirm(session, token, "not-valid").andExpect(status().isForbidden());
            }
            if (!stage.equals("OTP")) {
                confirmRegistered(session, token, "not-valid").andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void stateRejectsMissingAndExpiredSessions() throws Exception {
        mvc.perform(get("/auth/admin/state")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        var loggedIn = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        session.setAttribute(OperatorLoginController.PENDING,
                new OperatorLoginController.Pending(userId, "OTP", Instant.now().minusSeconds(1)));
        mvc.perform(get("/auth/admin/state").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        assertTrue(session.isInvalid());
    }

    @Test
    void badPasswordAndUnknownUserUseSameError() throws Exception {
        login(username, UUID.randomUUID().toString()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        login(UUID.randomUUID().toString(), password).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void expiredTemporaryPasswordAndExpiredAccountAreDistinguished() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET temp_password_expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
        login(username, password).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
        jdbc.update("UPDATE public.operator_credentials SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
        login(username, password).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void tooManyFailuresBlockTheIp() throws Exception {
        for (int i = 0; i < 20; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", "127.0.0.1");
        login(username, password).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
    }

    @Test
    void twentiethFailureIsRecordedAndTwentyFirstAttemptIsBlockedOnlyForThatIp() throws Exception {
        String ip = "192.0.2.31";
        String otherIp = "192.0.2.32";
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", ip);
        String wrong = mapper.writeValueAsString(new OperatorLoginController.LoginRequest(username, UUID.randomUUID().toString()));
        String correct = mapper.writeValueAsString(new OperatorLoginController.LoginRequest(username, password));
        mvc.perform(post("/auth/admin/login").with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM public.login_attempts WHERE ip = ?", Integer.class, ip));
        mvc.perform(post("/auth/admin/login").with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
        mvc.perform(post("/auth/admin/login").with(request -> { request.setRemoteAddr(otherIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PASSWORD_CHANGE"));
        jdbc.update("UPDATE public.login_attempts SET at = now() - interval '14 minutes 50 seconds' WHERE ip = ?", ip);
        mvc.perform(post("/auth/admin/login").with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(correct)).andExpect(status().isTooManyRequests());
        jdbc.update("UPDATE public.login_attempts SET at = now() - interval '15 minutes' WHERE ip = ?", ip);
        mvc.perform(post("/auth/admin/login").with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(correct)).andExpect(status().isOk());
    }

    @Test
    void twentiethOtpFailureCountsTowardTheSameIpLimit() throws Exception {
        var session = otpSetupSession();
        String token = csrfFor(session);
        String invalid = invalidCode(secretFor(session));
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", "127.0.0.1");
        confirm(session, token, invalid).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM public.login_attempts WHERE ip = ?", Integer.class, "127.0.0.1"));
        login(username, password).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
    }

    @Test
    void concurrentAttemptsCannotPassTheIpLimit() throws Exception {
        String ip = "192.0.2.39";
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", ip);
        String wrongPassword = UUID.randomUUID().toString();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return operatorLoginService.login(username, wrongPassword, ip).type(); });
            var second = executor.submit(() -> { start.await(); return operatorLoginService.login(username, wrongPassword, ip).type(); });
            start.countDown();
            var results = java.util.List.of(first.get(), second.get());
            assertTrue(results.contains(OperatorLoginService.ResultType.INVALID));
            assertTrue(results.contains(OperatorLoginService.ResultType.RATE_LIMITED));
        }
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM public.login_attempts WHERE ip = ?", Integer.class, ip));
    }

    @Test
    void temporaryPasswordChangeRequiresCsrfAndRevokesOtherSessions() throws Exception {
        var first = login(username, password).andExpect(status().isOk()).andReturn();
        var second = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        var other = (MockHttpSession) second.getRequest().getSession(false);
        String token = mapper.readTree(first.getResponse().getContentAsString()).get("csrf_token").asString();
        String newPassword = UUID.randomUUID().toString();
        String newUsername = "new" + UUID.randomUUID().toString().replace("-", "");
        String body = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, newPassword, newUsername));
        mvc.perform(post("/auth/admin/password").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty());
        mvc.perform(get("/auth/admin/state").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("OTP_SETUP"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/admin/state").session(other)).andExpect(status().isUnauthorized());
        assertEquals(newUsername, jdbc.queryForObject("SELECT username FROM public.operator_credentials WHERE user_id = ?", String.class, userId));
        String stored = jdbc.queryForObject("SELECT password_hash FROM public.operator_credentials WHERE user_id = ?", String.class, userId);
        assertNotEquals(newPassword, stored);
        assertTrue(passwords.matches(newPassword, stored));
        login(username, password).andExpect(status().isUnauthorized());
        login(newUsername, newPassword).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"));
    }

    @Test
    void rejectsWrongPasswordDuplicateUsernameAndIncorrectStage() throws Exception {
        var first = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        String token = mapper.readTree(first.getResponse().getContentAsString()).get("csrf_token").asString();
        String newPassword = UUID.randomUUID().toString();
        String otherName = "dup" + UUID.randomUUID().toString().replace("-", "");
        long otherId = jdbc.queryForObject("INSERT INTO public.users (login, role) VALUES (?, 'ADMIN') RETURNING id", Long.class, otherName);
        jdbc.update("INSERT INTO public.operator_credentials (user_id, username, password_hash) VALUES (?, ?, ?)",
                otherId, otherName, passwords.hash(UUID.randomUUID().toString()));
        String wrong = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(UUID.randomUUID().toString(), newPassword, otherName));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        String duplicate = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, newPassword, otherName));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        assertTrue(passwords.matches(password, jdbc.queryForObject("SELECT password_hash FROM public.operator_credentials WHERE user_id = ?", String.class, userId)));
        jdbc.update("UPDATE public.operator_sessions SET stage = 'OTP' WHERE id_hash = ?", OperatorSessionService.hash(session.getId()));
        var pending = (OperatorLoginController.Pending) session.getAttribute(OperatorLoginController.PENDING);
        session.setAttribute(OperatorLoginController.PENDING, new OperatorLoginController.Pending(userId, "OTP", pending.expiresAt()));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate)).andExpect(status().isForbidden());
    }

    @Test
    void activeOperatorCanChangePasswordOnlyWithAuthenticatedMatchingIdentity() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false, totp_enabled_at = now() WHERE user_id = ?", userId);
        var result = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String token = mapper.readTree(result.getResponse().getContentAsString()).get("csrf_token").asString();
        var pending = (OperatorLoginController.Pending) session.getAttribute(OperatorLoginController.PENDING);
        jdbc.update("UPDATE public.operator_sessions SET stage = 'ACTIVE' WHERE id_hash = ?", OperatorSessionService.hash(session.getId()));
        session.setAttribute(OperatorLoginController.PENDING, new OperatorLoginController.Pending(userId, "ACTIVE", pending.expiresAt()));
        String newPassword = UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, newPassword, null));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(userId, CurrentUser.Role.SUPER_ADMIN), null, java.util.List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ACTIVE"));
        assertTrue(passwords.matches(newPassword, jdbc.queryForObject("SELECT password_hash FROM public.operator_credentials WHERE user_id = ?", String.class, userId)));
        String sessionHash = OperatorSessionService.hash(session.getId());
        mvc.perform(post("/auth/logout").session(session).header("X-CSRF-Token", token)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.operator_sessions WHERE id_hash = ?", Integer.class, sessionHash));
    }

    @Test
    void temporaryPasswordExpiringAfterLoginCannotBeChanged() throws Exception {
        var result = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String token = mapper.readTree(result.getResponse().getContentAsString()).get("csrf_token").asString();
        jdbc.update("UPDATE public.operator_credentials SET temp_password_expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
        String body = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(
                password, UUID.randomUUID().toString(), "new" + UUID.randomUUID().toString().replace("-", "")));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
    }

    @Test
    void staleLoginVerificationCannotRegisterAfterPasswordChange() throws Exception {
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM public.operator_credentials WHERE user_id = ?", String.class, userId);
        var staleResult = new OperatorLoginService.Result(OperatorLoginService.ResultType.OK, userId, "PASSWORD_CHANGE", oldHash);
        jdbc.update("UPDATE public.operator_credentials SET password_hash = ? WHERE user_id = ?", passwords.hash(UUID.randomUUID().toString()), userId);
        assertFalse(operatorSessions.register(staleResult, UUID.randomUUID().toString(), Instant.now().plusSeconds(300), "127.0.0.1"));
    }

    @Test
    void otpSetupReturnsStableUriAndStoresOnlyEncryptedSecret() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        var first = login(username, password).andExpect(status().isOk()).andReturn();
        var second = login(username, password).andExpect(status().isOk()).andReturn();
        var firstSession = (MockHttpSession) first.getRequest().getSession(false);
        var secondSession = (MockHttpSession) second.getRequest().getSession(false);
        var response = mvc.perform(get("/auth/admin/otp/setup").session(firstSession))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache")).andReturn();
        var json = mapper.readTree(response.getResponse().getContentAsString());
        String secret = json.get("secret").asString();
        String uri = json.get("otpauth_uri").asString();
        assertTrue(secret.matches("[A-Z2-7]{32}"));
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + secret));
        assertTrue(uri.contains("issuer=RepoScope"));
        String stored = jdbc.queryForObject("SELECT totp_secret FROM public.operator_credentials WHERE user_id = ?", String.class, userId);
        assertNotNull(stored);
        assertFalse(stored.contains(secret));
        assertEquals(secret, otpCrypto.decrypt(userId, stored));
        assertThrows(IllegalStateException.class, () -> otpCrypto.decrypt(userId + 1, stored));
        var again = mvc.perform(get("/auth/admin/otp/setup").session(secondSession))
                .andExpect(status().isOk()).andReturn();
        assertEquals(secret, mapper.readTree(again.getResponse().getContentAsString()).get("secret").asString());
        assertEquals(stored, jdbc.queryForObject("SELECT totp_secret FROM public.operator_credentials WHERE user_id = ?", String.class, userId));
        mvc.perform(get("/api/me").session(firstSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void otpSetupRejectsMissingWrongStageAndInvalidatedSession() throws Exception {
        mvc.perform(get("/auth/admin/otp/setup")).andExpect(status().isUnauthorized());
        var temporary = login(username, password).andExpect(status().isOk()).andReturn();
        var wrongStage = (MockHttpSession) temporary.getRequest().getSession(false);
        mvc.perform(get("/auth/admin/otp/setup").session(wrongStage)).andExpect(status().isForbidden());
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        var setup = login(username, password).andExpect(status().isOk()).andReturn();
        var revoked = (MockHttpSession) setup.getRequest().getSession(false);
        jdbc.update("DELETE FROM public.operator_sessions WHERE id_hash = ?", OperatorSessionService.hash(revoked.getId()));
        mvc.perform(get("/auth/admin/otp/setup").session(revoked)).andExpect(status().isUnauthorized());
    }

    @Test
    void otpEncryptionRequiresIndependentValidKey() {
        var missing = new OperatorOtpCrypto("");
        assertThrows(IllegalStateException.class, () -> missing.encrypt(userId, UUID.randomUUID().toString()));
    }

    @Test
    void simultaneousOtpSetupRequestsKeepOneSecret() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        var first = login(username, password).andExpect(status().isOk()).andReturn();
        var second = login(username, password).andExpect(status().isOk()).andReturn();
        var firstSession = (MockHttpSession) first.getRequest().getSession(false);
        var secondSession = (MockHttpSession) second.getRequest().getSession(false);
        var firstPending = (OperatorLoginController.Pending) firstSession.getAttribute(OperatorLoginController.PENDING);
        var secondPending = (OperatorLoginController.Pending) secondSession.getAttribute(OperatorLoginController.PENDING);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstSetup = executor.submit(() -> { start.await(); return otpSetup.setup(firstPending, firstSession.getId()); });
            var secondSetup = executor.submit(() -> { start.await(); return otpSetup.setup(secondPending, secondSession.getId()); });
            start.countDown();
            assertEquals(firstSetup.get().secret(), secondSetup.get().secret());
        }
    }

    private static String codeAt(String secret, long step) throws Exception {
        byte[] key = org.bouncycastle.util.encoders.Base32.decode(secret);
        var mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] digest = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
        int index = digest[digest.length - 1] & 15;
        int number = ((digest[index] & 127) << 24) | ((digest[index + 1] & 255) << 16)
                | ((digest[index + 2] & 255) << 8) | (digest[index + 3] & 255);
        return String.format(java.util.Locale.ROOT, "%06d", number % 1_000_000);
    }

    private MockHttpSession otpSetupSession() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        var loginResult = login(username, password).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }

    private String csrfFor(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/auth/admin/state").session(session)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("csrf_token").asString();
    }

    private String secretFor(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/auth/admin/otp/setup").session(session)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("secret").asString();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(MockHttpSession session, String token, String code) throws Exception {
        var request = post("/auth/admin/otp/setup").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new OperatorLoginController.OtpRequest(code)));
        if (token != null) request.header("X-CSRF-Token", token);
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions confirmRegistered(MockHttpSession session, String token, String code) throws Exception {
        var request = post("/auth/admin/otp").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new OperatorLoginController.OtpRequest(code)));
        if (token != null) request.header("X-CSRF-Token", token);
        return mvc.perform(request);
    }

    private String registerOtpForTest() {
        byte[] key = new byte[20];
        new java.security.SecureRandom().nextBytes(key);
        String secret = org.bouncycastle.util.encoders.Base32.toBase32String(key);
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false, totp_secret = ?, "
                        + "totp_enabled_at = now() - interval '1 day' WHERE user_id = ?",
                otpCrypto.encrypt(userId, secret), userId);
        return secret;
    }

    private static String invalidCode(String secret) throws Exception {
        long step = Instant.now().getEpochSecond() / 30;
        var nearby = new java.util.HashSet<String>();
        for (long candidate = step - 2; candidate <= step + 2; candidate++) nearby.add(codeAt(secret, candidate));
        for (int candidate = 0; candidate < 100; candidate++) {
            String code = String.format(java.util.Locale.ROOT, "%06d", candidate);
            if (!nearby.contains(code)) return code;
        }
        throw new AssertionError("잘못된 OTP 테스트 값을 만들지 못했습니다.");
    }

    @Test
    void correctOtpCompletesSetupAndAuthenticatesOnlyAfterCsrfAndVerification() throws Exception {
        var loginResult = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String initialToken = csrfFor(session);
        String newUsername = "new" + UUID.randomUUID().toString().replace("-", "");
        String newPassword = UUID.randomUUID().toString();
        String change = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, newPassword, newUsername));
        mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", initialToken)
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"));
        String token = csrfFor(session);
        String secret = secretFor(session);
        String code = codeAt(secret, Instant.now().getEpochSecond() / 30);
        confirm(session, null, code).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        confirm(session, token, invalidCode(secret)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        String oldId = session.getId();
        var confirmed = confirm(session, token, code).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ACTIVE"))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty()).andReturn();
        assertNotEquals(oldId, session.getId());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.operator_sessions WHERE id_hash = ?", Integer.class, OperatorSessionService.hash(oldId)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT stage FROM public.operator_sessions WHERE id_hash = ?", String.class, OperatorSessionService.hash(session.getId())));
        assertNotNull(jdbc.queryForObject("SELECT totp_enabled_at FROM public.operator_credentials WHERE user_id = ?", java.sql.Timestamp.class, userId));
        assertNotNull(jdbc.queryForObject("SELECT totp_last_step FROM public.operator_credentials WHERE user_id = ?", Long.class, userId));
        assertEquals(0, jdbc.queryForObject("SELECT otp_failed_attempts FROM public.operator_credentials WHERE user_id = ?", Integer.class, userId));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.auth").value("operator"))
                .andExpect(jsonPath("$.user.id").value(userId));
        String activeToken = mapper.readTree(confirmed.getResponse().getContentAsString()).get("csrf_token").asString();
        mvc.perform(post("/auth/logout").session(session).header("X-CSRF-Token", activeToken)).andExpect(status().isNoContent());
        var nextLogin = login(newUsername, newPassword).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("OTP")).andReturn();
        var nextSession = (MockHttpSession) nextLogin.getRequest().getSession(false);
        String nextCode = codeAt(secret, Instant.now().getEpochSecond() / 30 + 1);
        confirmRegistered(nextSession, csrfFor(nextSession), nextCode).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ACTIVE"));
        mvc.perform(get("/api/me").session(nextSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId));
    }

    @Test
    void otpConfirmationRejectsWrongStageAndMissingSetupSecret() throws Exception {
        var temporary = login(username, password).andExpect(status().isOk()).andReturn();
        var wrongStage = (MockHttpSession) temporary.getRequest().getSession(false);
        confirm(wrongStage, csrfFor(wrongStage), "not-valid").andExpect(status().isForbidden());
        var setup = otpSetupSession();
        confirm(setup, csrfFor(setup), "not-valid").andExpect(status().isForbidden());
    }

    @Test
    void failedOtpAttemptsLockAccountAndShareTheIpLimit() throws Exception {
        var session = otpSetupSession();
        String token = csrfFor(session);
        String invalid = invalidCode(secretFor(session));
        for (int i = 0; i < 5; i++)
            confirm(session, token, invalid).andExpect(status().isUnauthorized());
        confirm(session, token, invalid).andExpect(status().isLocked()).andExpect(jsonPath("$.error").value("locked"));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM public.login_attempts WHERE ip = ?", Integer.class, "127.0.0.1"));
        for (int i = 0; i < 14; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", "127.0.0.1");
        confirm(session, token, "not-valid").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
    }

    @Test
    void usedTotpTimeStepCannotBeAcceptedAgain() throws Exception {
        var session = otpSetupSession();
        String secret = secretFor(session);
        Instant instant = Instant.now();
        long step = instant.getEpochSecond() / 30;
        String code = codeAt(secret, step);
        assertEquals(step, totp.matchingStep(secret, code, instant, null));
        assertNull(totp.matchingStep(secret, code, instant, step + 1));
    }

    @Test
    void previouslyConsumedOtpStepCannotCompleteRegistration() throws Exception {
        var session = otpSetupSession();
        String token = csrfFor(session);
        String secret = secretFor(session);
        long step = Instant.now().getEpochSecond() / 30;
        String code = codeAt(secret, step);
        jdbc.update("UPDATE public.operator_credentials SET totp_last_step = ? WHERE user_id = ?", step + 1, userId);
        confirm(session, token, code).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        assertNull(jdbc.queryForObject("SELECT totp_enabled_at FROM public.operator_credentials WHERE user_id = ?", java.sql.Timestamp.class, userId));
    }

    @Test
    void registeredOtpRequiresCsrfAndValidCodeBeforeAuthenticating() throws Exception {
        String secret = registerOtpForTest();
        java.sql.Timestamp registeredAt = jdbc.queryForObject(
                "SELECT totp_enabled_at FROM public.operator_credentials WHERE user_id = ?", java.sql.Timestamp.class, userId);
        var loginResult = login(username, password).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP")).andReturn();
        var session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String token = csrfFor(session);
        String code = codeAt(secret, Instant.now().getEpochSecond() / 30);
        confirmRegistered(session, null, code).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        confirmRegistered(session, token, invalidCode(secret)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        String oldId = session.getId();
        var authenticated = confirmRegistered(session, token, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ACTIVE")).andExpect(jsonPath("$.csrf_token").isNotEmpty()).andReturn();
        assertNotEquals(oldId, session.getId());
        assertEquals(registeredAt, jdbc.queryForObject("SELECT totp_enabled_at FROM public.operator_credentials WHERE user_id = ?", java.sql.Timestamp.class, userId));
        assertNotNull(jdbc.queryForObject("SELECT totp_last_step FROM public.operator_credentials WHERE user_id = ?", Long.class, userId));
        assertEquals(0, jdbc.queryForObject("SELECT otp_failed_attempts FROM public.operator_credentials WHERE user_id = ?", Integer.class, userId));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.auth").value("operator"))
                .andExpect(jsonPath("$.user.id").value(userId));
        String activeToken = mapper.readTree(authenticated.getResponse().getContentAsString()).get("csrf_token").asString();
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(post("/auth/logout").session(session).header("X-CSRF-Token", activeToken)).andExpect(status().isNoContent());
    }

    @Test
    void registeredOtpRejectsReusedStepAndWrongSetupStage() throws Exception {
        String secret = registerOtpForTest();
        long step = Instant.now().getEpochSecond() / 30;
        String code = codeAt(secret, step);
        jdbc.update("UPDATE public.operator_credentials SET totp_last_step = ? WHERE user_id = ?", step + 1, userId);
        var loginResult = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String token = csrfFor(session);
        confirm(session, token, code).andExpect(status().isForbidden());
        confirmRegistered(session, token, code).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        var temporary = jdbc.queryForObject("SELECT totp_enabled_at FROM public.operator_credentials WHERE user_id = ?", java.sql.Timestamp.class, userId);
        assertNotNull(temporary);
    }

    @Test
    void registeredOtpRejectsSetupSessionAndHonorsFailureLimits() throws Exception {
        var setup = otpSetupSession();
        confirmRegistered(setup, csrfFor(setup), "not-valid").andExpect(status().isForbidden());
        String secret = registerOtpForTest();
        var loginResult = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) loginResult.getRequest().getSession(false);
        String token = csrfFor(session);
        String invalid = invalidCode(secret);
        for (int i = 0; i < 5; i++) confirmRegistered(session, token, invalid).andExpect(status().isUnauthorized());
        confirmRegistered(session, token, invalid).andExpect(status().isLocked());
        for (int i = 0; i < 14; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", "127.0.0.1");
        confirmRegistered(session, token, invalid).andExpect(status().isTooManyRequests());
    }

    @Test
    void concurrentSessionsCannotUseTheSameOtpStep() throws Exception {
        String secret;
        long step;
        do {
            secret = registerOtpForTest();
            step = Instant.now().getEpochSecond() / 30;
        } while (codeAt(secret, step).equals(codeAt(secret, step - 1))
                || codeAt(secret, step).equals(codeAt(secret, step + 1))
                || codeAt(secret, step).equals(codeAt(secret, step + 2)));
        var first = login(username, password).andExpect(status().isOk()).andReturn();
        var second = login(username, password).andExpect(status().isOk()).andReturn();
        var firstSession = (MockHttpSession) first.getRequest().getSession(false);
        var secondSession = (MockHttpSession) second.getRequest().getSession(false);
        var firstPending = (OperatorLoginController.Pending) firstSession.getAttribute(OperatorLoginController.PENDING);
        var secondPending = (OperatorLoginController.Pending) secondSession.getAttribute(OperatorLoginController.PENDING);
        String code = codeAt(secret, step);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> { start.await(); return otpConfirmation.confirm(firstPending, firstSession.getId(), code, "192.0.2.1"); });
            var secondResult = executor.submit(() -> { start.await(); return otpConfirmation.confirm(secondPending, secondSession.getId(), code, "192.0.2.2"); });
            start.countDown();
            var statuses = java.util.List.of(firstResult.get().status(), secondResult.get().status());
            assertTrue(statuses.contains(OperatorOtpConfirmationService.Status.OK));
            assertTrue(statuses.contains(OperatorOtpConfirmationService.Status.INVALID));
        }
    }

    private MockHttpSession activeOperator() throws Exception {
        String secret = registerOtpForTest();
        var result = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String code = codeAt(secret, Instant.now().getEpochSecond() / 30);
        confirmRegistered(session, csrfFor(session), code).andExpect(status().isOk());
        return session;
    }

    @Test
    void idleLimitExpiresInactiveSessionButActivityExtendsIt() throws Exception {
        var session = activeOperator();
        String hash = OperatorSessionService.hash(session.getId());
        jdbc.update("UPDATE public.operator_sessions SET last_seen_at = now() - interval '29 minutes' WHERE id_hash = ?", hash);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT last_seen_at > now() - interval '5 seconds' FROM public.operator_sessions WHERE id_hash = ?", Boolean.class, hash));
        jdbc.update("UPDATE public.operator_sessions SET last_seen_at = now() - interval '30 minutes' WHERE id_hash = ?", hash);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void absoluteLifetimeExpiresEvenIfRecentlyUsed() throws Exception {
        var session = activeOperator();
        String hash = OperatorSessionService.hash(session.getId());
        jdbc.update("UPDATE public.operator_sessions SET created_at = now() - interval '7 hours 59 minutes', "
                + "expires_at = now() + interval '1 minute', last_seen_at = now() WHERE id_hash = ?", hash);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE public.operator_sessions SET created_at = now() - interval '8 hours', "
                + "expires_at = now(), last_seen_at = now() WHERE id_hash = ?", hash);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void roleChangeInvalidatesActiveSessionInsteadOfRetainingOldPrivileges() throws Exception {
        var session = activeOperator();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("SUPER_ADMIN"));
        jdbc.update("UPDATE public.users SET role = 'ADMIN' WHERE id = ?", userId);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void pendingSessionCannotCarryACompleteAuthentication() throws Exception {
        var result = login(username, password).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(userId, CurrentUser.Role.SUPER_ADMIN), null, java.util.List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(get("/api/projects").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void switchingFromOperatorToGithubClearsOperatorSession() throws Exception {
        var session = activeOperator();
        String operatorHash = OperatorSessionService.hash(session.getId());
        long githubId = java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000L, Long.MAX_VALUE);
        org.mockito.Mockito.when(github.authenticate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new GithubIdentityClient.GithubIdentity(githubId, "test-student", java.util.Set.of()));
        var start = mvc.perform(get("/auth/github/login").session(session)).andExpect(status().is3xxRedirection()).andReturn();
        String state = org.springframework.web.util.UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        mvc.perform(get("/auth/github/callback").session(session).param("state", state).param("code", UUID.randomUUID().toString()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.auth").value("github")).andExpect(jsonPath("$.user.github_id").value(githubId));
        assertNull(session.getAttribute(OperatorLoginController.PENDING));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.operator_sessions WHERE id_hash = ?", Integer.class, operatorHash));
    }

    @Test
    void activePasswordChangeRevokesOtherActiveSession() throws Exception {
        String secret = registerOtpForTest();
        var firstResult = login(username, password).andExpect(status().isOk()).andReturn();
        var secondResult = login(username, password).andExpect(status().isOk()).andReturn();
        var first = (MockHttpSession) firstResult.getRequest().getSession(false);
        var second = (MockHttpSession) secondResult.getRequest().getSession(false);
        String firstCode = codeAt(secret, Instant.now().getEpochSecond() / 30);
        var firstActive = confirmRegistered(first, csrfFor(first), firstCode).andExpect(status().isOk()).andReturn();
        String firstToken = mapper.readTree(firstActive.getResponse().getContentAsString()).get("csrf_token").asString();
        String secondCode = codeAt(secret, Instant.now().getEpochSecond() / 30 + 1);
        confirmRegistered(second, csrfFor(second), secondCode).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(second)).andExpect(status().isOk());
        String body = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, UUID.randomUUID().toString(), null));
        mvc.perform(post("/auth/admin/password").session(first).header("X-CSRF-Token", firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ACTIVE"));
        mvc.perform(get("/api/me").session(first)).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(second)).andExpect(status().isUnauthorized());
        assertTrue(second.isInvalid());
    }

    @Test
    void operatorAuthenticationDoesNotLogCredentialsEvenAtDebugLevel() throws Exception {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var web = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        var security = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.security");
        var previousWeb = web.getLevel();
        var previousSecurity = security.getLevel();
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        root.addAppender(captured);
        web.setLevel(ch.qos.logback.classic.Level.DEBUG);
        security.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            String wrongPassword = UUID.randomUUID().toString();
            String newPassword = UUID.randomUUID().toString();
            String malformedPassword = UUID.randomUUID().toString();
            mvc.perform(post("/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"" + malformedPassword + "\""))
                    .andExpect(status().isBadRequest());
            login(username, wrongPassword).andExpect(status().isUnauthorized());
            var loggedIn = login(username, password).andExpect(status().isOk()).andReturn();
            var original = (MockHttpSession) loggedIn.getRequest().getSession(false);
            String sessionId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            var session = new MockHttpSession(context.getServletContext(), sessionId);
            for (String name : java.util.Collections.list(original.getAttributeNames())) session.setAttribute(name, original.getAttribute(name));
            jdbc.update("UPDATE public.operator_sessions SET id_hash = ? WHERE id_hash = ?",
                    OperatorSessionService.hash(sessionId), OperatorSessionService.hash(original.getId()));
            String token = csrfFor(session);
            String nextUsername = "new" + UUID.randomUUID().toString().replace("-", "");
            String change = mapper.writeValueAsString(new OperatorLoginController.PasswordRequest(password, newPassword, nextUsername));
            mvc.perform(post("/auth/admin/password").session(session).header("X-CSRF-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(change))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"));
            String secret = secretFor(session);
            String wrongOtp = invalidCode(secret);
            confirm(session, token, wrongOtp).andExpect(status().isUnauthorized());
            String code = codeAt(secret, Instant.now().getEpochSecond() / 30);
            var confirmed = confirm(session, token, code).andExpect(status().isOk()).andReturn();
            String activeToken = mapper.readTree(confirmed.getResponse().getContentAsString()).get("csrf_token").asString();
            String activeSessionId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            var activeSession = new MockHttpSession(context.getServletContext(), activeSessionId);
            for (String name : java.util.Collections.list(session.getAttributeNames())) activeSession.setAttribute(name, session.getAttribute(name));
            jdbc.update("UPDATE public.operator_sessions SET id_hash = ? WHERE id_hash = ?",
                    OperatorSessionService.hash(activeSessionId), OperatorSessionService.hash(session.getId()));
            mvc.perform(get("/api/me").session(activeSession)).andExpect(status().isOk());
            mvc.perform(post("/auth/logout").session(activeSession).header("X-CSRF-Token", activeToken)).andExpect(status().isNoContent());
            StringBuilder output = new StringBuilder();
            for (var event : captured.list) {
                output.append(event.getFormattedMessage()).append('\n');
                if (event.getArgumentArray() != null) output.append(java.util.Arrays.deepToString(event.getArgumentArray())).append('\n');
                if (event.getThrowableProxy() != null)
                    output.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
            String logs = output.toString();
            assertTrue(logs.contains("LoginRequest[redacted]"));
            assertTrue(logs.contains("PasswordRequest[redacted]"));
            assertTrue(logs.contains("OtpRequest[redacted]"));
            assertTrue(logs.contains("Setup[redacted]"));
            String[] sensitiveValues = { password, wrongPassword, newPassword, malformedPassword, secret, wrongOtp, code, token, activeToken, sessionId, activeSessionId };
            String[] sensitiveNames = { "비밀번호", "틀린 비밀번호", "새 비밀번호", "잘못된 요청의 비밀번호", "OTP 비밀키", "틀린 OTP", "OTP", "CSRF", "활성 CSRF", "세션 ID", "활성 세션 ID" };
            for (int i = 0; i < sensitiveValues.length; i++)
                assertFalse(logs.contains(sensitiveValues[i]), sensitiveNames[i] + "가 디버그 로그에 기록되었습니다.");
        } finally {
            web.setLevel(previousWeb);
            security.setLevel(previousSecurity);
            root.detachAppender(captured);
            captured.stop();
        }
    }
}
