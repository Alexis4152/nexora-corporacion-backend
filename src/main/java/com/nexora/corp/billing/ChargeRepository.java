package com.nexora.corp.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Charge c WHERE c.id = :id")
    Optional<Charge> lockById(Long id);

    /** Cargos abiertos del cliente, del más antiguo al más reciente (orden de aplicación automática). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Charge c WHERE c.customerId = :customerId AND c.status IN ('OPEN', 'PARTIAL') ORDER BY c.dueDate, c.id")
    List<Charge> lockOpenForCustomer(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Charge c WHERE c.customerId = :customerId AND c.subscriptionId = :subscriptionId "
            + "AND c.status IN ('OPEN', 'PARTIAL') ORDER BY c.dueDate, c.id")
    List<Charge> lockOpenForSubscription(Long customerId, Long subscriptionId);

    Optional<Charge> findByIdempotencyKey(String key);

    @Query("SELECT c FROM Charge c WHERE c.subscriptionId = :subscriptionId AND c.type = 'SUBSCRIPTION' AND c.status <> 'VOID' "
            + "AND c.periodStart = :periodStart")
    Optional<Charge> findPeriodCharge(Long subscriptionId, LocalDate periodStart);

    @Query("SELECT c FROM Charge c WHERE c.subscriptionId = :subscriptionId AND c.status IN ('OPEN', 'PARTIAL') ORDER BY c.dueDate")
    List<Charge> openForSubscription(Long subscriptionId);
}

