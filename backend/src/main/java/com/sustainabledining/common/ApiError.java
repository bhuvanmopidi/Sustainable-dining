package com.sustainabledining.common;

import java.util.List;

/** Error body for every non-2xx response from the backend's own API. */
public record ApiError(String error, String message, List<Detail> details) {
    public record Detail(String field, String message) {}
}