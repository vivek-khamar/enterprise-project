package com.enterprise.demo.client;

import com.enterprise.demo.config.AnthropicProperties;
import com.enterprise.demo.dto.AiAnalysisResult;
import com.enterprise.demo.exception.KycException;
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

class KycAiClientTest {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_URL = BASE_URL + "/v1/messages";
    private static final byte[] SAMPLE_IMAGE = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    private MockRestServiceServer mockServer;
    private KycAiClient client;
    private AnthropicProperties properties;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        properties = new AnthropicProperties();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl(BASE_URL);
        properties.setModel("claude-sonnet-4-6");

        client = new KycAiClient(builder, properties, new ObjectMapper());
    }

    @Test
    void analyzeDocument_returnsResult_whenApiRespondsWithValidJson() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-api-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess(anthropicResponse("""
                        {"documentType":"PASSPORT","extractedFields":{"name":"John Smith","dob":"1985-03-15"},"inconsistencies":[],"confidenceScore":0.95}
                        """), MediaType.APPLICATION_JSON));

        AiAnalysisResult result = client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg");

        assertThat(result.documentType()).isEqualTo("PASSPORT");
        assertThat(result.confidenceScore()).isEqualTo(0.95);
        assertThat(result.inconsistencies()).isEmpty();
        assertThat(result.extractedFields()).containsEntry("name", "John Smith");
        mockServer.verify();
    }

    @Test
    void analyzeDocument_returnsResult_withInconsistencies() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(anthropicResponse("""
                        {"documentType":"DRIVERS_LICENSE","extractedFields":{"name":"Jane Doe"},"inconsistencies":["Document appears expired","Poor image quality"],"confidenceScore":0.62}
                        """), MediaType.APPLICATION_JSON));

        AiAnalysisResult result = client.analyzeDocument(SAMPLE_IMAGE, "image/png");

        assertThat(result.documentType()).isEqualTo("DRIVERS_LICENSE");
        assertThat(result.confidenceScore()).isEqualTo(0.62);
        assertThat(result.inconsistencies()).containsExactly("Document appears expired", "Poor image quality");
        mockServer.verify();
    }

    @Test
    void analyzeDocument_stripsMarkdownCodeFences_beforeParsing() {
        String responseWithFences = """
                ```json
                {"documentType":"NATIONAL_ID","extractedFields":{},"inconsistencies":[],"confidenceScore":0.88}
                ```
                """;
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(anthropicResponse(responseWithFences), MediaType.APPLICATION_JSON));

        AiAnalysisResult result = client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg");

        assertThat(result.documentType()).isEqualTo("NATIONAL_ID");
        assertThat(result.confidenceScore()).isEqualTo(0.88);
        mockServer.verify();
    }

    @Test
    void analyzeDocument_throwsKycException_whenApiKeyIsBlank() {
        properties.setApiKey("   ");

        assertThatThrownBy(() -> client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg"))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("API key not configured");
    }

    @Test
    void analyzeDocument_throwsKycException_whenApiKeyIsNull() {
        properties.setApiKey(null);

        assertThatThrownBy(() -> client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg"))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("API key not configured");
    }

    @Test
    void analyzeDocument_throwsKycException_whenApiCallFails() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg"))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("KYC analysis service unavailable");
        mockServer.verify();
    }

    @Test
    void analyzeDocument_throwsKycException_whenResponseTextIsNotValidJson() {
        mockServer.expect(requestTo(MESSAGES_URL))
                .andRespond(withSuccess(anthropicResponse("I cannot analyze this image."),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyzeDocument(SAMPLE_IMAGE, "image/jpeg"))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("unparseable response");
        mockServer.verify();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Wraps an AI text response in the Anthropic Messages API envelope. */
    private String anthropicResponse(String innerText) {
        // Escape the inner text for embedding in JSON: replace backslash then quotes
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
