package com.nexora.corp.catalog;

import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanPriceRepository extends JpaRepository<PlanPrice, Long> {

    @Query("SELECT p FROM PlanPrice p WHERE p.planId = :planId AND p.validTo IS NULL ORDER BY p.billingPeriod")
    List<PlanPrice> findCurrent(Long planId);

    @Query("SELECT p FROM PlanPrice p WHERE p.planId IN :planIds AND p.validTo IS NULL")
    List<PlanPrice> findCurrentForPlans(Collection<Long> planIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlanPrice p WHERE p.planId = :planId AND p.billingPeriod = :period AND p.validTo IS NULL")
    Optional<PlanPrice> findCurrentForUpdate(Long planId, BillingPeriod period);

    @Query("SELECT p FROM PlanPrice p WHERE p.planId = :planId AND p.billingPeriod = :period AND p.validTo IS NULL")
    Optional<PlanPrice> findCurrent(Long planId, BillingPeriod period);

    List<PlanPrice> findByPlanIdOrderByValidFromDesc(Long planId);
}

