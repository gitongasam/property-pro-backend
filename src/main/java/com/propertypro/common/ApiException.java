package com.propertypro.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A business error with an HTTP status and a stable code the frontend can switch on. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /** The organisation's plan does not include a feature or its limit is reached. */
    public static ApiException planLimit(String code, String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, code, message);
    }
}
