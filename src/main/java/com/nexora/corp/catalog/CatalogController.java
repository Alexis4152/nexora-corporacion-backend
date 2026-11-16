package com.nexora.corp.catalog;

import com.nexora.corp.catalog.CatalogDtos.ActiveRequest;
import com.nexora.corp.catalog.CatalogDtos.CouponRequest;
import com.nexora.corp.catalog.CatalogDtos.CouponResponse;
import com.nexora.corp.catalog.CatalogDtos.PlanRequest;
import com.nexora.corp.catalog.CatalogDtos.PlanResponse;
import com.nexora.corp.catalog.CatalogDtos.PriceRequest;
import com.nexora.corp.catalog.CatalogDtos.PriceResponse;
import com.nexora.corp.catalog.CatalogDtos.ProductRequest;
import com.nexora.corp.catalog.CatalogDtos.ProductResponse;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.domain.BillingPeriod;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService service;

    /** Catálogo para menús y formularios (cualquier usuario autenticado lo necesita para navegar). */
    @GetMapping("/products")
    public ApiResponse<List<ProductResponse>> products(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(service.list(includeInactive));
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('products.read')")
    public ApiResponse<ProductResponse> product(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/products/by-code/{code}")
    @PreAuthorize("hasAuthority('products.read')")
    public ApiResponse<ProductResponse> productByCode(@PathVariable String code) {
        return ApiResponse.ok(service.getByCode(code));
    }

    @PostMapping("/products")
    @PreAuthorize("hasAuthority('products.manage')")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ApiResponse.ok(service.createProduct(request), "Producto creado");
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public ApiResponse<ProductResponse> update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ApiResponse.ok(service.updateProduct(id, request), "Producto actualizado");
    }

    @PostMapping("/products/{id}/active")
    @PreAuthorize("hasAuthority('products.manage')")
    public ApiResponse<ProductResponse> active(@PathVariable Long id, @Valid @RequestBody ActiveRequest request) {
        return ApiResponse.ok(service.setProductActive(id, request.active()));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<PlanResponse> createPlan(@Valid @RequestBody PlanRequest request) {
        return ApiResponse.ok(service.createPlan(request), "Plan creado");
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<PlanResponse> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest request) {
        return ApiResponse.ok(service.updatePlan(id, request), "Plan actualizado");
    }

    @PostMapping("/plans/{id}/prices")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<PlanResponse> price(@PathVariable Long id, @Valid @RequestBody PriceRequest request) {
        return ApiResponse.ok(service.changePrice(id, request), "Precio actualizado; el anterior queda en el histórico");
    }

    @DeleteMapping("/plans/{id}/prices/{period}")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<PlanResponse> retire(@PathVariable Long id, @PathVariable BillingPeriod period) {
        return ApiResponse.ok(service.retirePrice(id, period), "Periodicidad retirada");
    }

    @GetMapping("/plans/{id}/prices")
    @PreAuthorize("hasAuthority('products.read')")
    public ApiResponse<List<PriceResponse>> history(@PathVariable Long id) {
        return ApiResponse.ok(service.priceHistory(id));
    }

    @GetMapping("/coupons")
    @PreAuthorize("hasAuthority('products.read')")
    public ApiResponse<List<CouponResponse>> coupons() {
        return ApiResponse.ok(service.coupons());
    }

    @PostMapping("/coupons")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<CouponResponse> createCoupon(@Valid @RequestBody CouponRequest request) {
        return ApiResponse.ok(service.createCoupon(request), "Cupón creado");
    }

    @PostMapping("/coupons/{id}/active")
    @PreAuthorize("hasAuthority('plans.manage')")
    public ApiResponse<CouponResponse> couponActive(@PathVariable Long id, @Valid @RequestBody ActiveRequest request) {
        return ApiResponse.ok(service.setCouponActive(id, request.active()));
    }
}
