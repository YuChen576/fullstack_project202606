package com.example.seating.common;

public record ApiResponse<T>(ErrorCode code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK, "OK", data);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
