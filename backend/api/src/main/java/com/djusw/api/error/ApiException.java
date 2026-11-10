package com.djusw.api.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, null);
    }

    public ApiException(HttpStatus status, String error, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.error = error;
        this.fields = fields == null ? null : Map.copyOf(fields);
    }

    public HttpStatus status() { return status; }
    public String error() { return error; }
    public Map<String, String> fields() { return fields; }
}
