package com.enterprise.demo.dto;

import com.enterprise.demo.entity.KycStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KycReviewRequest {

    @NotNull(message = "Status is required")
    private KycStatus status;

    @NotBlank(message = "Review notes are required")
    @Size(max = 1000, message = "Review notes must not exceed 1000 characters")
    private String reviewNotes;
}
