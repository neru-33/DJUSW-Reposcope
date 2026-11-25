package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GithubApiClient implements GithubIdentityClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String clientId, clientSecret, callbackUrl;
    private final URI tokenEndpoint, apiBase;

    @Autowired
    GithubApiClient(ObjectMapper mapper, @Value("${app.github.client-id}") String clientId,
                    @Value("${app.github.client-secret}") String clientSecret,
                    @Value("${app.github.callback-url}") String callbackUrl) {
        this(mapper, clientId, clientSecret, callbackUrl,
                URI.create("https://github.com/login/oauth/access_token"), URI.create("https://api.github.com/"));
    }

    GithubApiClient(ObjectMapper mapper, String clientId, String clientSecret, String callbackUrl,
                    URI tokenEndpoint, URI apiBase) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = mapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callbackUrl = callbackUrl;
        this.tokenEndpoint = tokenEndpoint;
        this.apiBase = apiBase;
    }

    @Override
    public GithubIdentity authenticate(String code, String verifier) {
        if (clientId.isBlank() || clientSecret.isBlank()) throw unavailable();
        String body = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code) + "&redirect_uri=" + encode(callbackUrl)
                + "&code_verifier=" + encode(verifier);
        try {
            var tokenRequest = HttpRequest.newBuilder(tokenEndpoint)
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode tokenJson = send(tokenRequest);
            JsonNode tokenNode = tokenJson.get("access_token");
            if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asString().isBlank()) throw unavailable();
            String token = tokenNode.asString();
            JsonNode user = get("user", token);
            JsonNode id = user.get("id"), login = user.get("login");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                    || login == null || !login.isTextual() || login.asString().isBlank()) throw unavailable();
            Set<Long> installationIds = listIds("user/installations", "installations", token);
            Set<Long> repositoryIds = new HashSet<>();
            for (long installationId : installationIds) {
                repositoryIds.addAll(listIds("user/installations/" + installationId + "/repositories", "repositories", token));
            }
            return new GithubIdentity(id.asLong(), login.asString(), repositoryIds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            // 요청이나 예외에 토큰이 포함될 수 있으므로 원본 오류를 기록하지 않는다.
            throw unavailable();
        }
    }

    private Set<Long> listIds(String path, String field, String token) throws Exception {
        Set<Long> ids = new HashSet<>();
        long expected = -1;
        for (int page = 1; ; page++) {
            JsonNode result = get(path + "?per_page=100&page=" + page, token);
            JsonNode total = result.get("total_count"), items = result.get(field);
            if (total == null || !total.isIntegralNumber() || !total.canConvertToLong() || total.asLong() < 0
                    || items == null || !items.isArray() || items.size() > 100) throw unavailable();
            if (expected == -1) expected = total.asLong();
            if (expected != total.asLong() || (items.isEmpty() && ids.size() < expected)) throw unavailable();
            for (JsonNode item : items) {
                JsonNode id = item.get("id");
                if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                        || !ids.add(id.asLong())) throw unavailable();
            }
            if (ids.size() == expected) return ids;
            if (ids.size() > expected || page == Integer.MAX_VALUE) throw unavailable();
        }
    }

    private JsonNode get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(apiBase.resolve(path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw unavailable();
        return mapper.readTree(response.body());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "internal_error", "GitHub 인증을 완료할 수 없습니다.");
    }
}
