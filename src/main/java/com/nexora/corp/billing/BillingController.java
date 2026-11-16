package com.nexora.corp.billing;

import com.nexora.corp.billing.BillingDtos.AdjustmentRequest;
import com.nexora.corp.billing.BillingDtos.AgingBucket;
import com.nexora.corp.billing.BillingDtos.ApplyRequest;
import com.nexora.corp.billing.BillingDtos.ChargeDetail;
import com.nexora.corp.billing.BillingDtos.ChargeRequest;
import com.nexora.corp.billing.BillingDtos.ChargeRow;
import com.nexora.corp.billing.BillingDtos.CollectionActionRequest;
import com.nexora.corp.billing.BillingDtos.CollectionActionRow;
import com.nexora.corp.billing.BillingDtos.PaymentDetail;
import com.nexora.corp.billing.BillingDtos.PaymentRequest;
import com.nexora.corp.billing.BillingDtos.PaymentRow;
import com.nexora.corp.billing.BillingDtos.ReasonRequest;
import com.nexora.corp.billing.BillingDtos.ReceivableRow;
import com.nexora.corp.billing.BillingDtos.RefundRequest;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BillingController {

    private final ChargeService charges;
    private final PaymentService payments;
    private final BillingQueryService query;
    private final CollectionsService collections;

    // ── Cargos ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/charges")
    @PreAuthorize("hasAuthority('charges.read')")
    public ApiResponse<PageResponse<ChargeRow>> charges(@RequestParam(required = false) String q, @RequestParam(required = false) Long customerId,
                                                        @RequestParam(required = false) Long subscriptionId, @RequestParam(required = false) Long productId,
                                                        @RequestParam(required = false) String status, @RequestParam(required = false) Boolean overdue,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String sort) {
        return ApiResponse.ok(query.charges(new BillingQueryService.ChargeFilters(q, customerId, subscriptionId, productId, status, overdue, from, to),
                PageQuery.of(page, size, sort)));
    }

    @GetMapping("/charges/{id}")
    @PreAuthorize("hasAuthority('charges.read')")
    public ApiResponse<ChargeDetail> charge(@PathVariable Long id) {
        return ApiResponse.ok(query.charge(id));
    }

    @PostMapping("/charges")
    @PreAuthorize("hasAuthority('charges.create')")
    public ApiResponse<ChargeDetail> createCharge(@Valid @RequestBody ChargeRequest request) {
        return ApiResponse.ok(query.charge(charges.manual(request).getId()), "Cargo generado");
    }

    @PostMapping("/charges/{id}/void")
    @PreAuthorize("hasAuthority('charges.void')")
    public ApiResponse<ChargeDetail> voidCharge(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        charges.voidCharge(id, request.reason());
        return ApiResponse.ok(query.charge(id), "Cargo anulado");
    }

    @PostMapping("/charges/{id}/adjustments")
    @PreAuthorize("hasAuthority('charges.adjust')")
    public ApiResponse<ChargeDetail> adjust(@PathVariable Long id, @Valid @RequestBody AdjustmentRequest request) {
        charges.adjust(id, request);
        return ApiResponse.ok(query.charge(id), "Ajuste registrado");
    }

    // ── Pagos ───────────────────────────────────────────────────────────────────────────

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('payments.read')")
    public ApiResponse<PageResponse<PaymentRow>> payments(@RequestParam(required = false) String q, @RequestParam(required = false) Long customerId,
                                                          @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @RequestParam(required = false) Boolean withCredit,
                                                          @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) String sort) {
        return ApiResponse.ok(query.payments(new BillingQueryService.PaymentFilters(q, customerId, status, method, from, to, withCredit),
                PageQuery.of(page, size, sort)));
    }

    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('payments.read')")
    public ApiResponse<PaymentDetail> payment(@PathVariable Long id) {
        return ApiResponse.ok(query.payment(id));
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('payments.create')")
    public ApiResponse<PaymentDetail> register(@Valid @RequestBody PaymentRequest request,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(query.payment(payments.register(request, idempotencyKey).getId()), "Pago registrado");
    }

    @PostMapping("/payments/{id}/apply")
    @PreAuthorize("hasAuthority('payments.create')")
    public ApiResponse<PaymentDetail> apply(@PathVariable Long id, @Valid @RequestBody ApplyRequest request) {
        payments.applyCredit(id, request.allocations());
        return ApiResponse.ok(query.payment(id), "Saldo aplicado");
    }

    @PostMapping("/payments/allocations/{allocationId}/reverse")
    @PreAuthorize("hasAuthority('payments.reverse')")
    public ApiResponse<Void> unapply(@PathVariable Long allocationId, @Valid @RequestBody ReasonRequest request) {
        payments.unapply(allocationId, request.reason());
        return ApiResponse.ok(null, "Aplicación revertida; el importe queda como saldo a favor");
    }

    @PostMapping("/payments/{id}/confirm")
    @PreAuthorize("hasAuthority('payments.confirm')")
    public ApiResponse<PaymentDetail> confirm(@PathVariable Long id) {
        payments.confirm(id);
        return ApiResponse.ok(query.payment(id), "Pago confirmado");
    }

    @PostMapping("/payments/{id}/reconcile")
    @PreAuthorize("hasAuthority('payments.confirm')")
    public ApiResponse<PaymentDetail> reconcile(@PathVariable Long id) {
        payments.reconcile(id);
        return ApiResponse.ok(query.payment(id), "Pago conciliado");
    }

    @PostMapping("/payments/{id}/reject")
    @PreAuthorize("hasAuthority('payments.confirm')")
    public ApiResponse<PaymentDetail> reject(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        payments.reject(id, request.reason());
        return ApiResponse.ok(query.payment(id), "Pago rechazado");
    }

    @PostMapping("/payments/{id}/reverse")
    @PreAuthorize("hasAuthority('payments.reverse')")
    public ApiResponse<PaymentDetail> reverse(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        payments.reverse(id, request.reason());
        return ApiResponse.ok(query.payment(id), "Pago revertido");
    }

    @PostMapping("/payments/{id}/refunds")
    @PreAuthorize("hasAuthority('payments.refund')")
    public ApiResponse<PaymentDetail> refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request) {
        payments.refund(id, request);
        return ApiResponse.ok(query.payment(id), "Devolución registrada");
    }

    // ── Cuentas por cobrar ──────────────────────────────────────────────────────────────

    @GetMapping("/receivables/aging")
    @PreAuthorize("hasAuthority('collections.read')")
    public ApiResponse<List<AgingBucket>> aging() {
        return ApiResponse.ok(collections.aging());
    }

    @GetMapping("/receivables")
    @PreAuthorize("hasAuthority('collections.read')")
    public ApiResponse<PageResponse<ReceivableRow>> receivables(@RequestParam(required = false) String q, @RequestParam(required = false) String bucket,
                                                                @RequestParam(required = false) Long responsibleId,
                                                                @RequestParam(defaultValue = "false") boolean overdueOnly,
                                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                                @RequestParam(required = false) String sort) {
        return ApiResponse.ok(collections.receivables(new CollectionsService.Filters(q, bucket, responsibleId, overdueOnly), PageQuery.of(page, size, sort)));
    }

    @GetMapping("/collection-actions")
    @PreAuthorize("hasAuthority('collections.read')")
    public ApiResponse<List<CollectionActionRow>> actions(@RequestParam(required = false) Long customerId, @RequestParam(required = false) Boolean upcoming) {
        return ApiResponse.ok(collections.actions(customerId, upcoming));
    }

    @PostMapping("/collection-actions")
    @PreAuthorize("hasAuthority('collections.manage')")
    public ApiResponse<CollectionActionRow> addAction(@Valid @RequestBody CollectionActionRequest request) {
        return ApiResponse.ok(collections.addAction(request), "Gestión registrada");
    }
}

