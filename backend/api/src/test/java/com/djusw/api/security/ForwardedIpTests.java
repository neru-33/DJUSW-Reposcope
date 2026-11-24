package com.djusw.api.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DB_USER=test", "DB_PASSWORD=test", "FRONTEND_URL=https://www.eclipse-sync.com",
                "CORS_ALLOWED_ORIGIN=https://www.eclipse-sync.com"})
@Import(ForwardedIpTests.TestEndpoints.class)
class ForwardedIpTests {
    @LocalServerPort int port;
    @Value("${app.frontend-url}") String frontendUrl;

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
        var uri = URI.create("http://127.0.0.1:" + port + "/auth/github/callback");
        var withBoth = HttpRequest.newBuilder(uri)
                .header("CF-Connecting-IP", "203.0.113.10")
                .header("X-Forwarded-For", "198.51.100.20").GET().build();
        var response = client.send(withBoth, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("203.0.113.10", response.body());

        var withForwardedForOnly = HttpRequest.newBuilder(uri)
                .header("X-Forwarded-For", "198.51.100.20").GET().build();
        var withoutCloudflare = client.send(withForwardedForOnly, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, withoutCloudflare.statusCode());
        assertNotEquals("198.51.100.20", withoutCloudflare.body());
        assertEquals("127.0.0.1", withoutCloudflare.body());
    }

    @TestConfiguration
    static class TestEndpoints {
        @Bean IpController ipController() { return new IpController(); }
    }

    @RestController
    static class IpController {
        @GetMapping("/auth/github/callback")
        String remoteAddress(HttpServletRequest request) { return request.getRemoteAddr(); }
    }
}
