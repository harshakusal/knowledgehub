package com.knowledgehub.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgehub.config.LlmProperties;
import com.knowledgehub.exception.LlmClientException;
import com.knowledgehub.exception.LlmUnavailableException;
import com.knowledgehub.model.ChatMessage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Resilient client for the (mock) LLM API.
 * <ul>
 *   <li>Retries ONLY 429, 503 and connect/read timeouts; never 400/401 or other 4xx.</li>
 *   <li>Exponential back-off with full jitter, max {@code llm.max-attempts} (4) attempts, honouring Retry-After.</li>
 *   <li>Resilience4j circuit breaker around each attempt; when open we fail fast with {@link LlmUnavailableException}.</li>
 *   <li>Explicit connect/read timeouts. API key comes from env (LLM_API_KEY) and is never logged.</li>
 * </ul>
 */
@Component
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LlmProperties props;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Sleeper sleeper;
    private final CircuitBreaker breaker;

    public LlmClient(LlmProperties props, RestClient.Builder builder, Sleeper sleeper) {
        this.props = props;
        this.sleeper = sleeper;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.readTimeout());
        RestClient.Builder b = builder.clone().baseUrl(props.baseUrl()).requestFactory(factory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        this.http = b.build();
        this.breaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .failureRateThreshold(props.circuitFailureRate())
                .minimumNumberOfCalls(props.circuitMinCalls())
                .slidingWindowSize(props.circuitWindow())
                .waitDurationInOpenState(props.circuitOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(RetryableLlmException.class)
                .build());
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public List<float[]> embed(List<String> inputs) {
        Map<String, Object> body = Map.of("model", props.model(), "input", inputs);
        JsonNode json = execute("embeddings", () -> http.post().uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> readJson(res), true));
        List<float[]> out = new ArrayList<>();
        for (JsonNode item : json.path("data")) {
            JsonNode e = item.path("embedding");
            if (e.size() != props.embeddingDim()) {
                throw new LlmClientException(200, "Embedding dimension mismatch: " + e.size());
            }
            float[] v = new float[e.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) e.get(i).asDouble();
            }
            out.add(v);
        }
        if (out.size() != inputs.size()) {
            throw new LlmClientException(200, "Embedding count mismatch");
        }
        return out;
    }

    /** Non-streaming completion. When {@code jsonMode} we ask the model for a JSON object. */
    public String complete(List<ChatMessage> messages, boolean jsonMode) {
        Map<String, Object> body = chatBody(messages, false, jsonMode);
        JsonNode json = execute("chat", () -> http.post().uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> readJson(res), true));
        return json.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * Streams tokens to {@code onToken}. Retries only happen before the first token is delivered
     * (re-sending tokens would duplicate output). Stops reading and closes the upstream connection as soon
     * as {@code cancelled} returns true (e.g. browser pressed Stop / disconnected).
     */
    public void streamChat(List<ChatMessage> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
        Map<String, Object> body = chatBody(messages, true, false);
        execute("chat-stream", () -> http.post().uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).body(body)
                .exchange((req, res) -> {
                    checkStatus(res);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            String token = mapper.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                            if (!token.isEmpty()) {
                                onToken.accept(token);
                            }
                        }
                    }
                    if (cancelled.getAsBoolean()) {
                        log.info("LLM stream cancelled by client; upstream connection closed");
                    }
                    return Boolean.TRUE;
                }, true));
    }

    private Map<String, Object> chatBody(List<ChatMessage> messages, boolean stream, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", messages);
        body.put("stream", stream);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private JsonNode readJson(ClientHttpResponse res) throws IOException {
        checkStatus(res);
        return mapper.readTree(res.getBody());
    }

    private void checkStatus(ClientHttpResponse res) throws IOException {
        HttpStatusCode status = res.getStatusCode();
        if (status.is2xxSuccessful()) {
            return;
        }
        int code = status.value();
        if (code == 429 || code == 503) {
            throw new RetryableLlmException("LLM returned " + code, parseRetryAfter(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)), null);
        }
        throw new LlmClientException(code, "LLM returned non-retryable status " + code);
    }

    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null; // HTTP-date form not used by our provider; fall back to back-off
        }
    }

    <T> T execute(String op, Supplier<T> call) {
        int max = Math.max(1, props.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                return breaker.executeSupplier(() -> {
                    try {
                        return call.get();
                    } catch (ResourceAccessException e) {
                        // connect timeout, connection refused, read timeout
                        throw new RetryableLlmException("LLM I/O failure: " + e.getClass().getSimpleName(), null, e);
                    }
                });
            } catch (CallNotPermittedException e) {
                throw new LlmUnavailableException("The AI service is temporarily unavailable (circuit open). Please try again shortly.", e);
            } catch (RetryableLlmException e) {
                if (attempt >= max) {
                    log.warn("LLM {} failed after {} attempts: {}", op, attempt, e.getMessage());
                    throw new LlmUnavailableException("The AI service is busy or unreachable. Please try again shortly.", e);
                }
                Duration delay = backoff(attempt, e.retryAfter());
                if (delay == null) {
                    throw new LlmUnavailableException("The AI service asked us to wait too long. Please try again later.", e);
                }
                log.info("LLM {} attempt {}/{} failed ({}); retrying in {} ms", op, attempt, max, e.getMessage(), delay.toMillis());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmUnavailableException("Interrupted while waiting to retry", ie);
                }
            }
        }
    }

    /** Exponential back-off with "equal" jitter (random in [exp/2, exp]); back-off; Retry-After acts as a lower bound. Returns null if Retry-After exceeds our cap. */
    Duration backoff(int attempt, Duration retryAfter) {
        long cap = props.maxBackoff().toMillis();
        long exp = Math.min(cap, props.initialBackoff().toMillis() * (1L << (attempt - 1)));
        long jittered = ThreadLocalRandom.current().nextLong(exp / 2, exp + 1);
        if (retryAfter != null) {
            if (retryAfter.compareTo(props.maxRetryAfter()) > 0) {
                return null;
            }
            return Duration.ofMillis(Math.max(jittered, retryAfter.toMillis()));
        }
        return Duration.ofMillis(jittered);
    }
}
