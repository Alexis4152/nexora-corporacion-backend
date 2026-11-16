package com.nexora.corp.subscriptions;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.id = :id")
    Optional<Subscription> lockById(Long id);

    @Query("SELECT s.id FROM Subscription s WHERE s.status = 'PENDING_ACTIVATION' AND s.startDate <= :today")
    List<Long> dueForActivation(LocalDate today);

    @Query("SELECT s.id FROM Subscription s WHERE s.status = 'TRIAL' AND s.trialEndsOn < :today")
    List<Long> expiredTrials(LocalDate today);

    @Query("SELECT s.id FROM Subscription s WHERE s.status IN :statuses AND s.currentPeriodEnd < :today")
    List<Long> periodEnded(Collection<SubscriptionStatus> statuses, LocalDate today);

    @Query("SELECT s.id FROM Subscription s WHERE s.status IN ('ACTIVE', 'GRACE') AND s.autoRenew = true "
            + "AND s.cancelAtPeriodEnd = false AND s.currentPeriodEnd <= :limit")
    List<Long> needingNextCharge(LocalDate limit);

    @Query("SELECT s.id FROM Subscription s WHERE s.status IN ('ACTIVE', 'GRACE')")
    List<Long> inGoodStanding();

    @Query("SELECT COALESCE(SUM(s.mrr), 0) FROM Subscription s WHERE s.customerId = :customerId AND s.id <> :excludeId")
    BigDecimal otherMrr(Long customerId, Long excludeId);

    long countByCustomerIdAndProductIdAndStatusIn(Long customerId, Long productId, Collection<SubscriptionStatus> statuses);

    List<Subscription> findByCustomerId(Long customerId);
}

