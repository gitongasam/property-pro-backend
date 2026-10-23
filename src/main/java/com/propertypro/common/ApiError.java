package com.propertypro.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

/** The single error shape every endpoint returns. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, code, message, path, Map.of());
    }
}
