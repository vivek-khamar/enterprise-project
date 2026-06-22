package com.enterprise.demo.client;

import com.enterprise.demo.config.AnthropicProperties;
import com.enterprise.demo.dto.CategorizationResult;
import com.enterprise.demo.entity.TransactionCategory;
import com.enterprise.demo.exception.TransactionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TransactionCategorizationClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Package-private so PromptIterationEvalTest can verify the deployed prompt meets V3 criteria.
    static final String CATEGORIZATION_PROMPT = """
            You are a financial transaction categorizer and fraud risk analyst for a banking application.

            Given a merchant name and transaction description, respond with ONLY valid JSON — no prose, no markdown:
            {
              "category": "FOOD|TRANSPORT|UTILITIES|ENTERTAINMENT|SHOPPING|HEALTHCARE|INCOME|TRANSFER|OTHER",
              "confidence": <0.00-1.00>,
              "reasoning": "<one sentence: state the category rationale and note any fraud concern>",
              "fraudSignals": ["<specific observed pattern>"]
            }

            Category definitions:
            FOOD          Restaurants, cafes, grocery stores, food delivery
            TRANSPORT     Rideshare, airlines, gas stations, parking, transit, car rentals
            UTILITIES     Electricity, water, gas, internet, mobile phone, cable
            ENTERTAINMENT Streaming, cinemas, gaming, concerts, sports venues
            SHOPPING      Retail, clothing, electronics, online marketplaces
            HEALTHCARE    Pharmacies, hospitals, clinics, dental, vision, insurance
            INCOME        Salary, payroll, freelance, government deposits, tax refunds
            TRANSFER      Bank-to-bank wires, ACH transfers, peer-to-peer payments
            OTHER         Does not clearly fit any category above

            Confidence calibration:
            0.95-1.00  Merchant name unambiguously matches the category (e.g. "Starbucks" -> FOOD)
            0.80-0.94  Category is clear from context; merchant is less well-known
            0.60-0.79  Ambiguous merchant or description — best-guess category
            < 0.60     Assign OTHER and explain the ambiguity in reasoning

            Fraud signal criteria — flag ONLY signals you observe in the data provided:
            - Generic or code-like merchant name  (e.g. "LLC-7842", "XYZ-Corp-99", random digits)
            - Description inconsistent with merchant (e.g. "industrial chemicals" from a cafe)
            - Unusual geography keywords  (e.g. "offshore", "international wire", "overseas")
            - Vague high-value transfer language  (e.g. "urgent transfer", no recipient detail)
            Return [] when no signals are present — do NOT invent or extrapolate.

            Examples:
            Merchant: Starbucks | Description: Two oat milk lattes and a muffin
            {"category":"FOOD","confidence":0.98,"reasoning":"Starbucks is a coffee chain; routine beverage purchase with no anomalies.","fraudSignals":[]}

            Merchant: Uber | Description: Airport pickup — JFK to Midtown Manhattan
            {"category":"TRANSPORT","confidence":0.97,"reasoning":"Uber rideshare; airport-to-city route is a standard transport transaction.","fraudSignals":[]}

            Merchant: Con Edison | Description: Monthly electricity bill account #4821
            {"category":"UTILITIES","confidence":0.99,"reasoning":"Con Edison is a US utility provider; recurring bill with account reference is normal.","fraudSignals":[]}

            Merchant: GLOBAL-WIRE-LLC-4471 | Description: Transfer funds international urgent
            {"category":"TRANSFER","confidence":0.72,"reasoning":"Wire to an unrecognizable coded merchant with vague urgent international wording raises multiple fraud concerns.","fraudSignals":["unrecognized merchant pattern","vague international transfer description","urgency language without recipient detail"]}

            Now analyze:
            """;

    private final RestClient restClient;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;

    public TransactionCategorizationClient(RestClient.Builder builder,
                                           AnthropicProperties properties,
                                           ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Cacheable(value = "transaction-categories",
               key = "#merchant.toLowerCase() + '|' + #description.toLowerCase()")
    public CategorizationResult categorize(String merchant, String description) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new TransactionException(
                    "Transaction analysis not available: API key not configured");
        }

        String prompt = CATEGORIZATION_PROMPT + "Merchant: " + merchant + " | Description: " + description;

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", prompt))
        );
        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),
                "max_tokens", 512,
                "messages", List.of(message)
        );

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
            throw new TransactionException(
                    "Transaction categorization service unavailable: " + e.getMessage(), e);
        }

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new TransactionException("Empty response from categorization service");
        }

        return parseResult(response.content().get(0).text());
    }

    private CategorizationResult parseResult(String rawText) {
        try {
            String json = rawText.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("^```(?:json)?\\s*", "")
                           .replaceFirst("```\\s*$", "")
                           .trim();
            }
            RawResult raw = objectMapper.readValue(json, RawResult.class);
            TransactionCategory category = parseCategory(raw.category());
            List<String> signals = raw.fraudSignals() != null ? raw.fraudSignals() : List.of();
            return new CategorizationResult(category, raw.confidence(), raw.reasoning(), signals);
        } catch (Exception e) {
            log.warn("Failed to parse categorization response: {}", e.getMessage());
            throw new TransactionException(
                    "Categorization service returned an unparseable response", e);
        }
    }

    private TransactionCategory parseCategory(String raw) {
        if (raw == null) return TransactionCategory.OTHER;
        try {
            return TransactionCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TransactionCategory.OTHER;
        }
    }

    private record AnthropicResponse(List<ContentBlock> content) {}
    private record ContentBlock(String type, String text) {}
    private record RawResult(String category, double confidence, String reasoning,
                             List<String> fraudSignals) {}
}
