package com.propertypro.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/** 429 with a Retry-After header, so clients know when to try again. */
@Getter
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String code, String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
    }

    /** "Try again in 3 minutes" style wording for the message. */
    public static String waitText(Duration d) {
        long minutes = (d.toSeconds() + 59) / 60;
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }
}
