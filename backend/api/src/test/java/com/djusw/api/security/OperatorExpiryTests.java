package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class OperatorExpiryTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OperatorPasswords passwords;
    @MockitoBean Clock operatorClock;
    private MockMvc mvc;
    private String username;
    private String password;
    private long userId;
    private Instant issuedAt;
    private Instant tempExpiry;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        username = "op" + UUID.randomUUID().toString().replace("-", "");
        password = UUID.randomUUID().toString();
        issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofHours(72));
        tempExpiry = issuedAt.plus(Duration.ofHours(72));
        when(operatorClock.instant()).thenReturn(tempExpiry.minus(1, ChronoUnit.MICROS));
        userId = jdbc.queryForObject("INSERT INTO public.users (login, role) VALUES (?, 'ADMIN') RETURNING id", Long.class, username);
        jdbc.update("INSERT INTO public.operator_credentials "
                        + "(user_id, username, password_hash, must_change_password, created_at, temp_password_expires_at) "
                        + "VALUES (?, ?, ?, true, ?, ?)", userId, username, passwords.hash(password),
                Timestamp.from(issuedAt), Timestamp.from(tempExpiry));
        jdbc.update("DELETE FROM public.login_attempts WHERE ip = ?", "127.0.0.1");
    }

    private org.springframework.test.web.servlet.ResultActions login() throws Exception {
        return mvc.perform(post("/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new OperatorLoginController.LoginRequest(username, password))));
    }

    @Test
    void temporaryPasswordExpiresExactly72HoursAfterIssue() throws Exception {
        login().andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PASSWORD_CHANGE"));
        when(operatorClock.instant()).thenReturn(tempExpiry);
        login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
        when(operatorClock.instant()).thenReturn(tempExpiry.plus(1, ChronoUnit.MICROS));
        login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
    }

    @Test
    void accountExpiryIsDistinctFromTemporaryPasswordExpiry() throws Exception {
        when(operatorClock.instant()).thenReturn(tempExpiry.minusSeconds(10));
        jdbc.update("UPDATE public.operator_credentials SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(tempExpiry.minusSeconds(10)), userId);
        login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_credentials"));
        jdbc.update("UPDATE public.operator_credentials SET expires_at = NULL WHERE user_id = ?", userId);
        login().andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PASSWORD_CHANGE"));
        jdbc.update("UPDATE public.operator_credentials SET expires_at = ?, temp_password_expires_at = ? WHERE user_id = ?",
                Timestamp.from(tempExpiry.plusSeconds(1)), Timestamp.from(tempExpiry.minusSeconds(10)), userId);
        login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
    }

    @Test
    void missingTemporaryExpiryFailsClosedButDoesNotAffectChangedPasswords() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET temp_password_expires_at = NULL WHERE user_id = ?", userId);
        login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
        jdbc.update("UPDATE public.operator_credentials SET must_change_password = false WHERE user_id = ?", userId);
        login().andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("OTP_SETUP"));
    }

    @Test
    void expiredTemporaryPasswordAttemptsAreCountedForIpLimit() throws Exception {
        when(operatorClock.instant()).thenReturn(tempExpiry);
        for (int i = 0; i < 20; i++)
            login().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("temp_password_expired"));
        login().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
        org.junit.jupiter.api.Assertions.assertEquals(20,
                jdbc.queryForObject("SELECT count(*) FROM public.login_attempts WHERE ip = ?", Integer.class, "127.0.0.1"));
    }

    @Test
    void lockedAccountAttemptsAreCountedForIpLimit() throws Exception {
        jdbc.update("UPDATE public.operator_credentials SET locked_until = ? WHERE user_id = ?",
                Timestamp.from(tempExpiry.plusSeconds(60)), userId);
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO public.login_attempts (ip) VALUES (?)", "127.0.0.1");
        login().andExpect(status().isLocked()).andExpect(jsonPath("$.error").value("locked"));
        login().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_attempts"));
    }
}
