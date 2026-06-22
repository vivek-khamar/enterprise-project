package com.enterprise.demo.client;

import com.enterprise.demo.config.AnthropicProperties;
import com.enterprise.demo.dto.CategorizationResult;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the caching contract of {@link TransactionCategorizationClient}:
 * identical (merchant, description) pairs within the 24-hour TTL window must
 * not trigger a second call to the Anthropic API.
 *
 * <p>Uses a minimal Spring context (no web layer, no database) so startup is fast.
 * {@code MockRestServiceServer} is bound to the {@code RestClient.Builder} before the
 * client's constructor calls {@code builder.baseUrl(...).build()}, which installs the
 * mock interceptor in the resulting {@code RestClient}.
 *
 * <h2>Per-request cost estimate</h2>
 * See {@link #estimatedCostPerUncachedCall_isBelowOneCent()} for the arithmetic.
 * Short answer: <strong>≈ $0.0014 per uncached call</strong> with
 * claude-sonnet-4-6 at $3/MTok input · $15/MTok output.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TransactionCategorizationClientCacheTest.Config.class)
class TransactionCategorizationClientCacheTest {

    // ── test Spring context ───────────────────────────────────────────────────

    @Configuration
    @EnableCaching
    static class Config {
        // Bind the mock server BEFORE the client's constructor calls builder.build(),
        // so the resulting RestClient routes requests through the mock interceptor.
        private final RestClient.Builder       builder    = RestClient.builder();
        private final MockRestServiceServer    mockServer =
                MockRestServiceServer.bindTo(builder).build();

        @Bean public MockRestServiceServer mockServer() { return mockServer; }

        @Bean
        public CacheManager cacheManager() {
            CaffeineCacheManager manager = new CaffeineCacheManager();
            manager.registerCustomCache("transaction-categories",
                    Caffeine.newBuilder()
                            .maximumSize(100)
                            .expireAfterWrite(24, TimeUnit.HOURS)
                            .<Object, Object>build());
            return manager;
        }

        @Bean
        public TransactionCategorizationClient client() {
            AnthropicProperties props = new AnthropicProperties();
            props.setApiKey("test-key");
            props.setBaseUrl("https://api.anthropic.com");
            props.setModel("claude-sonnet-4-6");
            return new TransactionCategorizationClient(builder, props, new ObjectMapper());
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String FOOD_JSON =
            "{\"category\":\"FOOD\",\"confidence\":0.95,"
            + "\"reasoning\":\"Coffee shop purchase\",\"fraudSignals\":[]}";

    @Autowired private MockRestServiceServer       mockServer;
    @Autowired private TransactionCategorizationClient client;
    @Autowired private CacheManager                cacheManager;

    @BeforeEach
    void resetState() {
        mockServer.reset();
        cacheManager.getCache("transaction-categories").clear();
    }

    // ── cache hit / miss behaviour ────────────────────────────────────────────

    @Test
    void secondCallWithSameArgs_servesFromCache_andSkipsApi() {
        // Register exactly ONE expectation. The mock server throws AssertionError on
        // any additional request, so this implicitly catches an unexpected second call.
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(response(FOOD_JSON), MediaType.APPLICATION_JSON));

        client.categorize("Starbucks", "Coffee latte");
        client.categorize("Starbucks", "Coffee latte"); // cache hit — no HTTP request

        mockServer.verify(); // all expectations satisfied (exactly one call)
    }

    @Test
    void callsWithDifferentArgs_eachMakeApiRequest() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(response(FOOD_JSON), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(response(FOOD_JSON), MediaType.APPLICATION_JSON));

        client.categorize("Starbucks", "Coffee latte");
        client.categorize("McDonald's", "Burger and fries"); // different key — cache miss

        mockServer.verify();
    }

    @Test
    void cacheKeyNormalizesToLowerCase_preventsDuplicateApiCall() {
        // Key = merchant.toLowerCase() + '|' + description.toLowerCase()
        // "STARBUCKS|COFFEE LATTE" == "starbucks|coffee latte" → one API call
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(response(FOOD_JSON), MediaType.APPLICATION_JSON));

