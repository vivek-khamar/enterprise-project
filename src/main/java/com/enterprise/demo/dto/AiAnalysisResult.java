package com.enterprise.demo.dto;

import java.util.List;
import java.util.Map;

public record AiAnalysisResult(
        String documentType,
        Map<String, String> extractedFields,
        List<String> inconsistencies,
        double confidenceScore
) {
    public static AiAnalysisResult failed() {
        return new AiAnalysisResult(null, Map.of(), List.of(), 0.0);
    }
}
