package com.nexora.corp.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    @Query("SELECT a FROM PaymentAllocation a WHERE a.paymentId = :paymentId AND a.reversedAt IS NULL")
    List<PaymentAllocation> activeForPayment(Long paymentId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM PaymentAllocation a WHERE a.paymentId = :paymentId AND a.reversedAt IS NULL")
    BigDecimal allocatedFor(Long paymentId);
}

