package com.djusw.api.security;

import com.djusw.api.support.DatabaseTest;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.djusw.api.error.ApiException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DatabaseTest
@TestPropertySource(properties = {"app.github.client-id=test-client", "app.github.client-secret=test-secret"})
class GithubAuthenticationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GithubIdentityClient github;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.users WHERE github_id = ?", 987654321L);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void newUserIsGuestAndExistingUserIsIdentifiedByGithubId() throws Exception {
        when(github.authenticate(eq("test-code"), anyString()))
                .thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "first-name", java.util.Set.of()))
                .thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "renamed", java.util.Set.of()));
        var first = login("/projects/42");
        var session = (MockHttpSession) first.getRequest().getSession(false);
        String state = state(first.getResponse().getRedirectedUrl());
        mvc.perform(get("/auth/github/callback").session(session).param("state", state).param("code", "test-code"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/signup"));
        var securityContext = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals(CurrentUser.Role.GUEST, ((CurrentUser) securityContext.getAuthentication().getPrincipal()).role());
        assertNull(securityContext.getAuthentication().getCredentials());
        assertEquals("GUEST", jdbc.queryForObject("SELECT role FROM public.users WHERE github_id = ?", String.class, 987654321L));
        long id = jdbc.queryForObject("SELECT id FROM public.users WHERE github_id = ?", Long.class, 987654321L);
        jdbc.update("UPDATE public.users SET role = 'STUDENT' WHERE id = ?", id);
        var second = login("/projects/42");
        mvc.perform(get("/auth/github/callback").session((MockHttpSession) second.getRequest().getSession(false))
                        .param("state", state(second.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("http://localhost:5173/projects/42"));
        assertEquals(id, jdbc.queryForObject("SELECT id FROM public.users WHERE github_id = ?", Long.class, 987654321L));
        assertEquals("renamed", jdbc.queryForObject("SELECT login FROM public.users WHERE id = ?", String.class, id));
    }

    @Test
    void invalidOrReusedStateCannotAuthenticate() throws Exception {
        var result = login("/projects");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String state = state(result.getResponse().getRedirectedUrl());
        mvc.perform(get("/auth/github/callback").session(session).param("state", "wrong").param("code", "test-code"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/auth/github/callback").session(session).param("state", state).param("code", "test-code"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(github);
    }

    @Test
    void disabledUserIsRejectedWithoutChangingLogin() throws Exception {
        jdbc.update("INSERT INTO public.users (github_id, login, disabled_at) VALUES (?, ?, now())", 987654321L, "old-name");
        when(github.authenticate(anyString(), anyString())).thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "new-name", java.util.Set.of()));
        var result = login("/projects");
        mvc.perform(get("/auth/github/callback").session((MockHttpSession) result.getRequest().getSession(false))
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().isForbidden());
        assertEquals("old-name", jdbc.queryForObject("SELECT login FROM public.users WHERE github_id = ?", String.class, 987654321L));
    }

    @Test
    void rejectsExternalAndEncodedRedirects() throws Exception {
        for (String next : new String[]{"https://evil.example", "//evil.example", "/\\evil.example", "/%2f%2fevil.example", "/%252fevil.example"}) {
            mvc.perform(get("/auth/github/login").param("next", next)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void loginMeCsrfAndLogoutUseSameSession() throws Exception {
        when(github.authenticate(anyString(), anyString())).thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "test-user", java.util.Set.of()));
        var result = login("/projects");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String oldId = session.getId();
        Instant before = Instant.now();
        mvc.perform(get("/auth/github/callback").session(session)
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().is3xxRedirection());
        assertNotEquals(oldId, session.getId());
        assertEquals(8 * 60 * 60, session.getMaxInactiveInterval());
        Instant expiry = (Instant) session.getAttribute(GithubSession.EXPIRES_AT);
        assertFalse(expiry.isBefore(before.plusSeconds(8 * 60 * 60)));
        assertFalse(expiry.isAfter(Instant.now().plusSeconds(8 * 60 * 60)));

        var me = mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.github_id").value(987654321))
                .andExpect(jsonPath("$.user.role").value("GUEST"))
                .andExpect(jsonPath("$.auth").value("github"))
                .andExpect(jsonPath("$.consent_required").value(true))
                .andExpect(jsonPath("$.profile_required").value(true))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty()).andReturn();
        String body = me.getResponse().getContentAsString();
        String token = new tools.jackson.databind.ObjectMapper().readTree(body).get("csrf_token").asString();
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(post("/auth/logout").session(session).header("X-CSRF-Token", token))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionCannotAccessMeEvenIfRecentlyUsed() throws Exception {
        when(github.authenticate(anyString(), anyString())).thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "test-user", java.util.Set.of()));
        var result = login("/projects");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/auth/github/callback").session(session)
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().is3xxRedirection());
        session.setAttribute(GithubSession.EXPIRES_AT, Instant.now().minusSeconds(1));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void meReflectsCompletedProfileAndConsent() throws Exception {
        jdbc.update("INSERT INTO public.users (github_id, login, role, name, consented_at, registered_at) "
                + "VALUES (?, ?, 'STUDENT', ?, now(), now())", 987654321L, "test-user", "test-name");
        when(github.authenticate(anyString(), anyString())).thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "test-user", java.util.Set.of()));
        var result = login("/projects");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/auth/github/callback").session(session)
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.name").value("test-name"))
                .andExpect(jsonPath("$.consent_required").value(false))
                .andExpect(jsonPath("$.profile_required").value(false));
    }

    @Test
    void loginReplacesRepositoryAccessAndRefreshesCheckedAt() throws Exception {
        jdbc.update("INSERT INTO public.users (github_id, login, role) VALUES (?, 'old', 'STUDENT')", 987654321L);
        long id = jdbc.queryForObject("SELECT id FROM public.users WHERE github_id = ?", Long.class, 987654321L);
        jdbc.update("INSERT INTO public.user_repos (user_id, repo_id, checked_at) VALUES (?, 10, '2000-01-01'), (?, 20, '2000-01-01')", id, id);
        when(github.authenticate(anyString(), anyString()))
                .thenReturn(new GithubIdentityClient.GithubIdentity(987654321L, "new", Set.of(20L, 30L)));
        var result = login("/projects");
        mvc.perform(get("/auth/github/callback").session((MockHttpSession) result.getRequest().getSession(false))
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().is3xxRedirection());
        assertEquals(Set.of(20L, 30L), Set.copyOf(jdbc.queryForList(
                "SELECT repo_id FROM public.user_repos WHERE user_id = ?", Long.class, id)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM public.user_repos WHERE user_id = ? AND checked_at > '2000-01-01'", Integer.class, id));
    }

    @Test
    void failedGithubLookupPreservesRepositoryAccess() throws Exception {
        jdbc.update("INSERT INTO public.users (github_id, login) VALUES (?, 'old')", 987654321L);
        long id = jdbc.queryForObject("SELECT id FROM public.users WHERE github_id = ?", Long.class, 987654321L);
        jdbc.update("INSERT INTO public.user_repos (user_id, repo_id) VALUES (?, 10)", id);
        when(github.authenticate(anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "internal_error", "GitHub 조회 실패"));
        var result = login("/projects");
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/auth/github/callback").session(session)
                        .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                .andExpect(status().isBadGateway());
        assertEquals(List.of(10L), jdbc.queryForList("SELECT repo_id FROM public.user_repos WHERE user_id = ?", Long.class, id));
        assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void githubUserTokenDoesNotPersistInDatabaseSessionOrApplicationLogs() throws Exception {
        String token = "ghu_fake_token_for_test_only";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = switch (exchange.getRequestURI().getPath()) {
                case "/token" -> "{\"access_token\":\"" + token + "\"}";
                case "/user" -> "{\"id\":987654321,\"login\":\"test-user\"}";
                case "/user/installations" -> "{\"total_count\":0,\"installations\":[]}";
                default -> "{}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new GithubApiClient(new tools.jackson.databind.ObjectMapper(), "test-client", "test-secret",
                    "http://localhost/callback", URI.create(base + "/token"), URI.create(base + "/"));
            when(github.authenticate(anyString(), anyString())).thenAnswer(inv -> client.authenticate(inv.getArgument(0), inv.getArgument(1)));
            var result = login("/projects");
            var session = (MockHttpSession) result.getRequest().getSession(false);
            mvc.perform(get("/auth/github/callback").session(session)
                            .param("state", state(result.getResponse().getRedirectedUrl())).param("code", "test-code"))
                    .andExpect(status().is3xxRedirection());
            var bytes = new ByteArrayOutputStream();
            try (var stream = new ObjectOutputStream(bytes)) {
                for (String key : Collections.list(session.getAttributeNames())) stream.writeObject(session.getAttribute(key));
            }
            assertFalse(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).contains(token));
            String row = jdbc.queryForObject("SELECT row_to_json(u)::text FROM public.users u WHERE github_id = ?", String.class, 987654321L);
            assertFalse(row.contains(token));
            assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(token)));
        } finally {
            root.detachAppender(logs);
            logs.stop();
            server.stop(0);
        }
    }

    private org.springframework.test.web.servlet.MvcResult login(String next) throws Exception {
        return mvc.perform(get("/auth/github/login").param("next", next)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("code_challenge_method=S256"))).andReturn();
    }

    private String state(String url) {
        return UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams().getFirst("state");
    }
}
