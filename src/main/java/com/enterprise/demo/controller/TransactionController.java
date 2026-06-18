package com.enterprise.demo.controller;

import com.enterprise.demo.dto.CreateTransactionRequest;
import com.enterprise.demo.dto.TransactionDto;
import com.enterprise.demo.entity.TransactionCategory;
import com.enterprise.demo.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Validated
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionDto> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createTransaction(request));
    }

    @GetMapping
    public ResponseEntity<Page<TransactionDto>> getOwnTransactions(
            @PageableDefault(size = 20, sort = "transactionDate",
                             direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getOwnTransactions(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDto> getTransaction(
            @PathVariable @Positive(message = "Transaction ID must be a positive number") Long id) {
        return ResponseEntity.ok(transactionService.getTransaction(id));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<TransactionCategory, BigDecimal>> getSpendingSummary() {
        return ResponseEntity.ok(transactionService.getSpendingSummary());
    }

    @GetMapping("/flagged")
    public ResponseEntity<Page<TransactionDto>> getFlaggedTransactions(
            @PageableDefault(size = 20, sort = "createdAt",
                             direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getFlaggedTransactions(pageable));
    }
}
