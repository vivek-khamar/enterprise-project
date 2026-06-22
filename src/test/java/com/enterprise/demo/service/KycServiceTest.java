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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock private KycVerificationRepository kycRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private KycAiClient kycAiClient;

    @InjectMocks
    private KycService kycService;

    private User testUser;

    @BeforeEach
    void setUpSecurityContext() {
        testUser = new User("jsmith", "j@example.com");
        testUser.setId(1L);

        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn("jsmith");
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        lenient().when(userRepository.findByUsername("jsmith")).thenReturn(Optional.of(testUser));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── submitDocument ────────────────────────────────────────────────────────

    @Test
    void submitDocument_returnsApproved_whenHighConfidenceAndNoIssues() throws Exception {
        MockMultipartFile file = imageFile("passport.jpg");
        stubFileUpload(42L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenReturn(
                new AiAnalysisResult("PASSPORT", Map.of("name", "John"), List.of(), 0.92));

        KycVerificationDto result = kycService.submitDocument(file);

        assertThat(result.getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(result.getDocumentType()).isEqualTo("PASSPORT");
        assertThat(result.getConfidenceScore()).isEqualTo(0.92);
    }

    @Test
    void submitDocument_returnsInReview_whenConfidenceBelowThreshold() throws Exception {
        MockMultipartFile file = imageFile("id.jpg");
        stubFileUpload(1L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenReturn(
                new AiAnalysisResult("NATIONAL_ID", Map.of(), List.of(), 0.70));

        KycVerificationDto result = kycService.submitDocument(file);

        assertThat(result.getStatus()).isEqualTo(KycStatus.IN_REVIEW);
    }

    @Test
    void submitDocument_returnsInReview_whenInconsistenciesFound() throws Exception {
        MockMultipartFile file = imageFile("doc.jpg");
        stubFileUpload(1L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenReturn(
                new AiAnalysisResult("PASSPORT", Map.of(), List.of("Document appears expired"), 0.90));

        KycVerificationDto result = kycService.submitDocument(file);

        assertThat(result.getStatus()).isEqualTo(KycStatus.IN_REVIEW);
        assertThat(result.getInconsistencies()).containsExactly("Document appears expired");
    }

    @Test
    void submitDocument_returnsFailed_whenAiClientThrows() throws Exception {
        MockMultipartFile file = imageFile("doc.jpg");
        stubFileUpload(1L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenThrow(new KycException("API key not configured"));

        KycVerificationDto result = kycService.submitDocument(file);

        assertThat(result.getStatus()).isEqualTo(KycStatus.FAILED);
    }

    @Test
    void submitDocument_throwsKycException_forUnsupportedFileType() {
        MultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> kycService.submitDocument(pdf))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("Unsupported document format");

        verify(fileStorageService, never()).uploadFile(any());
        verify(kycAiClient, never()).analyzeDocument(any(), any());
    }

    @Test
    void submitDocument_throwsKycException_forEmptyFile() {
        MultipartFile empty = new MockMultipartFile("file", "doc.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> kycService.submitDocument(empty))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("required");
    }

    @Test
    void submitDocument_uploadsFileToS3_beforeCallingAi() throws Exception {
        // Valid PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngMagic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        var file = new MockMultipartFile("file", "id.png", "image/png", pngMagic);
        stubFileUpload(10L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenReturn(
                new AiAnalysisResult("NATIONAL_ID", Map.of(), List.of(), 0.91));

        kycService.submitDocument(file);

        ArgumentCaptor<KycVerification> captor = ArgumentCaptor.forClass(KycVerification.class);
        verify(kycRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getFileMetadataId()).isEqualTo(10L);
    }

    @Test
    void submitDocument_acceptsAllSupportedImageTypes() throws Exception {
        stubFileUpload(1L);
        stubSave();
        when(kycAiClient.analyzeDocument(any(), any())).thenReturn(
                new AiAnalysisResult("PASSPORT", Map.of(), List.of(), 0.90));

        // Each entry: [mimeType, validMagicBytes]
        List<Object[]> cases = List.of(
            new Object[]{"image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}},
            new Object[]{"image/png",  new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}},
            new Object[]{"image/webp", new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50}}
        );

        for (Object[] c : cases) {
            var file = new MockMultipartFile("file", "doc", (String) c[0], (byte[]) c[1]);
            KycVerificationDto result = kycService.submitDocument(file);
            assertThat(result.getStatus()).isNotNull();
        }
    }

    // ── getOwnStatus ──────────────────────────────────────────────────────────

    @Test
    void getOwnStatus_returnsLatestVerificationForCurrentUser() {
        KycVerification v = buildVerification(KycStatus.APPROVED, "PASSPORT", 0.95);
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(v));

        KycVerificationDto result = kycService.getOwnStatus();

        assertThat(result.getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(result.getDocumentType()).isEqualTo("PASSPORT");
        assertThat(result.getConfidenceScore()).isEqualTo(0.95);
        assertThat(result.getUserId()).isEqualTo(1L);
    }

    @Test
    void getOwnStatus_throwsResourceNotFound_whenNoSubmission() {
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getOwnStatus())
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No KYC submission");
    }

    // ── getStatusForUser ──────────────────────────────────────────────────────

    @Test
    void getStatusForUser_returnsVerification_forAdmin() {
        when(userRepository.existsById(2L)).thenReturn(true);
        KycVerification v = buildVerification(KycStatus.IN_REVIEW, "DRIVERS_LICENSE", 0.72);
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.of(v));

        KycVerificationDto result = kycService.getStatusForUser(2L);

        assertThat(result.getStatus()).isEqualTo(KycStatus.IN_REVIEW);
        assertThat(result.getDocumentType()).isEqualTo("DRIVERS_LICENSE");
    }

    @Test
    void getStatusForUser_throwsResourceNotFound_whenUserNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> kycService.getStatusForUser(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getStatusForUser_throwsResourceNotFound_whenNoSubmissionExists() {
        when(userRepository.existsById(2L)).thenReturn(true);
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getStatusForUser(2L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No KYC submission");
    }

    // ── listVerifications ─────────────────────────────────────────────────────

    @Test
    void listVerifications_returnsAll_whenNoStatusFilter() {
        Pageable pageable = PageRequest.of(0, 20);
        List<KycVerification> verifications = List.of(
                buildVerification(KycStatus.APPROVED, "PASSPORT", 0.95),
                buildVerification(KycStatus.IN_REVIEW, "NATIONAL_ID", 0.65));
        when(kycRepository.findAll(pageable)).thenReturn(new PageImpl<>(verifications));

        Page<KycVerificationDto> result = kycService.listVerifications(null, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(result.getContent().get(1).getStatus()).isEqualTo(KycStatus.IN_REVIEW);
    }

    @Test
    void listVerifications_filtersByStatus_whenStatusProvided() {
        Pageable pageable = PageRequest.of(0, 20);
        List<KycVerification> inReview = List.of(buildVerification(KycStatus.IN_REVIEW, "PASSPORT", 0.70));
        when(kycRepository.findByStatus(KycStatus.IN_REVIEW, pageable)).thenReturn(new PageImpl<>(inReview));

        Page<KycVerificationDto> result = kycService.listVerifications(KycStatus.IN_REVIEW, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(KycStatus.IN_REVIEW);
        verify(kycRepository, never()).findAll(any(Pageable.class));
    }

    // ── reviewVerification ────────────────────────────────────────────────────

    @Test
    void reviewVerification_approvesWithNotes() {
        KycVerification v = buildVerification(KycStatus.IN_REVIEW, "PASSPORT", 0.70);
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(v));
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycReviewRequest req = new KycReviewRequest();
        req.setStatus(KycStatus.APPROVED);
        req.setReviewNotes("Verified manually by compliance team");

        KycVerificationDto result = kycService.reviewVerification(1L, req);

        assertThat(result.getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(result.getReviewNotes()).isEqualTo("Verified manually by compliance team");
    }

    @Test
    void reviewVerification_rejectsWithNotes() {
        KycVerification v = buildVerification(KycStatus.IN_REVIEW, "NATIONAL_ID", 0.50);
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(v));
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KycReviewRequest req = new KycReviewRequest();
        req.setStatus(KycStatus.REJECTED);
        req.setReviewNotes("Document appears to be forged");

        KycVerificationDto result = kycService.reviewVerification(1L, req);

        assertThat(result.getStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(result.getReviewNotes()).isEqualTo("Document appears to be forged");
    }

    @Test
    void reviewVerification_throwsKycException_forPendingStatus() {
        KycReviewRequest req = new KycReviewRequest();
        req.setStatus(KycStatus.PENDING);
        req.setReviewNotes("some notes");

        assertThatThrownBy(() -> kycService.reviewVerification(1L, req))
                .isInstanceOf(KycException.class)
                .hasMessageContaining("APPROVED or REJECTED");
    }

    @Test
    void reviewVerification_throwsResourceNotFound_whenNoSubmission() {
        when(kycRepository.findTopByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        KycReviewRequest req = new KycReviewRequest();
        req.setStatus(KycStatus.APPROVED);
        req.setReviewNotes("Verified");

        assertThatThrownBy(() -> kycService.reviewVerification(1L, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockMultipartFile imageFile(String filename) {
        // Valid JPEG magic bytes: FF D8 FF E0 (minimum 4 bytes required by validateMagicBytes)
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        return new MockMultipartFile("file", filename, "image/jpeg", jpeg);
    }

    private void stubFileUpload(Long fileId) {
        FileDto dto = new FileDto(fileId, "doc.jpg", "image/jpeg", 1024L, Instant.now(), null);
        when(fileStorageService.uploadFile(any())).thenReturn(dto);
    }

    private void stubSave() {
        when(kycRepository.save(any())).thenAnswer(inv -> {
            KycVerification v = inv.getArgument(0);
            if (v.getId() == null) v.setId(1L);
            return v;
        });
    }

    private KycVerification buildVerification(KycStatus status, String docType, double confidence) {
        KycVerification v = new KycVerification();
        v.setId(1L);
        v.setUser(testUser);
        v.setFileMetadataId(42L);
        v.setStatus(status);
        v.setDocumentType(docType);
        v.setConfidenceScore(confidence);
        return v;
    }
}
