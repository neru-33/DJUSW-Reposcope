package com.djusw.api.project;

import com.djusw.api.error.ApiException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class DeliverableInputTests {
    @Test
    void acceptsKindsAndTitleBoundaries() {
        for (String kind : List.of("DOCUMENT", "SLIDES", "DEMO_VIDEO", "REPOSITORY", "OTHER")) {
            assertDoesNotThrow(() -> new DeliverableInput(kind, "가", "https://example.com").validate());
        }
        assertDoesNotThrow(() -> new DeliverableInput("OTHER", "😀".repeat(100), "https://example.com/a?q=1").validate());
    }

    @Test
    void rejectsInvalidFields() {
        for (String kind : Arrays.asList(null, "", "document", "UNKNOWN")) {
            invalid(new DeliverableInput(kind, "제목", "https://example.com"));
        }
        for (String title : Arrays.asList(null, "", " ", "가".repeat(101))) {
            invalid(new DeliverableInput("OTHER", title, "https://example.com"));
        }
        for (String url : Arrays.asList(null, "", "http://example.com", "https://", "https://example.com bad",
                "https://user@example.com", "https://example.com:99999")) {
            invalid(new DeliverableInput("OTHER", "제목", url));
        }
    }

    private void invalid(DeliverableInput input) {
        ApiException error = assertThrows(ApiException.class, input::validate);
        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("invalid_request", error.error());
    }
}
