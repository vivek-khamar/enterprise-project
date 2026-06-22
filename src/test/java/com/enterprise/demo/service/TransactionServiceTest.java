package com.enterprise.demo.service;

import com.enterprise.demo.client.TransactionCategorizationClient;
import com.enterprise.demo.dto.CategorizationResult;
import com.enterprise.demo.dto.CreateTransactionRequest;
import com.enterprise.demo.dto.TransactionDto;
import com.enterprise.demo.entity.FraudRisk;
import com.enterprise.demo.entity.Transaction;
import com.enterprise.demo.entity.TransactionCategory;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.exception.TransactionException;
import com.enterprise.demo.repository.TransactionRepository;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionCategorizationClient categorizationClient;

    @InjectMocks
    private TransactionService transactionService;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUpSecurityContext() {
        testUser = new User("jsmith", "j@example.com");
        testUser.setId(1L);
        otherUser = new User("other", "other@example.com");
        otherUser.setId(2L);

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

    // ── createTransaction ─────────────────────────────────────────────────────

    @Test
    void createTransaction_lowFraudRisk_whenCleanAiResult() {
        stubSave();
        when(categorizationClient.categorize(any(), any())).thenReturn(
                new CategorizationResult(TransactionCategory.FOOD, 0.95,
                        "Coffee shop purchase", List.of()));

        TransactionDto result = transactionService.createTransaction(request(new BigDecimal("5.50")));

        assertThat(result.getCategory()).isEqualTo(TransactionCategory.FOOD);
        assertThat(result.getCategoryConfidence()).isEqualTo(0.95);
        assertThat(result.getFraudRisk()).isEqualTo(FraudRisk.LOW);
        assertThat(result.getFraudFlags()).isEmpty();
    }

    @Test
    void createTransaction_highFraudRisk_whenAmountExceedsTenThousand() {
        stubSave();
        when(categorizationClient.categorize(any(), any())).thenReturn(
                new CategorizationResult(TransactionCategory.TRANSFER, 0.85,
                        "Wire transfer", List.of()));

        TransactionDto result = transactionService.createTransaction(request(new BigDecimal("15000.00")));

        assertThat(result.getFraudRisk()).isEqualTo(FraudRisk.HIGH);
        assertThat(result.getFraudFlags()).contains("Amount exceeds $10,000 threshold");
    }

    @Test
    void createTransaction_highFraudRisk_whenAiReturnsTwoOrMoreSignals() {
        stubSave();
        when(categorizationClient.categorize(any(), any())).thenReturn(
                new CategorizationResult(TransactionCategory.OTHER, 0.60,
                        "Suspicious activity", List.of(
                                "unusually high-value transfer",
                                "unrecognized merchant pattern")));

        TransactionDto result = transactionService.createTransaction(request(new BigDecimal("100.00")));

        assertThat(result.getFraudRisk()).isEqualTo(FraudRisk.HIGH);
        assertThat(result.getFraudFlags()).containsExactlyInAnyOrder(
                "unusually high-value transfer", "unrecognized merchant pattern");
    }

    @Test
    void createTransaction_mediumFraudRisk_whenAmountMediumAndOneAiSignal() {
        stubSave();
        when(categorizationClient.categorize(any(), any())).thenReturn(
                new CategorizationResult(TransactionCategory.SHOPPING, 0.75,
                        "Online purchase", List.of("unrecognized merchant pattern")));

        TransactionDto result = transactionService.createTransaction(request(new BigDecimal("6000.00")));

        // toAmountRisk(6000) = MEDIUM, toAiRisk(1) = MEDIUM, max = MEDIUM
        assertThat(result.getFraudRisk()).isEqualTo(FraudRisk.MEDIUM);
        assertThat(result.getFraudFlags()).contains(
                "unrecognized merchant pattern", "Amount exceeds $5,000 threshold");
    }

    @Test
    void createTransaction_fallsBackToOther_whenAiThrows() {
        stubSave();
        when(categorizationClient.categorize(any(), any()))
                .thenThrow(new TransactionException("API key not configured"));

        TransactionDto result = transactionService.createTransaction(request(new BigDecimal("50.00")));

        assertThat(result.getCategory()).isEqualTo(TransactionCategory.OTHER);
        assertThat(result.getFraudRisk()).isEqualTo(FraudRisk.LOW);
        assertThat(result.getFraudFlags()).containsExactly("AI analysis unavailable");
    }

    // ── getTransaction ────────────────────────────────────────────────────────

    @Test
    void getTransaction_returnsDto_whenOwnedByCurrentUser() {
        Transaction tx = buildTx(1L, testUser, new BigDecimal("20.00"), FraudRisk.LOW, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));

        TransactionDto result = transactionService.getTransaction(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(1L);
    }

    @Test
    void getTransaction_throwsResourceNotFoundException_whenOwnedByOtherUser() {
        Transaction tx = buildTx(1L, otherUser, new BigDecimal("20.00"), FraudRisk.LOW, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> transactionService.getTransaction(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    // ── getOwnTransactions ────────────────────────────────────────────────────

    @Test
    void getOwnTransactions_returnsPageForCurrentUser() {
        Pageable pageable = PageRequest.of(0, 20);
        List<Transaction> txList = List.of(
                buildTx(1L, testUser, new BigDecimal("5.50"), FraudRisk.LOW, null),
                buildTx(2L, testUser, new BigDecimal("200.00"), FraudRisk.LOW, null));
        when(transactionRepository.findByUserId(1L, pageable))
                .thenReturn(new PageImpl<>(txList));

        Page<TransactionDto> result = transactionService.getOwnTransactions(pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getMerchant()).isEqualTo("Starbucks");
    }

    // ── getSpendingSummary ────────────────────────────────────────────────────

    @Test
    void getSpendingSummary_returnsTotalPerCategory() {
        List<Transaction> txList = List.of(
                buildTx(1L, testUser, new BigDecimal("5.50"), FraudRisk.LOW, TransactionCategory.FOOD),
                buildTx(2L, testUser, new BigDecimal("12.00"), FraudRisk.LOW, TransactionCategory.FOOD),
                buildTx(3L, testUser, new BigDecimal("30.00"), FraudRisk.LOW, TransactionCategory.TRANSPORT));
        when(transactionRepository.findByUserIdAndCategoryNotNull(1L)).thenReturn(txList);

        Map<TransactionCategory, BigDecimal> result = transactionService.getSpendingSummary();

        assertThat(result.get(TransactionCategory.FOOD))
                .isEqualByComparingTo(new BigDecimal("17.50"));
        assertThat(result.get(TransactionCategory.TRANSPORT))
                .isEqualByComparingTo(new BigDecimal("30.00"));
    }

    // ── getFlaggedTransactions ────────────────────────────────────────────────

    @Test
    void getFlaggedTransactions_returnsMediumAndHighRiskOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        List<Transaction> flagged = List.of(
                buildTx(1L, testUser, new BigDecimal("15000"), FraudRisk.HIGH, TransactionCategory.TRANSFER),
                buildTx(2L, testUser, new BigDecimal("6000"), FraudRisk.MEDIUM, TransactionCategory.SHOPPING));
        when(transactionRepository.findByFraudRiskIn(
                List.of(FraudRisk.MEDIUM, FraudRisk.HIGH), pageable))
                .thenReturn(new PageImpl<>(flagged));

        Page<TransactionDto> result = transactionService.getFlaggedTransactions(pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getFraudRisk()).isEqualTo(FraudRisk.HIGH);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CreateTransactionRequest request(BigDecimal amount) {
        CreateTransactionRequest req = new CreateTransactionRequest();
        req.setMerchant("Starbucks");
        req.setDescription("Coffee latte");
        req.setAmount(amount);
        req.setCurrency("USD");
        req.setTransactionDate(LocalDate.of(2026, 6, 8));
        return req;
    }

    private void stubSave() {
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            tx.setId(1L);
            return tx;
        });
    }

    private Transaction buildTx(Long id, User user, BigDecimal amount,
                                 FraudRisk risk, TransactionCategory category) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setUser(user);
        tx.setMerchant("Starbucks");
        tx.setDescription("Coffee latte");
        tx.setAmount(amount);
        tx.setCurrency("USD");
        tx.setTransactionDate(LocalDate.of(2026, 6, 8));
        tx.setCategory(category);
        tx.setFraudRisk(risk);
        return tx;
    }
}
