package com.djusw.api.error;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FrameworkErrorsTests {
    @ParameterizedTest
    @CsvSource({
            "400, invalid_request",
            "401, unauthorized",
            "403, forbidden",
            "404, not_found",
            "405, invalid_request",
            "415, invalid_request",
            "500, internal_error",
            "503, internal_error"
    })
    void mapsStatusToSpecErrorCode(int status, String error) {
        ApiErrorResponse response = FrameworkErrors.forStatus(status);
        assertEquals(error, response.error());
        assertFalse(response.message().isBlank());
    }
}
