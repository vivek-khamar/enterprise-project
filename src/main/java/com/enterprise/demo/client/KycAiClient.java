package com.enterprise.demo.client;

import com.enterprise.demo.config.AnthropicProperties;
import com.enterprise.demo.dto.AiAnalysisResult;
import com.enterprise.demo.exception.KycException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KycAiClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final String ANALYSIS_PROMPT = """
            Analyze this identity document image.
            Return ONLY valid JSON with no other text, using this exact structure:
            {
              "documentType": "<PASSPORT|DRIVERS_LICENSE|NATIONAL_ID|OTHER>",
              "extractedFields": {
                "name": "<full name or null>",
                "dob": "<date of birth YYYY-MM-DD or null>",
                "documentNumber": "<document number or null>",
                "expiryDate": "<expiry date YYYY-MM-DD or null>"
              },
              "inconsistencies": ["<description of each issue found>"],
              "confidenceScore": <0.0 to 1.0>
            }
            Flag as inconsistencies: expired document, suspected digital alteration,
            poor image quality, mismatched fonts, blurry or cropped fields.
            If the image is not an identity document, set confidenceScore to 0.0.
            """;

    private final RestClient restClient;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;

    public KycAiClient(RestClient.Builder builder,
                       AnthropicProperties properties,
                       ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiAnalysisResult analyzeDocument(byte[] documentBytes, String mediaType) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank() ) {
            throw new KycException("KYC AI analysis not available: API key not configured");
        }

        String base64Data = Base64.getEncoder().encodeToString(documentBytes);
        Map<String, Object> requestBody = buildRequestBody(base64Data, mediaType);

        AnthropicResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", properties.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(AnthropicResponse.class);
        } catch (Exception e) {
            log.error("Anthropic API call failed: {}", e.getMessage());
            throw new KycException("KYC analysis service unavailable: " + e.getMessage(), e);
        }

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new KycException("Empty response from KYC analysis service");
        }

        return parseAnalysisResult(response.content().get(0).text());
    }

    private Map<String, Object> buildRequestBody(String base64Data, String mediaType) {
        Map<String, Object> imageSource = Map.of(
                "type", "base64",
                "media_type", mediaType,
                "data", base64Data
        );
        Map<String, Object> imageContent = Map.of("type", "image", "source", imageSource);
        Map<String, Object> textContent = Map.of("type", "text", "text", ANALYSIS_PROMPT);

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(imageContent, textContent)
        );

        return Map.of(
                "model", properties.getModel(),
                "max_tokens", 1024,
                "messages", List.of(message)
        );
    }

    private AiAnalysisResult parseAnalysisResult(String rawText) {
        try {
            String json = rawText.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("^```(?:json)?\\s*", "")
                           .replaceFirst("```\\s*$", "")
                           .trim();
            }
            return objectMapper.readValue(json, AiAnalysisResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse KYC AI response: {}", e.getMessage());
            throw new KycException("KYC analysis returned an unparseable response", e);
        }
    }

    private record AnthropicResponse(List<ContentBlock> content) {}
    private record ContentBlock(String type, String text) {}
}
