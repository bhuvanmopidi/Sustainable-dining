package com.sustainabledining.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<ApiError.Detail> details;

    public ApiException(HttpStatus status, String code, String message, List<ApiError.Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found", List.of());
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of());
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, List.of());
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<ApiError.Detail> details() { return details; }
}