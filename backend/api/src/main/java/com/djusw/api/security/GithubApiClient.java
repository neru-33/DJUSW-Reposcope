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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GithubApiClient implements GithubIdentityClient {
    private static final Logger log = LoggerFactory.getLogger(GithubApiClient.class);
    private static final Set<String> TOKEN_ERRORS = Set.of("incorrect_client_credentials", "redirect_uri_mismatch",
            "bad_verification_code", "unverified_user_email", "access_denied", "device_flow_disabled",
            "expired_token", "incorrect_device_code", "incorrect_user_code", "authorization_pending",
            "slow_down", "unsupported_grant_type");
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
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("GitHub token exchange failed: missing client configuration");
            throw unavailable();
        }
        String body = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code) + "&redirect_uri=" + encode(callbackUrl)
                + "&code_verifier=" + encode(verifier);
        try {
            var tokenRequest = HttpRequest.newBuilder(tokenEndpoint)
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode tokenJson = send(tokenRequest, "token exchange", true);
            JsonNode error = tokenJson.get("error");
            if (error != null) {
                log.warn("GitHub token exchange failed: error={}", safeTokenError(error));
                throw unavailable();
            }
            JsonNode tokenNode = tokenJson.get("access_token");
            if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asString().isBlank()) {
                log.warn("GitHub token exchange failed: missing access token");
                throw unavailable();
            }
            String token = tokenNode.asString();
            JsonNode user = get("user", token);
            JsonNode id = user.get("id"), login = user.get("login");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                    || login == null || !login.isTextual() || login.asString().isBlank()) throw invalidResponse("user");
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
                    || items == null || !items.isArray() || items.size() > 100) throw invalidResponse(path);
            if (expected == -1) expected = total.asLong();
            if (expected != total.asLong() || (items.isEmpty() && ids.size() < expected)) throw invalidResponse(path);
            for (JsonNode item : items) {
                JsonNode id = item.get("id");
                if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                        || !ids.add(id.asLong())) throw invalidResponse(path);
            }
            if (ids.size() == expected) return ids;
            if (ids.size() > expected || page == Integer.MAX_VALUE) throw invalidResponse(path);
        }
    }

    private JsonNode get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(apiBase.resolve(path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        String logPath = safePath(path);
        return send(request, logPath, false);
    }

    private JsonNode send(HttpRequest request, String stage, boolean tokenExchange) throws Exception {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            log.warn("GitHub {} failed: request failed", stage);
            throw exception;
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (Exception exception) {
            log.warn("GitHub {} failed: status={}, invalid response", stage, response.statusCode());
            throw unavailable();
        }
        if (response.statusCode() != 200) {
            if (tokenExchange) {
                log.warn("GitHub token exchange failed: status={}, error={}", response.statusCode(),
                        safeTokenError(json == null ? null : json.get("error")));
            } else {
                log.warn("GitHub API request failed: path={}, status={}", stage, response.statusCode());
            }
            throw unavailable();
        }
        if (json == null || !json.isObject()) {
            log.warn("GitHub {} failed: invalid response", stage);
            throw unavailable();
        }
        return json;
    }

    private static String safeTokenError(JsonNode error) {
        if (error == null || !error.isTextual()) return "unknown";
        String value = error.asString();
        return TOKEN_ERRORS.contains(value) ? value : "unknown";
    }

    private static String safePath(String path) {
        return path.split("\\?", 2)[0].replaceAll("(?<=user/installations/)\\d+(?=/repositories)", "{id}");
    }

    private static ApiException invalidResponse(String path) {
        log.warn("GitHub API request failed: path={}, status=200, invalid response", safePath(path));
        return unavailable();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "internal_error", "GitHub 인증을 완료할 수 없습니다.");
    }
}
