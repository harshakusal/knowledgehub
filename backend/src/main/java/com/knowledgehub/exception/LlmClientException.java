package com.knowledgehub.exception;

/** Non-retryable LLM error (e.g. 400/401): a bug or misconfiguration on our side. Mapped to 502. */
public class LlmClientException extends RuntimeException {
    private final int status;

    public LlmClientException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
