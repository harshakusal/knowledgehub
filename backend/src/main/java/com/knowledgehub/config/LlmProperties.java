package com.knowledgehub.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        int embeddingDim,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration maxRetryAfter,
        float circuitFailureRate,
        int circuitMinCalls,
        int circuitWindow,
        Duration circuitOpenDuration) {

    /** Never print the API key (records include all components in toString by default). */
    @Override
    public String toString() {
        return "LlmProperties[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "****") + "]";
    }
}
