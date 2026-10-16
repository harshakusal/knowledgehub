package com.knowledgehub.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.knowledgehub.config.LlmProperties;
import com.knowledgehub.exception.LlmClientException;
import com.knowledgehub.exception.LlmUnavailableException;
import com.knowledgehub.model.ChatMessage;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmClientTest {
    private WireMockServer wm;
    private final List<Duration> sleeps = new ArrayList<>();
    private LlmClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = newClient(20);
    }

    private LlmClient newClient(int minCalls) {
        LlmProperties props = new LlmProperties("http://localhost:" + wm.port(), "secret-key", "mock-1", 3,
                Duration.ofMillis(500), Duration.ofMillis(300), 4, Duration.ofMillis(100), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 50f, minCalls, minCalls, Duration.ofSeconds(30));
        return new LlmClient(props, RestClient.builder(), sleeps::add);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static final String EMBED_OK = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}";
    private static final String CHAT_OK = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";

    @Test
    void retriesOn429HonouringRetryAfterThenSucceeds() {
        wm.stubFor(post("/v1/embeddings").inScenario("s").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2")).willSetStateTo("ok"));
        wm.stubFor(post("/v1/embeddings").inScenario("s").whenScenarioStateIs("ok")
                .willReturn(okJson(EMBED_OK)));

        List<float[]> out = client.embed(List.of("x"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void retries503UpToFourAttemptsThenFailsWithClearError() {
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("busy or unreachable");
        wm.verify(4, postRequestedFor(urlEqualTo("/v1/chat")));
        assertThat(sleeps).hasSize(3);
        // exponential: each base doubles (100, 200, 400) with jitter in [base/2, base]
        assertThat(sleeps.get(0).toMillis()).isBetween(50L, 100L);
        assertThat(sleeps.get(1).toMillis()).isBetween(100L, 200L);
        assertThat(sleeps.get(2).toMillis()).isBetween(200L, 400L);
    }

    @Test
    void neverRetries400() {
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmClientException.class);
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/chat")));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void neverRetries401() {
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmClientException.class);
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/chat")));
    }

    @Test
    void retriesReadTimeout() {
        wm.stubFor(post("/v1/chat").inScenario("t").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(CHAT_OK).withFixedDelay(1500)).willSetStateTo("fast"));
        wm.stubFor(post("/v1/chat").inScenario("t").whenScenarioStateIs("fast").willReturn(okJson(CHAT_OK)));

        assertThat(client.complete(List.of(ChatMessage.user("q")), false)).isEqualTo("hello");
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void givesUpWhenRetryAfterExceedsCap() {
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmUnavailableException.class);
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/chat")));
    }

    @Test
    void circuitOpensAndFailsFastWithoutCallingServer() {
        LlmClient small = newClient(4);
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> small.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(small.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        wm.resetRequests();

        assertThatThrownBy(() -> small.complete(List.of(ChatMessage.user("q")), false))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("circuit open");
        wm.verify(0, postRequestedFor(urlEqualTo("/v1/chat")));
    }

    @Test
    void clientErrorsDoNotOpenCircuit() {
        LlmClient small = newClient(4);
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withStatus(400)));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> small.complete(List.of(ChatMessage.user("q")), false)).isInstanceOf(LlmClientException.class);
        }
        assertThat(small.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void sendsApiKeyAsBearerAndMasksItInToString() {
        wm.stubFor(post("/v1/chat").willReturn(okJson(CHAT_OK)));
        client.complete(List.of(ChatMessage.user("q")), true);
        wm.verify(postRequestedFor(urlEqualTo("/v1/chat")).withHeader("Authorization", equalTo("Bearer secret-key"))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object"))));
        LlmProperties p = new LlmProperties("u", "secret-key", "m", 3, null, null, 1, null, null, null, 1, 1, 1, null);
        assertThat(p.toString()).doesNotContain("secret-key");
    }

    @Test
    void streamsTokensAndStopsAtDone() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: [DONE]\n\n";
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse)));
        StringBuilder sb = new StringBuilder();
        client.streamChat(List.of(ChatMessage.user("q")), sb::append, () -> false);
        assertThat(sb.toString()).isEqualTo("Hello");
    }

    @Test
    void streamStopsReadingWhenCancelled() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n".repeat(50) + "data: [DONE]\n\n";
        wm.stubFor(post("/v1/chat").willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse)));
        List<String> tokens = new ArrayList<>();
        client.streamChat(List.of(ChatMessage.user("q")), tokens::add, () -> tokens.size() >= 3);
        assertThat(tokens).hasSize(3);
    }

    @Test
    void parsesRetryAfter() {
        assertThat(LlmClient.parseRetryAfter("3")).isEqualTo(Duration.ofSeconds(3));
        assertThat(LlmClient.parseRetryAfter(null)).isNull();
        assertThat(LlmClient.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isNull();
    }
}
