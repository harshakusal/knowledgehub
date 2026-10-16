package com.knowledgehub.client;

import java.time.Duration;

/** 429 / 503 / connect-or-read timeout: safe to retry. Only this type is recorded by the circuit breaker. */
class RetryableLlmException extends RuntimeException {
    private final Duration retryAfter;

    RetryableLlmException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
