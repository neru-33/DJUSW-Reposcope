package com.djusw.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(
                new ApiErrorResponse(exception.error(), exception.getMessage(), exception.fields()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", "요청 본문을 확인하세요."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) throws Exception {
        if (exception instanceof org.springframework.security.core.AuthenticationException
                || exception instanceof org.springframework.security.access.AccessDeniedException) {
            throw exception;
        }
        if (exception instanceof org.springframework.web.ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(FrameworkErrors.forStatus(error.getStatusCode().value()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("internal_error", "서버 오류가 발생했습니다."));
    }
}
