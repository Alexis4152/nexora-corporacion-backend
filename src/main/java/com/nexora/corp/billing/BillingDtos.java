package com.nexora.corp.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record AllocationInput(@NotNull Long chargeId,
                                  @NotNull @DecimalMin(value = "0.01", message = "Importe mayor a cero") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    public record PaymentRequest(
            @NotNull(message = "Elige el cliente") Long customerId,
            @NotNull(message = "Escribe el importe") @DecimalMin(value = "0.01", message = "El importe debe ser mayor a cero")
            @Digits(integer = 10, fraction = 2, message = "Máximo 2 decimales") BigDecimal amount,
            @NotNull(message = "Indica la fecha de pago") LocalDate receivedOn,
            @NotBlank @Pattern(regexp = "^(TRANSFER|SPEI|CASH|CARD|DEPOSIT|GATEWAY|OTHER)$", message = "Método no válido") String method,
            @Size(max = 80) @Pattern(regexp = "^$|^[A-Za-z0-9 ._/#-]{1,80}$", message = "Referencia con caracteres no válidos") String reference,
            @Size(max = 1000) String notes,
            /** AUTO: aplica a los cargos más antiguos; MANUAL: usa allocations; NONE: queda como saldo a favor. */
            @NotBlank @Pattern(regexp = "^(AUTO|MANUAL|NONE)$") String allocationMode,
            Long subscriptionId,
            List<@Valid AllocationInput> allocations,
            boolean confirmNow) {
    }

    public record ApplyRequest(@NotNull List<@Valid AllocationInput> allocations) {
    }

    public record ReasonRequest(@NotBlank(message = "Escribe el motivo") @Size(max = 300) String reason) {
    }

    public record RefundRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                @NotNull LocalDate refundedOn,
                                @NotBlank @Pattern(regexp = "^(TRANSFER|SPEI|CASH|CARD|DEPOSIT|GATEWAY|OTHER)$") String method,
                                @Size(max = 80) String reference,
                                @NotBlank(message = "Escribe el motivo") @Size(max = 300) String reason) {
    }

    public record ChargeRequest(@NotNull Long customerId, Long subscriptionId,
                                @NotBlank @Pattern(regexp = "^(SETUP|SERVICE|OTHER)$") String type,
                                @NotBlank(message = "Describe el cargo") @Size(max = 300) String description,
                                @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                Boolean taxIncluded,
                                @NotNull LocalDate dueDate) {
    }

    public record AdjustmentRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                    @NotBlank(message = "Escribe el motivo") @Size(max = 300) String reason) {
    }

    public record ChargeRow(Long id, String folio, Long customerId, String customerName, Long subscriptionId, String subscriptionCode,
                            String productCode, String productColor, String type, String description, LocalDate periodStart,
                            LocalDate periodEnd, LocalDate issueDate, LocalDate dueDate, BigDecimal total, BigDecimal paidAmount,
                            BigDecimal adjustedAmount, BigDecimal balance, String status, int daysOverdue, String currency) {
    }

    public record AllocationRow(Long id, Long paymentId, String paymentFolio, Long chargeId, String chargeFolio, String chargeDescription,
                                BigDecimal amount, Instant createdAt, Instant reversedAt, String reversalReason) {
    }

    public record AdjustmentRow(Long id, BigDecimal amount, String reason, String userName, Instant createdAt) {
    }

    public record ChargeDetail(ChargeRow charge, BigDecimal amount, BigDecimal discount, BigDecimal subtotal, BigDecimal tax,
                               List<AllocationRow> allocations, List<AdjustmentRow> adjustments, String voidReason, Instant voidedAt) {
    }

    public record PaymentRow(Long id, String folio, Long customerId, String customerName, String customerCode, LocalDate receivedOn,
                             BigDecimal amount, BigDecimal allocated, BigDecimal refunded, BigDecimal unapplied, String currency,
                             String method, String reference, String status, String registeredBy, Instant createdAt) {
    }

    public record RefundRow(Long id, BigDecimal amount, LocalDate refundedOn, String method, String reference, String reason,
                            String userName, Instant createdAt) {
    }

    public record PaymentDetail(PaymentRow payment, String notes, List<AllocationRow> allocations, List<RefundRow> refunds,
                                Instant confirmedAt, String confirmedBy, Instant reconciledAt, Instant rejectedAt, String rejectReason,
                                Instant reversedAt, String reversalReason) {
    }

    public record CollectionActionRequest(@NotNull Long customerId, Long subscriptionId,
                                          @NotBlank @Pattern(regexp = "^(CALL|EMAIL|WHATSAPP|VISIT|PROMISE|NOTE)$") String type,
                                          @NotBlank(message = "Describe la gestión") @Size(max = 2000) String notes,
                                          LocalDate promiseDate,
                                          @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal promiseAmount,
                                          LocalDate nextActionOn, Long responsibleUserId) {
    }

    public record CollectionActionRow(Long id, Long customerId, String customerName, Long subscriptionId, String type, String notes,
                                      LocalDate promiseDate, BigDecimal promiseAmount, LocalDate nextActionOn, Long responsibleUserId,
                                      String responsibleName, String createdByName, Instant createdAt) {
    }

    public record AgingBucket(String key, String label, int fromDays, Integer toDays, BigDecimal amount, long charges, long customers) {
    }

    public record ReceivableRow(Long customerId, String customerCode, String customerName, String email, String phone,
                                List<String> products, BigDecimal openAmount, BigDecimal overdueAmount, LocalDate oldestDue,
                                int daysOverdue, int overduePeriods, LocalDate lastPaymentOn, BigDecimal lastPaymentAmount,
                                Instant lastActionAt, String lastActionType, LocalDate nextActionOn, String responsibleName,
                                Long responsibleUserId, String bucket, BigDecimal credit) {
    }
}

