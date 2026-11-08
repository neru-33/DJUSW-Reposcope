package com.djusw.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final String appVersion;

    public HealthController(@Value("${APP_VERSION:unknown}") String appVersion) {
        this.appVersion = appVersion;
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    @GetMapping("/api/version")
    public VersionResponse version() {
        return new VersionResponse(appVersion);
    }
    public record HealthResponse(String status) {}

    public record VersionResponse(String version) {}
}
