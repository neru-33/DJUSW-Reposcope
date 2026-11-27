package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.http.HttpStatus;

record DeliverableInput(String kind, String title, String url) {
    private static final Set<String> KINDS = Set.of("DOCUMENT", "SLIDES", "DEMO_VIDEO", "REPOSITORY", "OTHER");

    void validate() {
        if (kind == null || !KINDS.contains(kind) || title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 100 || !validUrl(url)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "결과물 정보를 확인하세요.");
        }
    }

    private static boolean validUrl(String value) {
        if (value == null || !value.startsWith("https://")) return false;
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() <= 65535);
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
