package com.enterprise.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileDto {

    private Long id;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private Instant uploadedAt;
    private String presignedUrl;
}
