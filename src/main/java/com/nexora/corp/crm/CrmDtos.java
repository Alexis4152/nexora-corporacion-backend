package com.nexora.corp.crm;

import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CrmDtos {

    private CrmDtos() {
    }

    public record InterestDto(@NotNull Long productId, Long planId) {
    }

    public record ProspectRequest(
            @NotBlank(message = "Escribe el nombre del contacto") @Size(max = 150) String contactName,
            @NotBlank(message = "Escribe el nombre del negocio") @Size(max = 150) String businessName,
            @Pattern(regexp = "^$|^\\d{10}$", message = "10 dígitos") String phone,
            @Pattern(regexp = "^$|^\\d{10,13}$", message = "10 a 13 dígitos") String whatsapp,
            @Email(message = "Correo no válido") @Size(max = 150) String email,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^$|^[A-Z]{2}$") String country,
            @Size(max = 80) String industry,
            @NotBlank(message = "Elige la fuente") @Pattern(regexp = "^(WEBSITE|REFERRAL|SOCIAL|ADS|EVENT|COLD_CALL|WHATSAPP|PARTNER|OTHER)$") String source,
            Long ownerUserId,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal estimatedBudget,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal estimatedValue,
            @Min(0) @Max(100) Integer probability,
            Instant nextContactAt,
            @Size(max = 4000) String comments,
            @NotEmpty(message = "Elige al menos un producto de interés") List<@Valid InterestDto> interests) {
    }

    public record StageRequest(@NotNull ProspectStage stage,
                               @Pattern(regexp = "^(PRICE|COMPETITOR|NO_BUDGET|NO_RESPONSE|NOT_A_FIT|TIMING|FEATURES|OTHER)$") String lostReason,
                               @Size(max = 500) String lostDetail) {
    }

    public record ActivityRequest(
            @NotBlank @Pattern(regexp = "^(CALL|MESSAGE|EMAIL|MEETING|DEMO|NOTE|COMMITMENT)$") String type,
            @NotBlank(message = "Escribe el asunto") @Size(max = 200) String subject,
            @Size(max = 4000) String body,
            Instant scheduledAt,
            boolean completed,
            @Size(max = 300) String outcome) {
    }

    public record CompleteRequest(@Size(max = 300) String outcome) {
    }

    public record ProspectCard(Long id, String businessName, String contactName, String email, String phone, String city,
                               String source, ProspectStage stage, int probability, BigDecimal estimatedValue, Long ownerUserId,
                               String ownerName, Instant nextContactAt, Instant stageChangedAt, Instant createdAt,
                               List<String> products, Long convertedCustomerId, String lostReason, boolean stale) {
    }

    public record ActivityResponse(Long id, Long prospectId, Long customerId, String type, String subject, String body,
                                   Instant scheduledAt, Instant completedAt, String outcome, Long userId, String userName,
                                   Instant createdAt, String targetName) {
    }

    public record ProspectDetail(Long id, String contactName, String businessName, String phone, String whatsapp, String email,
                                 String city, String state, String country, String industry, String source, Long ownerUserId,
                                 String ownerName, BigDecimal estimatedBudget, BigDecimal estimatedValue, int probability,
                                 ProspectStage stage, Instant stageChangedAt, Instant nextContactAt, String comments,
                                 String lostReason, String lostDetail, Long convertedCustomerId, Instant convertedAt,
                                 List<InterestDto> interests, List<ActivityResponse> activities, Instant createdAt, int version,
                                 Long duplicateCustomerId, String duplicateCustomerName) {
    }

    public record BoardColumn(ProspectStage stage, long count, BigDecimal value, BigDecimal weightedValue, List<ProspectCard> cards) {
    }

    /** Conversión transaccional de un prospecto ganado en cliente + suscripción (+ primer pago opcional). */
    public record ConvertRequest(
            Long existingCustomerId,
            @Valid com.nexora.corp.customers.CustomerDtos.CustomerRequest customer,
            @NotNull Long productId,
            @NotNull Long planId,
            @NotNull BillingPeriod billingPeriod,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull LocalDate startDate,
            boolean trial,
            @Min(0) @Max(180) Integer trialDays,
            @Size(max = 30) String couponCode,
            @Valid FirstPayment firstPayment) {
    }

    public record FirstPayment(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                               @NotBlank String method, @Size(max = 80) String reference, LocalDate receivedOn) {
    }

    public record ConvertResponse(Long customerId, String customerCode, Long subscriptionId, String subscriptionCode, Long paymentId) {
    }
}

