package com.enterprise.demo.controller;

import com.enterprise.demo.dto.KycVerificationDto;
import com.enterprise.demo.entity.KycStatus;
import com.enterprise.demo.exception.KycException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.security.SecurityConfig;
import com.enterprise.demo.service.AuditService;
import com.enterprise.demo.service.KycService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KycController.class)
@Import(SecurityConfig.class)
class KycControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private KycService kycService;

    // ── POST /api/v1/kyc/submit ───────────────────────────────────────────────

    @Test
    void submitDocument_returns201_withVerificationDto() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "passport.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});

        when(kycService.submitDocument(any())).thenReturn(buildDto(1L, KycStatus.APPROVED, "PASSPORT", 0.95));

        mockMvc.perform(multipart("/api/v1/kyc/submit")
                        .file(file)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.documentType").value("PASSPORT"))
                .andExpect(jsonPath("$.confidenceScore").value(0.95));
    }

    @Test
    void submitDocument_returns422_forUnsupportedFileType() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "doc.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[]{1});

        when(kycService.submitDocument(any()))
                .thenThrow(new KycException("Unsupported document format. Accepted formats: JPEG, PNG, WebP, GIF"));

        mockMvc.perform(multipart("/api/v1/kyc/submit")
                        .file(pdf)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("KYC verification error"))
                .andExpect(jsonPath("$.details").value("Unsupported document format. Accepted formats: JPEG, PNG, WebP, GIF"));
    }

    @Test
    void submitDocument_returns401_whenUnauthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "id.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});

        mockMvc.perform(multipart("/api/v1/kyc/submit").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitDocument_returns201_forAdminUser() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "id.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});

        when(kycService.submitDocument(any())).thenReturn(buildDto(2L, KycStatus.IN_REVIEW, "NATIONAL_ID", 0.65));

        mockMvc.perform(multipart("/api/v1/kyc/submit")
                        .file(file)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    }

    // ── GET /api/v1/kyc/me ────────────────────────────────────────────────────

    @Test
    void getOwnStatus_returns200_forAuthenticatedUser() throws Exception {
        when(kycService.getOwnStatus()).thenReturn(buildDto(1L, KycStatus.APPROVED, "PASSPORT", 0.93));

        mockMvc.perform(get("/api/v1/kyc/me").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.documentType").value("PASSPORT"));
    }

    @Test
    void getOwnStatus_returns404_whenNoSubmission() throws Exception {
        when(kycService.getOwnStatus())
                .thenThrow(new ResourceNotFoundException("No KYC submission found for current user"));

        mockMvc.perform(get("/api/v1/kyc/me").with(user("jsmith").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getOwnStatus_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/kyc/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/kyc (admin list) ──────────────────────────────────────────

    @Test
    void listVerifications_returns200_forAdmin() throws Exception {
        List<KycVerificationDto> items = List.of(
                buildDto(1L, KycStatus.APPROVED, "PASSPORT", 0.95),
                buildDto(2L, KycStatus.IN_REVIEW, "NATIONAL_ID", 0.65));
        when(kycService.listVerifications(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(items));

        mockMvc.perform(get("/api/v1/kyc").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.content[1].status").value("IN_REVIEW"));
    }

    @Test
    void listVerifications_returns403_forRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/kyc").with(user("jsmith").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listVerifications_returnsFilteredResults_whenStatusParamProvided() throws Exception {
        List<KycVerificationDto> inReview = List.of(buildDto(3L, KycStatus.IN_REVIEW, "PASSPORT", 0.68));
        when(kycService.listVerifications(eq(KycStatus.IN_REVIEW), any(Pageable.class)))
                .thenReturn(new PageImpl<>(inReview));

        mockMvc.perform(get("/api/v1/kyc")
                        .param("status", "IN_REVIEW")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("IN_REVIEW"));
    }

    // ── GET /api/v1/kyc/{userId} ──────────────────────────────────────────────

    @Test
    void getStatusForUser_returns200_forAdmin() throws Exception {
        when(kycService.getStatusForUser(5L)).thenReturn(buildDto(10L, KycStatus.REJECTED, "NATIONAL_ID", 0.55));

        mockMvc.perform(get("/api/v1/kyc/5").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void getStatusForUser_returns403_forRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/kyc/5").with(user("jsmith").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStatusForUser_returns404_whenNotFound() throws Exception {
        when(kycService.getStatusForUser(99L))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/v1/kyc/99").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/v1/kyc/{userId}/review ──────────────────────────────────────

    @Test
    void reviewVerification_returns200_forAdmin() throws Exception {
        when(kycService.reviewVerification(eq(5L), any()))
                .thenReturn(buildDto(10L, KycStatus.APPROVED, "PASSPORT", 0.90));

        mockMvc.perform(put("/api/v1/kyc/5/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED","reviewNotes":"Verified by compliance team"}
                                """)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void reviewVerification_returns403_forRegularUser() throws Exception {
        mockMvc.perform(put("/api/v1/kyc/5/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED","reviewNotes":"OK"}
                                """)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewVerification_returns400_forMissingReviewNotes() throws Exception {
        mockMvc.perform(put("/api/v1/kyc/5/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED"}
                                """)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void reviewVerification_returns422_whenKycServiceThrows() throws Exception {
        when(kycService.reviewVerification(eq(5L), any()))
                .thenThrow(new KycException("Review status must be APPROVED or REJECTED"));

        mockMvc.perform(put("/api/v1/kyc/5/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PENDING","reviewNotes":"test"}
                                """)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("KYC verification error"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private KycVerificationDto buildDto(Long id, KycStatus status, String docType, double confidence) {
        return KycVerificationDto.builder()
                .id(id)
                .userId(1L)
                .status(status)
                .documentType(docType)
                .inconsistencies(List.of())
                .confidenceScore(confidence)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
