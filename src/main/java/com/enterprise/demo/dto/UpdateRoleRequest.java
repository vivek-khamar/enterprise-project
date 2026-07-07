package com.enterprise.demo.dto;

import com.enterprise.demo.security.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UpdateRoleRequest {

    @NotNull(message = "role is required")
    private Role role;
}
