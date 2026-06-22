package com.enterprise.demo.controller;

import com.enterprise.demo.dto.KycReviewRequest;
import com.enterprise.demo.dto.KycVerificationDto;
import com.enterprise.demo.entity.KycStatus;
import com.enterprise.demo.service.KycService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
@Validated
public class KycController {

    private final KycService kycService;

    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KycVerificationDto> submitDocument(
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(kycService.submitDocument(file));
    }

    @GetMapping("/me")
    public ResponseEntity<KycVerificationDto> getOwnStatus() {
        return ResponseEntity.ok(kycService.getOwnStatus());
    }

    @GetMapping
    public ResponseEntity<Page<KycVerificationDto>> listVerifications(
            @RequestParam(required = false) KycStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(kycService.listVerifications(status, pageable));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<KycVerificationDto> getStatusForUser(
            @PathVariable @Positive(message = "User ID must be a positive number") Long userId) {
        return ResponseEntity.ok(kycService.getStatusForUser(userId));
    }

    @PutMapping("/{userId}/review")
    public ResponseEntity<KycVerificationDto> reviewVerification(
            @PathVariable @Positive(message = "User ID must be a positive number") Long userId,
            @Valid @RequestBody KycReviewRequest request) {
        return ResponseEntity.ok(kycService.reviewVerification(userId, request));
    }
}
