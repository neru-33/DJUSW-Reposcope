package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class GithubApiClientTests {
    private HttpServer server;
    private boolean failSecondPage;
    private boolean missingPage;
    private GithubApiClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new GithubApiClient(new ObjectMapper(), "test-id", "test-secret", "http://localhost/callback",
                URI.create(base + "/token"), URI.create(base + "/"));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void retrievesAllInstallationAndRepositoryPages() {
        var identity = client.authenticate("test-code", "test-verifier");
        assertEquals(123L, identity.id());
        assertEquals(101, identity.repositoryIds().size());
        assertTrue(identity.repositoryIds().contains(1001L));
        assertTrue(identity.repositoryIds().contains(1101L));
    }

    @Test
    void failureOrIncompleteSecondPageFailsTheWholeLogin() {
        failSecondPage = true;
        assertThrows(ApiException.class, () -> client.authenticate("test-code", "test-verifier"));
        failSecondPage = false;
        missingPage = true;
        assertThrows(ApiException.class, () -> client.authenticate("test-code", "test-verifier"));
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        int status = 200;
        String body;
        if (path.equals("/token")) {
            body = "{\"access_token\":\"fake-token\"}";
        } else if (!"Bearer fake-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            status = 401;
            body = "{}";
        } else if (path.equals("/user")) {
            body = "{\"id\":123,\"login\":\"test-user\"}";
        } else if (path.equals("/user/installations")) {
            body = query.contains("page=2") ? list("installations", 101, 101, 101) : list("installations", 101, 1, 100);
        } else if (path.equals("/user/installations/1/repositories")) {
            if (query.contains("page=2") && failSecondPage) { status = 500; body = "{}"; }
            else if (query.contains("page=2") && missingPage) body = "{\"total_count\":101,\"repositories\":[]}";
            else body = query.contains("page=2") ? list("repositories", 101, 1101, 1101) : list("repositories", 101, 1001, 1100);
        } else if (path.startsWith("/user/installations/") && path.endsWith("/repositories")) {
            body = "{\"total_count\":0,\"repositories\":[]}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String list(String field, int total, int first, int last) {
        String ids = IntStream.rangeClosed(first, last).mapToObj(id -> "{\"id\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"total_count\":" + total + ",\"" + field + "\":[" + ids + "]}";
    }
}
