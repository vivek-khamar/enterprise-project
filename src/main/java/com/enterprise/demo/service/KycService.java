package com.enterprise.demo.service;

import com.enterprise.demo.client.KycAiClient;
import com.enterprise.demo.dto.AiAnalysisResult;
import com.enterprise.demo.dto.FileDto;
import com.enterprise.demo.dto.KycReviewRequest;
import com.enterprise.demo.dto.KycVerificationDto;
import com.enterprise.demo.entity.KycStatus;
import com.enterprise.demo.entity.KycVerification;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.KycException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.KycVerificationRepository;
import com.enterprise.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PNG  = "image/png";
    private static final String MIME_WEBP = "image/webp";

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(MIME_JPEG, MIME_PNG, MIME_WEBP);

    private static final long MAX_KYC_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB

    private final KycVerificationRepository kycRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final KycAiClient kycAiClient;

    @Transactional
    public KycVerificationDto submitDocument(MultipartFile file) {
        validateFile(file);

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new KycException("Failed to read document file", e);
        }

        FileDto fileDto = fileStorageService.uploadFile(file);

        KycVerification verification = new KycVerification();
        verification.setUser(user);
        verification.setFileMetadataId(fileDto.getId());
        verification.setStatus(KycStatus.PENDING);
        kycRepository.save(verification);

        try {
            AiAnalysisResult result = kycAiClient.analyzeDocument(fileBytes, resolveMediaType(file));
            applyResult(verification, result);
        } catch (Exception e) {
            log.error("KYC AI analysis failed for user={}: {}", username, e.getMessage());
            verification.setStatus(KycStatus.FAILED);
        }

        return toDto(kycRepository.save(verification));
    }

    public KycVerificationDto getOwnStatus() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        KycVerification verification = kycRepository
                .findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No KYC submission found for current user"));

        return toDto(verification);
    }

    public KycVerificationDto getStatusForUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        KycVerification verification = kycRepository
                .findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No KYC submission found for user id: " + userId));

        return toDto(verification);
    }

    public Page<KycVerificationDto> listVerifications(KycStatus status, Pageable pageable) {
        Page<KycVerification> page = (status != null)
                ? kycRepository.findByStatus(status, pageable)
                : kycRepository.findAll(pageable);
        return page.map(this::toDto);
    }

    @Transactional
    public KycVerificationDto reviewVerification(Long userId, KycReviewRequest request) {
        if (request.getStatus() != KycStatus.APPROVED && request.getStatus() != KycStatus.REJECTED) {
            throw new KycException("Review status must be APPROVED or REJECTED");
        }

        KycVerification verification = kycRepository
                .findTopByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No KYC submission found for user id: " + userId));

        verification.setStatus(request.getStatus());
        verification.setReviewNotes(request.getReviewNotes());

        return toDto(kycRepository.save(verification));
    }

    private void applyResult(KycVerification v, AiAnalysisResult result) {
        v.setDocumentType(result.documentType());
        v.setConfidenceScore(result.confidenceScore());

        if (result.extractedFields() != null && !result.extractedFields().isEmpty()) {
            v.setExtractedData(
                    result.extractedFields().entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .collect(Collectors.joining("\n"))
            );
        }

        if (result.inconsistencies() != null && !result.inconsistencies().isEmpty()) {
            v.setInconsistencies(String.join("\n", result.inconsistencies()));
        }

        boolean noIssues = result.inconsistencies() == null || result.inconsistencies().isEmpty();
        boolean highConfidence = result.confidenceScore() >= 0.85;
        v.setStatus(noIssues && highConfidence ? KycStatus.APPROVED : KycStatus.IN_REVIEW);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new KycException("Document file is required");
        }
        if (file.getSize() > MAX_KYC_FILE_SIZE_BYTES) {
            throw new KycException("Document file must not exceed 5 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new KycException("Unsupported document format. Accepted formats: JPEG, PNG, WebP");
        }
        validateMagicBytes(file, contentType.toLowerCase());
    }

    // Verifies file content matches its declared MIME type to prevent MIME-confusion attacks.
    private void validateMagicBytes(MultipartFile file, String declaredType) {
        byte[] header = new byte[12];
        try (InputStream in = file.getInputStream()) {
            int read = in.read(header, 0, 12);
            if (read < 4) {
                throw new KycException("Document file is too small to be a valid image");
            }
        } catch (IOException e) {
            throw new KycException("Failed to read document file", e);
        }

        boolean valid = switch (declaredType) {
            case MIME_JPEG -> header[0] == (byte) 0xFF
                           && header[1] == (byte) 0xD8
                           && header[2] == (byte) 0xFF;
            case MIME_PNG  -> header[0] == (byte) 0x89
                           && header[1] == 0x50
                           && header[2] == 0x4E
                           && header[3] == 0x47;
            // RIFF....WEBP (12-byte WebP signature)
            case MIME_WEBP -> header[0] == 0x52 && header[1] == 0x49
                           && header[2] == 0x46 && header[3] == 0x46
                           && header[8] == 0x57 && header[9]  == 0x45
                           && header[10] == 0x42 && header[11] == 0x50;
            default -> false;
        };

        if (!valid) {
            throw new KycException("Document file content does not match the declared format");
        }
    }

    private String resolveMediaType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct != null) ? ct.toLowerCase() : MIME_JPEG;
    }

    private KycVerificationDto toDto(KycVerification v) {
        List<String> inconsistencies = null;
        if (v.getInconsistencies() != null && !v.getInconsistencies().isBlank()) {
            inconsistencies = List.of(v.getInconsistencies().split("\n"));
        }
        return KycVerificationDto.builder()
                .id(v.getId())
                .userId(v.getUser().getId())
                .status(v.getStatus())
                .documentType(v.getDocumentType())
                .inconsistencies(inconsistencies)
                .confidenceScore(v.getConfidenceScore())
                .reviewNotes(v.getReviewNotes())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}
