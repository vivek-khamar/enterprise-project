package com.enterprise.demo.controller;

import com.enterprise.demo.dto.TransactionDto;
import com.enterprise.demo.entity.FraudRisk;
import com.enterprise.demo.entity.TransactionCategory;
import com.enterprise.demo.exception.TransactionException;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.security.SecurityConfig;
import com.enterprise.demo.service.AuditService;
import com.enterprise.demo.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionControllerTest {

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
    @MockitoBean private TransactionService transactionService;

    // ── POST /api/v1/transactions ─────────────────────────────────────────────

    @Test
    void createTransaction_returns201_withTransactionDto() throws Exception {
        when(transactionService.createTransaction(any()))
                .thenReturn(buildDto(1L, TransactionCategory.FOOD, FraudRisk.LOW, List.of()));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"Starbucks","description":"Coffee latte",
                                 "amount":5.50,"currency":"USD","transactionDate":"2026-06-08"}
                                """)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.fraudRisk").value("LOW"));
    }

    @Test
    void createTransaction_returns422_whenServiceThrowsTransactionException() throws Exception {
        when(transactionService.createTransaction(any()))
                .thenThrow(new TransactionException("Transaction analysis not available: API key not configured"));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"Starbucks","description":"Coffee latte",
                                 "amount":5.50,"currency":"USD","transactionDate":"2026-06-08"}
                                """)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Transaction processing error"));
    }

    @Test
    void createTransaction_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchant":"Starbucks","description":"Coffee latte",
                                 "amount":5.50,"currency":"USD","transactionDate":"2026-06-08"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createTransaction_returns400_whenMerchantIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Coffee latte",
                                 "amount":5.50,"currency":"USD","transactionDate":"2026-06-08"}
                                """)
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // ── GET /api/v1/transactions ──────────────────────────────────────────────

    @Test
    void getOwnTransactions_returns200_withPage() throws Exception {
        List<TransactionDto> items = List.of(
                buildDto(1L, TransactionCategory.FOOD, FraudRisk.LOW, List.of()),
                buildDto(2L, TransactionCategory.TRANSPORT, FraudRisk.LOW, List.of()));
        when(transactionService.getOwnTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(items));

        mockMvc.perform(get("/api/v1/transactions")
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // ── GET /api/v1/transactions/{id} ─────────────────────────────────────────

    @Test
    void getTransaction_returns200_forAuthenticatedUser() throws Exception {
        when(transactionService.getTransaction(1L))
                .thenReturn(buildDto(1L, TransactionCategory.FOOD, FraudRisk.LOW, List.of()));

        mockMvc.perform(get("/api/v1/transactions/1")
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    // ── GET /api/v1/transactions/summary ─────────────────────────────────────

    @Test
    void getSpendingSummary_returns200_forAuthenticatedUser() throws Exception {
        when(transactionService.getSpendingSummary())
                .thenReturn(Map.of(
                        TransactionCategory.FOOD, new BigDecimal("17.50"),
                        TransactionCategory.TRANSPORT, new BigDecimal("30.00")));

        mockMvc.perform(get("/api/v1/transactions/summary")
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FOOD").value(17.50));
    }

    // ── GET /api/v1/transactions/flagged ─────────────────────────────────────

    @Test
    void getFlaggedTransactions_returns403_forRegularUser() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/flagged")
                        .with(user("jsmith").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getFlaggedTransactions_returns200_forAdmin() throws Exception {
        when(transactionService.getFlaggedTransactions(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        buildDto(1L, TransactionCategory.TRANSFER, FraudRisk.HIGH,
                                List.of("Amount exceeds $10,000 threshold")))));

        mockMvc.perform(get("/api/v1/transactions/flagged")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fraudRisk").value("HIGH"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TransactionDto buildDto(Long id, TransactionCategory category,
                                    FraudRisk risk, List<String> fraudFlags) {
        return TransactionDto.builder()
                .id(id)
                .userId(1L)
                .merchant("Starbucks")
                .description("Coffee latte")
                .amount(new BigDecimal("5.50"))
                .currency("USD")
                .transactionDate(LocalDate.of(2026, 6, 8))
                .category(category)
                .categoryConfidence(0.95)
                .categoryReasoning("Coffee shop purchase")
                .fraudRisk(risk)
                .fraudFlags(fraudFlags)
                .createdAt(Instant.now())
                .build();
    }
}
