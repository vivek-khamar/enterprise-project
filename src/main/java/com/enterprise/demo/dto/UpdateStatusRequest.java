package com.enterprise.demo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UpdateStatusRequest {

    @NotNull(message = "enabled is required")
    private Boolean enabled;
}
