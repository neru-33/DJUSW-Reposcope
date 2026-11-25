package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class GithubApiClientTests {
    private HttpServer server;
    private boolean failSecondPage;
    private boolean missingPage;
    private String tokenError;
    private String failedPath;
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

    @Test
    void logsTokenErrorWithoutResponseOrCredentials() {
        var logs = captureLogs();
        try {
            tokenError = "incorrect_client_credentials";
            assertThrows(ApiException.class, () -> client.authenticate("test-code", "test-verifier"));
            String output = messages(logs);
            assertTrue(output.contains("error=incorrect_client_credentials"));
            assertFalse(output.contains("test-secret"));
            assertFalse(output.contains("sensitive-response-body"));

            logs.list.clear();
            tokenError = "sensitive-response-body";
            assertThrows(ApiException.class, () -> client.authenticate("test-code", "test-verifier"));
            assertTrue(messages(logs).contains("error=unknown"));
            assertFalse(messages(logs).contains("sensitive-response-body"));
        } finally {
            ((Logger) LoggerFactory.getLogger(GithubApiClient.class)).detachAppender(logs);
        }
    }

    @Test
    void logsApiPathAndStatusWithoutTokenOrResponse() {
        var logs = captureLogs();
        try {
            failedPath = "/user/installations/1/repositories";
            assertThrows(ApiException.class, () -> client.authenticate("test-code", "test-verifier"));
            String output = messages(logs);
            assertTrue(output.contains("path=user/installations/{id}/repositories, status=403"));
            assertFalse(output.contains("fake-token"));
            assertFalse(output.contains("sensitive-response-body"));
        } finally {
            ((Logger) LoggerFactory.getLogger(GithubApiClient.class)).detachAppender(logs);
        }
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(GithubApiClient.class)).addAppender(logs);
        return logs;
    }

    private static String messages(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        int status = 200;
        String body;
        if (path.equals("/token")) {
            body = tokenError == null ? "{\"access_token\":\"fake-token\"}"
                    : "{\"error\":\"" + tokenError + "\",\"error_description\":\"sensitive-response-body\"}";
        } else if (!"Bearer fake-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            status = 401;
            body = "{}";
        } else if (path.equals(failedPath)) {
            status = 403;
            body = "{\"message\":\"sensitive-response-body\"}";
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
