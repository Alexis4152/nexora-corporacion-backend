package com.nexora.corp.subscriptions;

import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "subscriptions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_price_id")
    private Long planPriceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false)
    private BillingPeriod billingPeriod;

    @Column(name = "list_price", nullable = false)
    private BigDecimal listPrice;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    @Column(name = "tax_included")
    private boolean taxIncluded;

    @Column(name = "discount_type", nullable = false)
    @Builder.Default
    private String discountType = "NONE";

    @Column(name = "discount_value", nullable = false)
    @Builder.Default
    private BigDecimal discountValue = BigDecimal.ZERO;

    @Column(name = "discount_cycles_remaining")
    private Integer discountCyclesRemaining;

    @Column(name = "coupon_id")
    private Long couponId;

    /** Ejecutivo que cerró la venta; base de los indicadores por vendedor. */
    @Column(name = "seller_user_id")
    private Long sellerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "trial_ends_on")
    private LocalDate trialEndsOn;

    @Column(name = "billing_anchor_day", nullable = false)
    private int billingAnchorDay;

    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    @Column(name = "next_charge_on")
    private LocalDate nextChargeOn;

    @Column(name = "auto_renew")
    private boolean autoRenew;

    @Column(name = "grace_days")
    private int graceDays;

    @Column(name = "preferred_payment_method")
    private String preferredPaymentMethod;

    @Column(name = "cancel_at_period_end")
    private boolean cancelAtPeriodEnd;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancelled_on")
    private LocalDate cancelledOn;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "cancellation_detail")
    private String cancellationDetail;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "pending_plan_id")
    private Long pendingPlanId;

    @Column(name = "pending_plan_price_id")
    private Long pendingPlanPriceId;

    @Column(name = "pending_price")
    private BigDecimal pendingPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_billing_period")
    private BillingPeriod pendingBillingPeriod;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal mrr = BigDecimal.ZERO;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    private int version;

    public boolean hasPendingChange() {
        return pendingPlanId != null;
    }

    public boolean discountActive() {
        return !"NONE".equals(discountType) && (discountCyclesRemaining == null || discountCyclesRemaining > 0);
    }
}

