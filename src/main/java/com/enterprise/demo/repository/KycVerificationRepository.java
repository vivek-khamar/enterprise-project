package com.enterprise.demo.repository;

import com.enterprise.demo.entity.KycStatus;
import com.enterprise.demo.entity.KycVerification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KycVerificationRepository extends JpaRepository<KycVerification, Long> {

    Optional<KycVerification> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    Page<KycVerification> findByStatus(KycStatus status, Pageable pageable);
}
