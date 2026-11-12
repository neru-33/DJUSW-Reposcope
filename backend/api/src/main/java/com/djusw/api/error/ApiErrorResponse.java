package com.djusw.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String error, String message, Map<String, String> fields) {
    public ApiErrorResponse(String error, String message) {
        this(error, message, null);
    }
}
