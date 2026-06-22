package com.enterprise.demo.repository;

import com.enterprise.demo.entity.FraudRisk;
import com.enterprise.demo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findByUserId(Long userId, Pageable pageable);

    Page<Transaction> findByFraudRiskIn(Collection<FraudRisk> risks, Pageable pageable);

    List<Transaction> findByUserIdAndCategoryNotNull(Long userId);
}
