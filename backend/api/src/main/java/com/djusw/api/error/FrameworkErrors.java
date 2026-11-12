package com.djusw.api.error;

final class FrameworkErrors {
    private FrameworkErrors() {}

    static ApiErrorResponse forStatus(int status) {
        return switch (status) {
            case 400 -> new ApiErrorResponse("invalid_request", "요청을 확인하세요.");
            case 401 -> new ApiErrorResponse("unauthorized", "로그인이 필요합니다.");
            case 403 -> new ApiErrorResponse("forbidden", "접근 권한이 없습니다.");
            case 404 -> new ApiErrorResponse("not_found", "요청한 대상을 찾을 수 없습니다.");
            default -> status >= 500
                    ? new ApiErrorResponse("internal_error", "서버 오류가 발생했습니다.")
                    : new ApiErrorResponse("invalid_request", "요청을 처리할 수 없습니다.");
        };
    }
}
