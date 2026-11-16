package com.nexora.corp.catalog;

import com.nexora.corp.catalog.CatalogDtos.CouponRequest;
import com.nexora.corp.catalog.CatalogDtos.CouponResponse;
import com.nexora.corp.catalog.CatalogDtos.PlanRequest;
import com.nexora.corp.catalog.CatalogDtos.PlanResponse;
import com.nexora.corp.catalog.CatalogDtos.PriceRequest;
import com.nexora.corp.catalog.CatalogDtos.PriceResponse;
import com.nexora.corp.catalog.CatalogDtos.ProductRequest;
import com.nexora.corp.catalog.CatalogDtos.ProductResponse;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.audit.Changes;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catálogo dinámico de productos, planes, precios y cupones. Los precios se versionan: modificar un precio cierra el
 * vigente y crea uno nuevo; las suscripciones existentes conservan su precio pactado.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final ProductRepository products;
    private final PlanRepository plans;
    private final PlanPriceRepository prices;
    private final CouponRepository coupons;
    private final SettingsService settings;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ProductResponse> list(boolean includeInactive) {
        List<Product> all = products.findAllByOrderBySortOrderAscNameAsc().stream().filter(p -> includeInactive || p.isActive()).toList();
        List<Plan> allPlans = plans.findAllByOrderByProductIdAscSortOrderAsc();
        Map<Long, List<PlanPrice>> pricesByPlan = prices.findCurrentForPlans(allPlans.stream().map(Plan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlanPrice::getPlanId));
        Map<String, Long> counts = counts();
        return all.stream().map(p -> toResponse(p, allPlans.stream().filter(pl -> pl.getProductId().equals(p.getId())).toList(),
                pricesByPlan, counts)).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        Product p = product(id);
        List<Plan> productPlans = plans.findByProductIdOrderBySortOrderAscNameAsc(id);
        Map<Long, List<PlanPrice>> pricesByPlan = prices.findCurrentForPlans(productPlans.stream().map(Plan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlanPrice::getPlanId));
        return toResponse(p, productPlans, pricesByPlan, counts());
    }

    @Transactional(readOnly = true)
    public ProductResponse getByCode(String code) {
        return get(products.findByCode(code).orElseThrow(() -> new NotFoundException("Producto no encontrado")).getId());
    }

    private Map<String, Long> counts() {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("""
                SELECT 'p' || product_id || ':' || CASE WHEN status = 'TRIAL' THEN 'T' ELSE 'A' END, COUNT(*) FROM subscriptions
                WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED', 'TRIAL') GROUP BY 1
                UNION ALL
                SELECT 'plan' || plan_id, COUNT(*) FROM subscriptions WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED', 'TRIAL')
                GROUP BY plan_id""", rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    private ProductResponse toResponse(Product p, List<Plan> productPlans, Map<Long, List<PlanPrice>> pricesByPlan, Map<String, Long> counts) {
        List<PlanResponse> planResponses = productPlans.stream().map(pl -> planResponse(pl, pricesByPlan.getOrDefault(pl.getId(), List.of()),
                counts.getOrDefault("plan" + pl.getId(), 0L))).toList();
        return new ProductResponse(p.getId(), p.getCode(), p.getName(), p.getTagline(), p.getDescription(), p.getCategory(), p.getColor(),
                p.getIcon(), p.getMarketingUrl(), p.getSystemUrl(), p.getDefaultTrialDays(), p.getDefaultGraceDays(), p.getRenewalPolicy(),
                p.getSuspensionPolicy(), p.getAllowedSettings(), p.isActive(), p.getSortOrder(), p.getVersion(), planResponses,
                counts.getOrDefault("p" + p.getId() + ":A", 0L), counts.getOrDefault("p" + p.getId() + ":T", 0L));
    }

    private static PlanResponse planResponse(Plan pl, List<PlanPrice> current, long subs) {
        return new PlanResponse(pl.getId(), pl.getProductId(), pl.getCode(), pl.getName(), pl.getDescription(), pl.getFeatures(),
                pl.getUsageLimits(), pl.getMaxUsers(), pl.getMaxBranches(), pl.getTrialDays(), pl.isHighlighted(), pl.isCustom(),
                pl.isActive(), pl.getAvailableFrom(), pl.getAvailableUntil(), pl.getSortOrder(),
                current.stream().sorted((a, b) -> a.getBillingPeriod().compareTo(b.getBillingPeriod())).map(PriceResponse::of).toList(),
                subs, pl.getVersion());
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest r) {
        if (products.existsByCode(r.code())) {
            throw new ConflictException("Ya existe un producto con ese código");
        }
        Product p = products.save(apply(new Product(), r));
        audit.record(AuditEntry.success("products", "PRODUCT_CREATED", "Product", p.getId()).after(Map.of("code", p.getCode(), "name", p.getName())).build());
        return get(p.getId());
    }

    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest r) {
        Product p = product(id);
        if (!p.getCode().equals(r.code())) {
            throw new BusinessException("El código de un producto no se cambia (lo usan suscripciones, reportes e integraciones)");
        }
        Changes ch = new Changes().track("name", p.getName(), r.name()).track("color", p.getColor(), r.color())
                .track("defaultTrialDays", p.getDefaultTrialDays(), r.defaultTrialDays())
                .track("defaultGraceDays", p.getDefaultGraceDays(), r.defaultGraceDays())
                .track("renewalPolicy", p.getRenewalPolicy(), r.renewalPolicy())
                .track("suspensionPolicy", p.getSuspensionPolicy(), r.suspensionPolicy())
                .track("systemUrl", p.getSystemUrl(), blank(r.systemUrl()));
        apply(p, r);
        if (!ch.isEmpty()) {
            audit.record(AuditEntry.success("products", "PRODUCT_UPDATED", "Product", id).before(ch.before()).after(ch.after()).build());
        }
        return get(id);
    }

    @Transactional
    public ProductResponse setProductActive(Long id, boolean active) {
        Product p = product(id);
        p.setActive(active);
        audit.record(AuditEntry.success("products", active ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", "Product", id).build());
        return get(id);
    }

    private Product apply(Product p, ProductRequest r) {
        p.setCode(r.code());
        p.setName(r.name().trim());
        p.setTagline(blank(r.tagline()));
        p.setDescription(blank(r.description()));
        p.setCategory(blank(r.category()));
        p.setColor(r.color().toLowerCase());
        p.setIcon(r.icon());
        p.setMarketingUrl(blank(r.marketingUrl()));
        p.setSystemUrl(blank(r.systemUrl()));
        p.setDefaultTrialDays(r.defaultTrialDays());
        p.setDefaultGraceDays(r.defaultGraceDays());
        p.setRenewalPolicy(r.renewalPolicy());
        p.setSuspensionPolicy(r.suspensionPolicy());
        p.setAllowedSettings(r.allowedSettings() == null ? new HashMap<>() : new HashMap<>(r.allowedSettings()));
        p.setSortOrder(r.sortOrder());
        return p;
    }

    // ── Planes ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public PlanResponse createPlan(PlanRequest r) {
        product(r.productId());
        if (plans.existsByProductIdAndCode(r.productId(), r.code())) {
            throw new ConflictException("Ya existe un plan con ese código en este producto");
        }
        validateDates(r);
        Plan plan = plans.save(applyPlan(new Plan(), r));
        List<PriceRequest> requested = r.prices() == null ? List.of() : r.prices();
        if (requested.stream().map(PriceRequest::billingPeriod).distinct().count() != requested.size()) {
            throw new BusinessException("Cada periodicidad solo puede tener un precio");
        }
        requested.forEach(pr -> insertPrice(plan.getId(), pr));
        audit.record(AuditEntry.success("products", "PLAN_CREATED", "Plan", plan.getId())
                .after(Map.of("code", plan.getCode(), "prices", requested.stream().map(p -> p.billingPeriod() + "=" + p.amount()).toList())).build());
        return planResponse(plan, prices.findCurrent(plan.getId()), 0);
    }

    @Transactional
    public PlanResponse updatePlan(Long id, PlanRequest r) {
        Plan plan = plan(id);
        if (!plan.getProductId().equals(r.productId()) || !plan.getCode().equals(r.code())) {
            throw new BusinessException("El producto y el código de un plan no se cambian");
        }
        validateDates(r);
        Changes ch = new Changes().track("name", plan.getName(), r.name()).track("features", plan.getFeatures(), r.features())
                .track("maxUsers", plan.getMaxUsers(), r.maxUsers()).track("maxBranches", plan.getMaxBranches(), r.maxBranches())
                .track("trialDays", plan.getTrialDays(), r.trialDays()).track("active", plan.isActive(), r.active() == null || r.active());
        applyPlan(plan, r);
        if (!ch.isEmpty()) {
            audit.record(AuditEntry.success("products", "PLAN_UPDATED", "Plan", id).before(ch.before()).after(ch.after()).build());
        }
        return planResponse(plan, prices.findCurrent(id), 0);
    }

    private void validateDates(PlanRequest r) {
        if (r.availableFrom() != null && r.availableUntil() != null && r.availableUntil().isBefore(r.availableFrom())) {
            throw new BusinessException("La vigencia comercial termina antes de empezar");
        }
    }

    private Plan applyPlan(Plan plan, PlanRequest r) {
        plan.setProductId(r.productId());
        plan.setCode(r.code());
        plan.setName(r.name().trim());
        plan.setDescription(blank(r.description()));
        plan.setFeatures(r.features() == null ? new ArrayList<>() : new ArrayList<>(r.features().stream().map(String::trim).toList()));
        plan.setUsageLimits(r.usageLimits() == null ? new HashMap<>() : new HashMap<>(r.usageLimits()));
        plan.setMaxUsers(r.maxUsers());
        plan.setMaxBranches(r.maxBranches());
        plan.setTrialDays(r.trialDays());
        plan.setHighlighted(r.highlighted());
        plan.setCustom(r.custom());
        plan.setActive(r.active() == null || r.active());
        plan.setAvailableFrom(r.availableFrom());
        plan.setAvailableUntil(r.availableUntil());
        plan.setSortOrder(r.sortOrder());
        return plan;
    }

    /** Nuevo precio para una periodicidad: cierra el vigente (si lo hay) y conserva el histórico. */
    @Transactional
    public PlanResponse changePrice(Long planId, PriceRequest r) {
        Plan plan = plan(planId);
        Instant now = clock.instant();
        var current = prices.findCurrentForUpdate(planId, r.billingPeriod());
        boolean taxIncluded = r.taxIncluded() != null ? r.taxIncluded() : settings.bool(Setting.PRICES_INCLUDE_TAX);
        if (current.isPresent() && current.get().getAmount().compareTo(r.amount()) == 0 && current.get().isTaxIncluded() == taxIncluded) {
            throw new BusinessException("El precio es igual al vigente");
        }
        current.ifPresent(c -> {
            c.setValidTo(now);
            prices.saveAndFlush(c);
        });
        PlanPrice created = insertPrice(planId, r);
        audit.record(AuditEntry.success("products", "PRICE_CHANGED", "Plan", planId)
                .before(current.map(c -> Map.<String, Object>of("period", c.getBillingPeriod().name(), "amount", c.getAmount(),
                        "taxIncluded", c.isTaxIncluded())).orElse(null))
                .after(Map.of("period", created.getBillingPeriod().name(), "amount", created.getAmount(), "taxIncluded", created.isTaxIncluded()))
                .detail("Plan " + plan.getCode()).build());
        return planResponse(plan, prices.findCurrent(planId), 0);
    }

    @Transactional
    public PlanResponse retirePrice(Long planId, com.nexora.corp.shared.domain.BillingPeriod period) {
        Plan plan = plan(planId);
        PlanPrice current = prices.findCurrentForUpdate(planId, period).orElseThrow(() -> new NotFoundException("No hay precio vigente para esa periodicidad"));
        current.setValidTo(clock.instant());
        audit.record(AuditEntry.success("products", "PRICE_RETIRED", "Plan", planId).before(Map.of("period", period.name(), "amount", current.getAmount())).build());
        return planResponse(plan, prices.findCurrent(planId), 0);
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> priceHistory(Long planId) {
        plan(planId);
        return prices.findByPlanIdOrderByValidFromDesc(planId).stream().map(PriceResponse::of).toList();
    }

    private PlanPrice insertPrice(Long planId, PriceRequest r) {
        return prices.saveAndFlush(PlanPrice.builder().planId(planId).billingPeriod(r.billingPeriod()).amount(Money.of(r.amount()))
                .currency(settings.currency())
                .taxIncluded(r.taxIncluded() != null ? r.taxIncluded() : settings.bool(Setting.PRICES_INCLUDE_TAX))
                .validFrom(clock.instant()).createdBy(SecurityUtils.currentUserId()).build());
    }

    // ── Cupones ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CouponResponse> coupons() {
        return coupons.findAllByOrderByCreatedAtDesc().stream().map(CouponResponse::of).toList();
    }

    @Transactional
    public CouponResponse createCoupon(CouponRequest r) {
        if (coupons.existsByCode(r.code())) {
            throw new ConflictException("Ya existe un cupón con ese código");
        }
        if ("PERCENT".equals(r.discountType()) && r.discountValue().compareTo(Money.HUNDRED) > 0) {
            throw new BusinessException("El descuento porcentual no puede ser mayor a 100");
        }
        if (r.validFrom() != null && r.validUntil() != null && r.validUntil().isBefore(r.validFrom())) {
            throw new BusinessException("La vigencia termina antes de empezar");
        }
        if (r.productId() != null) {
            product(r.productId());
        }
        Coupon c = coupons.save(Coupon.builder().code(r.code()).description(blank(r.description())).discountType(r.discountType())
                .discountValue(Money.of(r.discountValue())).durationCycles(r.durationCycles()).productId(r.productId())
                .validFrom(r.validFrom()).validUntil(r.validUntil()).maxRedemptions(r.maxRedemptions())
                .createdBy(SecurityUtils.currentUserId()).build());
        audit.record(AuditEntry.success("products", "COUPON_CREATED", "Coupon", c.getId())
                .after(Map.of("code", c.getCode(), "type", c.getDiscountType(), "value", c.getDiscountValue())).build());
        return CouponResponse.of(c);
    }

    @Transactional
    public CouponResponse setCouponActive(Long id, boolean active) {
        Coupon c = coupons.findById(id).orElseThrow(() -> new NotFoundException("Cupón no encontrado"));
        c.setActive(active);
        audit.record(AuditEntry.success("products", active ? "COUPON_ACTIVATED" : "COUPON_DEACTIVATED", "Coupon", id).build());
        return CouponResponse.of(c);
    }

    // ── Apoyo ───────────────────────────────────────────────────────────────────────────

    public Product product(Long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Producto no encontrado"));
    }

    public Plan plan(Long id) {
        return plans.findById(id).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

