package com.enterprise.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for User.  The {@code password} field is write-only:
 * it is accepted on POST /users but is never serialised in responses.
 * The 3-arg constructor (id, username, email) is kept so that all
 * existing service/test code compiles without changes.
 */
@Data
@NoArgsConstructor
public class UserDto {

    private Long id;

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /** Accepted on input, never returned in responses. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    /** Backward-compatible constructor used throughout the codebase and tests. */
    public UserDto(Long id, String username, String email) {
        this.id = id;
        this.username = username;
        this.email = email;
    }
}
