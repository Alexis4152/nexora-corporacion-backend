package com.nexora.corp.subscriptions;

import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record CreateRequest(
            @NotNull(message = "Elige el cliente") Long customerId,
            @NotNull(message = "Elige el producto") Long productId,
            @NotNull(message = "Elige el plan") Long planId,
            @NotNull(message = "Elige la periodicidad") BillingPeriod billingPeriod,
            @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull(message = "Indica la fecha de inicio") LocalDate startDate,
            boolean trial,
            @Min(1) @Max(180) Integer trialDays,
            Boolean autoRenew,
            @Min(0) @Max(60) Integer graceDays,
            @Pattern(regexp = "^$|^(TRANSFER|SPEI|CASH|CARD|DEPOSIT|GATEWAY|OTHER)$") String preferredPaymentMethod,
            @Pattern(regexp = "^(NONE|PERCENT|AMOUNT)$") String discountType,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
            @Min(1) @Max(120) Integer discountCycles,
            @Size(max = 30) String couponCode,
            @Size(max = 2000) String notes,
            boolean allowDuplicate,
            Long sellerUserId) {
    }

    public record SellerRequest(@NotNull(message = "Elige el vendedor") Long sellerUserId, @Size(max = 300) String reason) {
    }

    public record ChangePlanRequest(@NotNull Long planId, @NotNull BillingPeriod billingPeriod,
                                    @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
                                    @NotBlank @Pattern(regexp = "^(IMMEDIATE|END_OF_PERIOD)$") String effective) {
    }

    public record CancelRequest(@NotBlank @Pattern(regexp = "^(IMMEDIATE|END_OF_PERIOD)$") String mode,
                                @NotBlank(message = "Elige el motivo") @Pattern(regexp = "^(PRICE|MISSING_FEATURES|COMPETITOR|CLOSED_BUSINESS|NOT_USING|SERVICE_ISSUES|NON_PAYMENT|OTHER)$") String reason,
                                @Size(max = 500) String detail,
                                boolean waiveOpenBalance) {
    }

    public record ReasonRequest(@NotBlank(message = "Escribe el motivo") @Size(max = 300) String reason) {
    }

    public record DiscountRequest(@NotBlank @Pattern(regexp = "^(NONE|PERCENT|AMOUNT)$") String type,
                                  @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal value,
                                  @Min(1) @Max(120) Integer cycles,
                                  @Size(max = 300) String reason) {
    }

    public record TermsRequest(boolean autoRenew, @Min(0) @Max(60) int graceDays,
                               @Pattern(regexp = "^$|^(TRANSFER|SPEI|CASH|CARD|DEPOSIT|GATEWAY|OTHER)$") String preferredPaymentMethod,
                               @Size(max = 2000) String notes) {
    }

    public record SubscriptionRow(Long id, String code, Long customerId, String customerCode, String customerName, Long productId,
                                  String productCode, String productName, String productColor, Long planId, String planName,
                                  BillingPeriod billingPeriod, BigDecimal price, BigDecimal mrr, String currency, String status,
                                  String collectionStatus, LocalDate startDate, LocalDate trialEndsOn, LocalDate currentPeriodStart,
                                  LocalDate currentPeriodEnd, LocalDate nextChargeOn, BigDecimal overdueAmount, int daysOverdue,
                                  boolean autoRenew, boolean cancelAtPeriodEnd, String discountType, BigDecimal discountValue,
                                  Long sellerUserId, String sellerName) {
    }

    public record EventRow(Long id, String type, String fromStatus, String toStatus, LocalDate effectiveOn, BigDecimal mrrBefore,
                           BigDecimal mrrAfter, String movement, Map<String, Object> data, String userName, Instant createdAt) {
    }

    public record SubscriptionDetail(SubscriptionRow row, BigDecimal listPrice, boolean taxIncluded, Integer discountCyclesRemaining,
                                     String couponCode, int graceDays, String preferredPaymentMethod, String cancellationReason,
                                     String cancellationDetail, LocalDate cancelledOn, Instant suspendedAt, Long pendingPlanId,
                                     String pendingPlanName, BigDecimal pendingPrice, BillingPeriod pendingBillingPeriod, String notes,
                                     BigDecimal openAmount, List<EventRow> events, int version, List<String> allowedActions) {
    }
}

