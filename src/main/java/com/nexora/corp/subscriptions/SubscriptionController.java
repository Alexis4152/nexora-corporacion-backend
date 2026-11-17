package com.nexora.corp.subscriptions;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.jobs.JobRunner;
import com.nexora.corp.subscriptions.SubscriptionDtos.CancelRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.ChangePlanRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.CreateRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.DiscountRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.ExtendTrialRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.ReasonRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.SellerRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.SubscriptionDetail;
import com.nexora.corp.subscriptions.SubscriptionDtos.SubscriptionRow;
import com.nexora.corp.subscriptions.SubscriptionDtos.TermsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionQueryService query;
    private final SubscriptionJobs jobs;

    @GetMapping
    @PreAuthorize("hasAuthority('subscriptions.read')")
    public ApiResponse<PageResponse<SubscriptionRow>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String collection,
                                                           @RequestParam(required = false) Long productId, @RequestParam(required = false) Long planId,
                                                           @RequestParam(required = false) Long customerId, @RequestParam(required = false) String period,
                                                           @RequestParam(required = false) Boolean trialEndingSoon,
                                                           @RequestParam(required = false) Long sellerId,
                                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                           @RequestParam(required = false) String sort) {
        return ApiResponse.ok(query.list(new SubscriptionQueryService.Filters(q, status, collection, productId, planId, customerId, period,
                trialEndingSoon, sellerId), PageQuery.of(page, size, sort)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('subscriptions.read')")
    public ApiResponse<SubscriptionDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('subscriptions.create')")
    public ApiResponse<SubscriptionDetail> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(query.detail(service.create(request).getId()), "Suscripción creada");
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('subscriptions.create')")
    public ApiResponse<SubscriptionDetail> activate(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.activateNow(id).getId()), "Suscripción activada");
    }

    @PostMapping("/{id}/convert-trial")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> convert(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.convertTrial(id).getId()), "Prueba convertida a plan pagado");
    }

    @PostMapping("/{id}/extend-trial")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> extendTrial(@PathVariable Long id, @Valid @RequestBody ExtendTrialRequest request) {
        return ApiResponse.ok(query.detail(service.extendTrial(id, request).getId()), "Prueba gratuita extendida");
    }

    @PostMapping("/{id}/change-plan")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> changePlan(@PathVariable Long id, @Valid @RequestBody ChangePlanRequest request) {
        return ApiResponse.ok(query.detail(service.changePlan(id, request).getId()), "Plan actualizado");
    }

    @PostMapping("/{id}/cancel-pending-change")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> cancelPending(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.cancelPendingChange(id).getId()), "Cambio programado cancelado");
    }

    @PostMapping("/{id}/discount")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> discount(@PathVariable Long id, @Valid @RequestBody DiscountRequest request) {
        return ApiResponse.ok(query.detail(service.setDiscount(id, request).getId()), "Descuento actualizado");
    }

    @PutMapping("/{id}/terms")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> terms(@PathVariable Long id, @Valid @RequestBody TermsRequest request) {
        return ApiResponse.ok(query.detail(service.updateTerms(id, request).getId()), "Condiciones actualizadas");
    }

    @PutMapping("/{id}/seller")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> seller(@PathVariable Long id, @Valid @RequestBody SellerRequest request) {
        return ApiResponse.ok(query.detail(service.changeSeller(id, request).getId()), "Vendedor actualizado");
    }

    @PostMapping("/{id}/renew")
    @PreAuthorize("hasAuthority('subscriptions.update')")
    public ApiResponse<SubscriptionDetail> renew(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.renewNow(id).getId()), "Renovación confirmada");
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('subscriptions.suspend')")
    public ApiResponse<SubscriptionDetail> suspend(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(query.detail(service.suspend(id, request.reason()).getId()), "Suscripción suspendida");
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('subscriptions.suspend')")
    public ApiResponse<SubscriptionDetail> reactivate(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.reactivate(id).getId()), "Suscripción reactivada");
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('subscriptions.cancel')")
    public ApiResponse<SubscriptionDetail> cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(query.detail(service.cancel(id, request).getId()), "Cancelación registrada");
    }

    @PostMapping("/{id}/undo-cancel")
    @PreAuthorize("hasAuthority('subscriptions.cancel')")
    public ApiResponse<SubscriptionDetail> undoCancel(@PathVariable Long id) {
        return ApiResponse.ok(query.detail(service.undoCancel(id).getId()), "Cancelación revertida");
    }

    /** Ejecuta el ciclo de tareas a demanda (idempotente). */
    @PostMapping("/run-cycle")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<List<JobRunner.Result>> runCycle() {
        return ApiResponse.ok(jobs.runCycle(), "Ciclo ejecutado");
    }
}
