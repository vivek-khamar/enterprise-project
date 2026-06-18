package com.enterprise.demo.dto;

import com.enterprise.demo.entity.KycStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class KycVerificationDto {
    Long id;
    Long userId;
    KycStatus status;
    String documentType;
    List<String> inconsistencies;
    Double confidenceScore;
    String reviewNotes;
    Instant createdAt;
    Instant updatedAt;
}
