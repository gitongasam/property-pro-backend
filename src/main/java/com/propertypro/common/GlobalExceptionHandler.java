package com.propertypro.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([a-z0-9_]+)\"");

    /** Friendly messages for database constraints a user can actually trigger. */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("ux_units_property_unit_no", "A unit with this number already exists in the property"),
            Map.entry("ux_units_payment_code", "This payment code is already used by another unit"),
            Map.entry("ux_properties_org_code", "A property with this code already exists"),
            Map.entry("ux_users_email", "An account with this email already exists"),
            Map.entry("ux_users_phone", "This phone number is already used by another account"),
            Map.entry("organizations_slug_key", "This organisation name is already taken"),
            Map.entry("ex_leases_no_overlap", "The unit already has an active lease for these dates"),
            Map.entry("ux_payments_reference", "A payment with this reference has already been recorded"));

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ApiError body = new ApiError(OffsetDateTime.now(), 400, "VALIDATION_FAILED",
                "Some fields are invalid", req.getRequestURI(), fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleUnreadable(Exception ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request could not be read", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String detail = String.valueOf(NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        Matcher m = CONSTRAINT.matcher(detail);
        String constraint = m.find() ? m.group(1) : null;
        String message = constraint != null && CONSTRAINT_MESSAGES.containsKey(constraint)
                ? CONSTRAINT_MESSAGES.get(constraint)
                : "The change conflicts with existing data";
        log.info("Integrity violation on {}: {}", constraint, detail);
        return respond(HttpStatus.CONFLICT, "CONFLICT", message, req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do this", req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Spring MVC's own errors (404 no route, 405 wrong method, missing parameter...) keep their status
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return respond(status, status.name(), errorResponse.getBody().getDetail() == null
                    ? status.getReasonPhrase() : errorResponse.getBody().getDetail(), req);
        }
        log.error("Unexpected error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", req);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message, req.getRequestURI()));
    }
}
