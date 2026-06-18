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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final String USER_NOT_FOUND = "User not found: ";
    private static final BigDecimal HIGH_RISK_THRESHOLD = new BigDecimal("10000");
    private static final BigDecimal MEDIUM_RISK_THRESHOLD = new BigDecimal("5000");

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final TransactionCategorizationClient categorizationClient;

    @Transactional
    public TransactionDto createTransaction(CreateTransactionRequest request) {
        String username = currentUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND + username));

        boolean aiSucceeded = true;
        CategorizationResult result;
        try {
            result = categorizationClient.categorize(request.getMerchant(), request.getDescription());
        } catch (TransactionException e) {
            log.warn("Transaction AI analysis failed for user={}: {}", username, e.getMessage());
            result = CategorizationResult.unknown();
            aiSucceeded = false;
        }

        FraudRisk fraudRisk;
        List<String> fraudFlags;
        if (!aiSucceeded) {
            fraudRisk = FraudRisk.LOW;
            fraudFlags = List.of("AI analysis unavailable");
        } else {
            fraudRisk = assessFraud(request.getAmount(), result);
            fraudFlags = buildFraudFlags(request.getAmount(), result);
        }

        Transaction tx = new Transaction();
        tx.setUser(user);
        tx.setMerchant(request.getMerchant());
        tx.setDescription(request.getDescription());
        tx.setAmount(request.getAmount());
        tx.setCurrency(request.getCurrency());
        tx.setTransactionDate(request.getTransactionDate());
        tx.setCategory(result.category());
        tx.setCategoryConfidence(result.confidence());
        tx.setCategoryReasoning(result.reasoning());
        tx.setFraudRisk(fraudRisk);
        tx.setFraudFlags(String.join("\n", fraudFlags));

        Transaction saved = transactionRepository.save(tx);
        return toDto(saved);
    }

    public Page<TransactionDto> getOwnTransactions(Pageable pageable) {
        User user = currentUser();
        return transactionRepository.findByUserId(user.getId(), pageable).map(this::toDto);
    }

    public TransactionDto getTransaction(Long id) {
        User user = currentUser();

        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Transaction not found with id: " + id));

        if (!tx.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Transaction not found with id: " + id);
        }
        return toDto(tx);
    }

    public Map<TransactionCategory, BigDecimal> getSpendingSummary() {
        User user = currentUser();
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndCategoryNotNull(user.getId());

        return transactions.stream()
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount,
                                BigDecimal::add)));
    }

    public Page<TransactionDto> getFlaggedTransactions(Pageable pageable) {
        return transactionRepository
                .findByFraudRiskIn(List.of(FraudRisk.MEDIUM, FraudRisk.HIGH), pageable)
                .map(this::toDto);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResourceNotFoundException(USER_NOT_FOUND + "(no authentication)");
        }
        return auth.getName();
    }

    private User currentUser() {
        String username = currentUsername();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND + username));
    }

    private FraudRisk assessFraud(BigDecimal amount, CategorizationResult result) {
        FraudRisk aiRisk = toAiRisk(result.fraudSignals().size());
        FraudRisk amtRisk = toAmountRisk(amount);
        return max(aiRisk, amtRisk);
    }

    private FraudRisk toAiRisk(int signalCount) {
        if (signalCount == 0) return FraudRisk.LOW;
        if (signalCount == 1) return FraudRisk.MEDIUM;
        return FraudRisk.HIGH;
    }

    private FraudRisk toAmountRisk(BigDecimal amount) {
        if (amount.compareTo(HIGH_RISK_THRESHOLD) > 0) return FraudRisk.HIGH;
        if (amount.compareTo(MEDIUM_RISK_THRESHOLD) > 0) return FraudRisk.MEDIUM;
        return FraudRisk.LOW;
    }

    private List<String> buildFraudFlags(BigDecimal amount, CategorizationResult result) {
        List<String> flags = new ArrayList<>(result.fraudSignals());
        if (amount.compareTo(HIGH_RISK_THRESHOLD) > 0) {
            flags.add("Amount exceeds $10,000 threshold");
        } else if (amount.compareTo(MEDIUM_RISK_THRESHOLD) > 0) {
            flags.add("Amount exceeds $5,000 threshold");
        }
        return flags;
    }

    private FraudRisk max(FraudRisk a, FraudRisk b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private TransactionDto toDto(Transaction tx) {
        List<String> flags = (tx.getFraudFlags() == null || tx.getFraudFlags().isBlank())
                ? List.of()
                : Arrays.asList(tx.getFraudFlags().split("\n"));
        return TransactionDto.builder()
                .id(tx.getId())
                .userId(tx.getUser().getId())
                .merchant(tx.getMerchant())
                .description(tx.getDescription())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .transactionDate(tx.getTransactionDate())
                .category(tx.getCategory())
                .categoryConfidence(tx.getCategoryConfidence())
                .categoryReasoning(tx.getCategoryReasoning())
                .fraudRisk(tx.getFraudRisk())
                .fraudFlags(flags)
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
