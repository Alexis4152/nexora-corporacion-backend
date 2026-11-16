package com.nexora.corp.catalog;

import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record ProductRequest(
            @NotBlank(message = "Escribe el código") @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,29}$", message = "Mayúsculas, números y guion bajo") String code,
            @NotBlank(message = "Escribe el nombre comercial") @Size(max = 100) String name,
            @Size(max = 150) String tagline,
            @Size(max = 2000) String description,
            @Size(max = 60) String category,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color #RRGGBB") String color,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,40}$", message = "Ícono no válido") String icon,
            @Pattern(regexp = "^$|^https?://[^\\s]{3,250}$", message = "URL no válida") String marketingUrl,
            @Pattern(regexp = "^$|^https?://[^\\s]{3,250}$", message = "URL no válida") String systemUrl,
            @Min(0) @Max(180) int defaultTrialDays,
            @Min(0) @Max(60) int defaultGraceDays,
            @NotBlank @Pattern(regexp = "^(AUTO|MANUAL)$") String renewalPolicy,
            @NotBlank @Pattern(regexp = "^(MANUAL|AUTO_AFTER_GRACE)$") String suspensionPolicy,
            Map<String, Object> allowedSettings,
            @Min(0) @Max(999) int sortOrder) {
    }

    public record PriceRequest(
            @NotNull BillingPeriod billingPeriod,
            @NotNull(message = "Escribe el precio") @DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
            @DecimalMax(value = "9999999.99") @Digits(integer = 10, fraction = 2, message = "Máximo 2 decimales") BigDecimal amount,
            Boolean taxIncluded) {
    }

    public record PlanRequest(
            @NotNull Long productId,
            @NotBlank(message = "Escribe el código") @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,29}$", message = "Mayúsculas, números y guion bajo") String code,
            @NotBlank(message = "Escribe el nombre") @Size(max = 80) String name,
            @Size(max = 1000) String description,
            @Size(max = 40) List<@NotBlank @Size(max = 160) String> features,
            Map<String, Object> usageLimits,
            @Min(1) Integer maxUsers,
            @Min(1) Integer maxBranches,
            @Min(0) @Max(180) Integer trialDays,
            boolean highlighted,
            boolean custom,
            Boolean active,
            LocalDate availableFrom,
            LocalDate availableUntil,
            @Min(0) @Max(999) int sortOrder,
            @Valid List<PriceRequest> prices) {
    }

    public record PriceResponse(Long id, BillingPeriod billingPeriod, BigDecimal amount, String currency, boolean taxIncluded,
                                Instant validFrom, Instant validTo) {
        static PriceResponse of(PlanPrice p) {
            return new PriceResponse(p.getId(), p.getBillingPeriod(), p.getAmount(), p.getCurrency(), p.isTaxIncluded(),
                    p.getValidFrom(), p.getValidTo());
        }
    }

    public record PlanResponse(Long id, Long productId, String code, String name, String description, List<String> features,
                               Map<String, Object> usageLimits, Integer maxUsers, Integer maxBranches, Integer trialDays,
                               boolean highlighted, boolean custom, boolean active, LocalDate availableFrom, LocalDate availableUntil,
                               int sortOrder, List<PriceResponse> prices, long activeSubscriptions, int version) {
    }

    public record ProductResponse(Long id, String code, String name, String tagline, String description, String category,
                                  String color, String icon, String marketingUrl, String systemUrl, int defaultTrialDays,
                                  int defaultGraceDays, String renewalPolicy, String suspensionPolicy,
                                  Map<String, Object> allowedSettings, boolean active, int sortOrder, int version,
                                  List<PlanResponse> plans, long activeSubscriptions, long trialSubscriptions) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }

    public record CouponRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{3,30}$", message = "3 a 30 caracteres: mayúsculas, números, guion") String code,
            @Size(max = 200) String description,
            @NotBlank @Pattern(regexp = "^(PERCENT|AMOUNT)$") String discountType,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
            @Min(1) @Max(120) Integer durationCycles,
            Long productId,
            LocalDate validFrom,
            LocalDate validUntil,
            @Min(1) Integer maxRedemptions) {
    }

    public record CouponResponse(Long id, String code, String description, String discountType, BigDecimal discountValue,
                                 Integer durationCycles, Long productId, LocalDate validFrom, LocalDate validUntil,
                                 Integer maxRedemptions, int redemptions, boolean active) {
        static CouponResponse of(Coupon c) {
            return new CouponResponse(c.getId(), c.getCode(), c.getDescription(), c.getDiscountType(), c.getDiscountValue(),
                    c.getDurationCycles(), c.getProductId(), c.getValidFrom(), c.getValidUntil(), c.getMaxRedemptions(),
                    c.getRedemptions(), c.isActive());
        }
    }
}