        client.categorize("STARBUCKS", "COFFEE LATTE");
        client.categorize("starbucks", "coffee latte");

        mockServer.verify();
    }

    @Test
    void secondCallReturnsSameObjectFromCache() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(response(FOOD_JSON), MediaType.APPLICATION_JSON));

        CategorizationResult first  = client.categorize("Starbucks", "Coffee latte");
        CategorizationResult second = client.categorize("Starbucks", "Coffee latte");

        assertThat(second).isEqualTo(first);
    }

    // ── per-request cost estimate ─────────────────────────────────────────────

    /**
     * Estimates the Anthropic API cost for a single <em>uncached</em> categorization call.
     *
     * <p>Token count methodology:
     * <ul>
     *   <li>English prose averages ~4 characters per token (Anthropic tokenizer).</li>
     *   <li>Input = fixed prompt template (~580 chars) + merchant + description (~55 chars)
     *       ≈ 635 chars → <strong>~160 input tokens</strong>.</li>
     *   <li>Output = JSON with category, confidence, one-sentence reasoning, and
     *       fraud-signal array → <strong>~60 output tokens</strong> (conservative upper bound).</li>
     * </ul>
     *
     * <p>Pricing (claude-sonnet-4-6, June 2026):
     * <pre>
     *   Input:   160 tokens × $3 / 1 000 000 = $0.000 480
     *   Output:   60 tokens × $15 / 1 000 000 = $0.000 900
     *   Total per uncached call              ≈ $0.001 380  (~$1.38 / 1 000 calls)
     * </pre>
     *
     * <p>Cache impact: with a 24-hour TTL and typical workloads, the same
     * {@code (merchant, description)} pair is submitted multiple times per day
     * (e.g., "Starbucks / Coffee latte"). Only the first submission in each 24-hour
     * window incurs a cost; all subsequent submissions are free.
     */
    @Test
    void estimatedCostPerUncachedCall_isBelowOneCent() {
        // Mirrors CATEGORIZATION_PROMPT in TransactionCategorizationClient.
        // Update this string if the prompt changes significantly.
        String fixedTemplate = """
                Categorize this financial transaction and identify any fraud risk signals.

                Respond with ONLY valid JSON, no other text:
                {
                  "category": "<FOOD|TRANSPORT|UTILITIES|ENTERTAINMENT|SHOPPING|HEALTHCARE|INCOME|TRANSFER|OTHER>",
                  "confidence": <0.0 to 1.0>,
                  "reasoning": "<one sentence>",
                  "fraudSignals": ["<specific suspicious pattern>"]
                }

                fraudSignals should be an empty array [] for normal transactions.
                Examples of fraud signals: "unusually high-value transfer",
                "unrecognized merchant pattern", "description inconsistent with merchant type",
                "potential duplicate transaction pattern".
                """;
        String fullPrompt = fixedTemplate + "\nMerchant: Starbucks\nDescription: Coffee latte";

        int inputTokens  = (int) Math.ceil(fullPrompt.length() / 4.0);
        int outputTokens = 60; // conservative: JSON body + one-sentence reasoning

        // claude-sonnet-4-6: $3/MTok input, $15/MTok output
        double inputCostUsd  = inputTokens  *  3.0 / 1_000_000;
        double outputCostUsd = outputTokens * 15.0 / 1_000_000;
        double totalCostUsd  = inputCostUsd + outputCostUsd;

        assertThat(totalCostUsd)
                .as("per-uncached-call cost (USD): %d input tokens + %d output tokens",
                        inputTokens, outputTokens)
                .isLessThan(0.01); // actual estimate is ~$0.0014 — assertion gives 7× headroom
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String response(String innerText) {
        String escaped = innerText.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        return """
                {
                  "id": "msg_test123",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "%s"}],
                  "model": "claude-sonnet-4-6",
                  "stop_reason": "end_turn"
                }
                """.formatted(escaped);
    }
}
