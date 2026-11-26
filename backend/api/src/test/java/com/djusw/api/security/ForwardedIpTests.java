package com.djusw.api.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DB_USER=test", "DB_PASSWORD=test", "FRONTEND_URL=https://www.eclipse-sync.com",
                "CORS_ALLOWED_ORIGIN=https://www.eclipse-sync.com"})
class ForwardedIpTests {
    @LocalServerPort int port;
    @Value("${app.frontend-url}") String frontendUrl;
    @MockitoBean OperatorLoginService operatorLogin;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @Test
    void resolvesFrontendUrlFromEnvironmentProperty() {
        assertEquals("https://www.eclipse-sync.com", frontendUrl);
    }

    @Test
    void resolvesCorsOriginFromEnvironmentProperty() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                .header("Origin", "https://www.eclipse-sync.com").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("https://www.eclipse-sync.com", response.headers()
                .firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @Test
    void disallowedOriginReceivesVaryOriginOnRealServer() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/api/health");
        var client = HttpClient.newHttpClient();
        for (String method : new String[]{"GET", "OPTIONS"}) {
            var builder = HttpRequest.newBuilder(uri).header("Origin", "https://evil.example");
            if (method.equals("OPTIONS")) {
                builder.header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type, X-CSRF-Token");
            }
            var response = client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertEquals(true, response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals(true, response.headers().allValues("Vary").stream()
                    .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                    .anyMatch(value -> value.trim().equalsIgnoreCase("Origin")));
            assertEquals(true, response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        }
    }

    @Test
    void tomcatUsesCloudflareHeaderInsteadOfForwardedFor() throws Exception {
        var client = HttpClient.newHttpClient();
        var uri = URI.create("http://127.0.0.1:" + port + "/auth/admin/login");
        when(operatorLogin.login(anyString(), anyString(), anyString()))
                .thenReturn(OperatorLoginService.Result.failure(OperatorLoginService.ResultType.INVALID));
        String username = java.util.UUID.randomUUID().toString();
        String password = java.util.UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(new OperatorLoginController.LoginRequest(username, password));
        var withBoth = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("CF-Connecting-IP", "203.0.113.10")
                .header("X-Forwarded-For", "198.51.100.20").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(withBoth, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        verify(operatorLogin).login(username, password, "203.0.113.10");

        var withForwardedForOnly = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100.20").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var withoutCloudflare = client.send(withForwardedForOnly, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, withoutCloudflare.statusCode());
        verify(operatorLogin).login(username, password, "127.0.0.1");
    }
}
