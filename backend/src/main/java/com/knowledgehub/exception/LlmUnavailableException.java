package com.knowledgehub.exception;

/** The LLM could not be reached after retries, or the circuit breaker is open. Mapped to 503. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
