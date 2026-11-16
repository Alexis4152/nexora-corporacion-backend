package com.nexora.corp.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Cargo interno (lo que el cliente debe). No es un CFDI: la factura fiscal será un documento aparte ligado a él. */
@Entity
@Table(name = "charges")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String folio;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "subscription_id", updatable = false)
    private Long subscriptionId;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(name = "plan_id", updatable = false)
    private Long planId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false)
    private String description;

    @Column(name = "period_start", updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", updatable = false)
    private LocalDate periodEnd;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private BigDecimal discount;

    @Column(nullable = false, updatable = false)
    private BigDecimal subtotal;

    @Column(nullable = false, updatable = false)
    private BigDecimal tax;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "paid_amount", nullable = false)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "adjusted_amount", nullable = false)
    @Builder.Default
    private BigDecimal adjustedAmount = BigDecimal.ZERO;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Version
    private int version;

    public BigDecimal balance() {
        return total.subtract(paidAmount).subtract(adjustedAmount);
    }

    public boolean open() {
        return "OPEN".equals(status) || "PARTIAL".equals(status);
    }

    /** Estado derivado de los acumulados; se llama tras cada movimiento. */
    public void refreshStatus() {
        if ("VOID".equals(status)) {
            return;
        }
        if (balance().signum() == 0) {
            status = "PAID";
        } else if (paidAmount.signum() > 0 || adjustedAmount.signum() > 0) {
            status = "PARTIAL";
        } else {
            status = "OPEN";
        }
    }
}

