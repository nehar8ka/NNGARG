package com.f1betting.adapter.openf1.exception;

import lombok.Getter;

/**
 * Typed exception thrown when the external OpenF1 API responds with HTTP 429 Too Many Requests.
 * Replaces fragile string-matching on exception messages with strong type classification,
 * preserving any upstream Retry-After backoff duration.
 */
@Getter
public class OpenF1RateLimitExceededException extends RuntimeException {

    private final Long retryAfterSeconds;

    public OpenF1RateLimitExceededException(String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public OpenF1RateLimitExceededException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
