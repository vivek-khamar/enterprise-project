package com.enterprise.demo.client;

import com.enterprise.demo.config.AnthropicProperties;
import com.enterprise.demo.dto.CategorizationResult;
import com.enterprise.demo.entity.TransactionCategory;
import com.enterprise.demo.exception.TransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransactionCategorizationClientTest {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_URL = BASE_URL + "/v1/messages";

    private MockRestServiceServer mockServer;
    private TransactionCategorizationClient client;
    private AnthropicProperties properties;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        properties = new AnthropicProperties();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl(BASE_URL);
        properties.setModel("claude-sonnet-4-6");

        client = new TransactionCategorizationClient(builder, properties, new ObjectMapper());
    }

    @Test
    void categorize_returnsResult_whenApiRespondsWithValidJson() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-api-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess(anthropicResponse("""
                        {"category":"FOOD","confidence":0.95,"reasoning":"Coffee shop purchase","fraudSignals":[]}
                        """), MediaType.APPLICATION_JSON));

        CategorizationResult result = client.categorize("Starbucks", "Coffee latte");

        assertThat(result.category()).isEqualTo(TransactionCategory.FOOD);
        assertThat(result.confidence()).isEqualTo(0.95);
        assertThat(result.reasoning()).isEqualTo("Coffee shop purchase");
        assertThat(result.fraudSignals()).isEmpty();
        mockServer.verify();
    }

    @Test
    void categorize_returnsFraudSignals_whenTransactionIsSuspicious() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(anthropicResponse("""
                        {"category":"TRANSFER","confidence":0.80,"reasoning":"Large wire transfer","fraudSignals":["unusually high-value transfer","unrecognized merchant pattern"]}
                        """), MediaType.APPLICATION_JSON));

        CategorizationResult result = client.categorize("WireXfer LLC", "International wire transfer 50000");

        assertThat(result.category()).isEqualTo(TransactionCategory.TRANSFER);
        assertThat(result.fraudSignals()).containsExactly(
                "unusually high-value transfer", "unrecognized merchant pattern");
        mockServer.verify();
    }

    @Test
    void categorize_stripsMarkdownCodeFences_beforeParsing() {
        String responseWithFences = """
                ```json
                {"category":"TRANSPORT","confidence":0.88,"reasoning":"Rideshare service","fraudSignals":[]}
                ```
                """;
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(anthropicResponse(responseWithFences), MediaType.APPLICATION_JSON));

        CategorizationResult result = client.categorize("Uber", "Ride to airport");

        assertThat(result.category()).isEqualTo(TransactionCategory.TRANSPORT);
        assertThat(result.confidence()).isEqualTo(0.88);
        mockServer.verify();
    }

    @Test
    void categorize_throwsTransactionException_whenApiKeyIsBlank() {
        properties.setApiKey("   ");

        assertThatThrownBy(() -> client.categorize("Starbucks", "Coffee"))
                .isInstanceOf(TransactionException.class)
                .hasMessageContaining("API key not configured");
    }

    @Test
    void categorize_throwsTransactionException_whenApiKeyIsNull() {
        properties.setApiKey(null);

        assertThatThrownBy(() -> client.categorize("Starbucks", "Coffee"))
                .isInstanceOf(TransactionException.class)
                .hasMessageContaining("API key not configured");
    }

    @Test
    void categorize_throwsTransactionException_whenApiCallFails() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.categorize("Starbucks", "Coffee"))
                .isInstanceOf(TransactionException.class)
                .hasMessageContaining("categorization service unavailable");
        mockServer.verify();
    }

    @Test
    void categorize_throwsTransactionException_whenResponseTextIsNotValidJson() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(
                        anthropicResponse("I cannot categorize this transaction."),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.categorize("Starbucks", "Coffee"))
                .isInstanceOf(TransactionException.class)
                .hasMessageContaining("unparseable response");
        mockServer.verify();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String anthropicResponse(String innerText) {
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
