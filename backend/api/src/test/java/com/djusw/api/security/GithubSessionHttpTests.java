package com.djusw.api.security;

import com.djusw.api.support.TestDatabaseConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "app.github.client-id=test-client", "app.github.client-secret=test-secret",
        "server.servlet.session.cookie.secure=true"})
@Import(TestDatabaseConfiguration.class)
class GithubSessionHttpTests {
    @LocalServerPort int port;
    @MockitoBean GithubIdentityClient github;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void cookieCsrfAndLogoutWorkOverHttp() throws Exception {
        long githubId = 987654322L;
        jdbc.update("DELETE FROM public.users WHERE github_id = ?", githubId);
        when(github.authenticate(anyString(), anyString()))
                .thenReturn(new GithubIdentityClient.GithubIdentity(githubId, "http-test-user", java.util.Set.of()));
        var login = client.send(HttpRequest.newBuilder(url("/auth/github/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode());
        String cookie = login.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Lax"));
        String oldCookie = cookie.split(";", 2)[0];
        String state = UriComponentsBuilder.fromUriString(login.headers().firstValue("location").orElseThrow())
                .build().getQueryParams().getFirst("state");
        var callback = client.send(HttpRequest.newBuilder(url("/auth/github/callback?state=" + state + "&code=test-code"))
                .header("Cookie", oldCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, callback.statusCode());
        String newCookie = callback.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertNotEquals(oldCookie, newCookie);
        var me = client.send(HttpRequest.newBuilder(url("/api/me")).header("Cookie", newCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        String csrf = new ObjectMapper().readTree(me.body()).get("csrf_token").asString();
        var noCsrf = client.send(HttpRequest.newBuilder(url("/auth/logout")).header("Cookie", newCookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode());
        var logout = client.send(HttpRequest.newBuilder(url("/auth/logout")).header("Cookie", newCookie)
                .header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logout.statusCode());
        assertTrue(logout.headers().firstValue("set-cookie").orElseThrow().contains("Max-Age=0"));
        var after = client.send(HttpRequest.newBuilder(url("/api/me")).header("Cookie", newCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, after.statusCode());
    }

    private URI url(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
