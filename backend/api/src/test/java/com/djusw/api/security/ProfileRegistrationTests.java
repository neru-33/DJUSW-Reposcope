package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
class ProfileRegistrationTests {
    private static final long FIRST = 987650001L, SECOND = 987650002L;
    private static final String BODY = "{\"name\":\"Test Student\",\"student_id\":\"12345678\",\"consent\":true}";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.users WHERE github_id IN (?, ?)", FIRST, SECOND);
        jdbc.update("INSERT INTO public.users (github_id, login, role) VALUES (?, 'test-a', 'GUEST'), (?, 'test-b', 'GUEST')", FIRST, SECOND);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void registrationRequiresLoginAndCsrf() throws Exception {
        mvc.perform(post("/api/me/profile").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        var session = session(FIRST);
        mvc.perform(post("/api/me/profile").session(session).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        assertEquals("GUEST", role(FIRST));
    }

    @Test
    void validatesRequiredFieldsAndConsent() throws Exception {
        var session = session(FIRST);
        String token = csrf(session);
        for (String body : List.of("{\"name\":\"Test\",\"student_id\":\"123456\",\"consent\":false}",
                "{\"name\":\"Test\",\"student_id\":\"123456\"}")) {
            register(session, token, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("consent_required"));
        }
        for (String body : List.of("{\"name\":\" \",\"student_id\":\"123456\",\"consent\":true}",
                "{\"student_id\":\"123456\",\"consent\":true}",
                "{\"name\":\"Test\",\"student_id\":\"12345\",\"consent\":true}",
                "{\"name\":\"Test\",\"student_id\":\"1234567890123\",\"consent\":true}",
                "{\"name\":\"Test\",\"student_id\":\"12345a\",\"consent\":true}")) {
            register(session, token, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertEquals("GUEST", role(FIRST));
    }

    @Test
    void savesProfileAndUpgradesSessionImmediately() throws Exception {
        var session = session(FIRST);
        register(session, csrf(session), BODY).andExpect(status().isNoContent());
        assertEquals("STUDENT", role(FIRST));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT consented_at IS NOT NULL AND registered_at IS NOT NULL "
                + "AND department IS NULL FROM public.users WHERE github_id = ?", Boolean.class, FIRST));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.name").value("Test Student"))
                .andExpect(jsonPath("$.consent_required").value(false))
                .andExpect(jsonPath("$.profile_required").value(false));
        register(session, csrf(session), BODY).andExpect(status().isForbidden());
    }

    @Test
    void duplicateStudentNumberDoesNotChangeOtherUser() throws Exception {
        var first = session(FIRST);
        var second = session(SECOND);
        register(first, csrf(first), BODY).andExpect(status().isNoContent());
        register(second, csrf(second), BODY).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_student_id"));
        assertEquals("GUEST", role(SECOND));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT student_id IS NULL AND consented_at IS NULL "
                + "AND registered_at IS NULL FROM public.users WHERE github_id = ?", Boolean.class, SECOND));
    }

    @Test
    void simultaneousRegistrationOfSameStudentNumberHasOneWinner() throws Exception {
        var first = session(FIRST);
        var second = session(SECOND);
        String firstToken = csrf(first), secondToken = csrf(second);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(pool.submit(() -> { ready.countDown(); start.await(); return register(first, firstToken, BODY).andReturn().getResponse().getStatus(); }),
                    pool.submit(() -> { ready.countDown(); start.await(); return register(second, secondToken, BODY).andReturn().getResponse().getStatus(); }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var codes = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertTrue(codes.contains(204));
            assertTrue(codes.contains(409));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.users WHERE student_id = '12345678'", Integer.class));
    }

    private MockHttpSession session(long githubId) {
        long id = jdbc.queryForObject("SELECT id FROM public.users WHERE github_id = ?", Long.class, githubId);
        var session = new MockHttpSession();
        GithubSession.start(session);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(id, CurrentUser.Role.GUEST), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    private String csrf(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("csrf_token").asString();
    }

    private org.springframework.test.web.servlet.ResultActions register(MockHttpSession session, String token, String body) throws Exception {
        return mvc.perform(post("/api/me/profile").session(session).header("X-CSRF-Token", token)
                .contentType("application/json").content(body));
    }

    private String role(long githubId) {
        return jdbc.queryForObject("SELECT role FROM public.users WHERE github_id = ?", String.class, githubId);
    }
}
