package com.enterprise.demo.dto;

import com.enterprise.demo.entity.TransactionCategory;

import java.util.List;

public record CategorizationResult(
        TransactionCategory category,
        double confidence,
        String reasoning,
        List<String> fraudSignals) {

    public static CategorizationResult unknown() {
        return new CategorizationResult(TransactionCategory.OTHER, 0.0, "Analysis unavailable", List.of());
    }
}
