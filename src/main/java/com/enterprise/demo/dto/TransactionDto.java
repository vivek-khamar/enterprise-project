package com.enterprise.demo.dto;

import com.enterprise.demo.entity.FraudRisk;
import com.enterprise.demo.entity.TransactionCategory;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Value
@Builder
public class TransactionDto {
    Long id;
    Long userId;
    String merchant;
    String description;
    BigDecimal amount;
    String currency;
    LocalDate transactionDate;
    TransactionCategory category;
    Double categoryConfidence;
    String categoryReasoning;
    FraudRisk fraudRisk;
    List<String> fraudFlags;
    Instant createdAt;
}
