package com.nexora.corp.crm;

import com.nexora.corp.crm.CrmDtos.ActivityRequest;
import com.nexora.corp.crm.CrmDtos.ActivityResponse;
import com.nexora.corp.crm.CrmDtos.BoardColumn;
import com.nexora.corp.crm.CrmDtos.CompleteRequest;
import com.nexora.corp.crm.CrmDtos.ProspectCard;
import com.nexora.corp.crm.CrmDtos.ProspectDetail;
import com.nexora.corp.crm.CrmDtos.ProspectRequest;
import com.nexora.corp.crm.CrmDtos.StageRequest;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProspectController {

    private final ProspectService service;

    @GetMapping("/prospects")
    @PreAuthorize("hasAuthority('prospects.read')")
    public ApiResponse<PageResponse<ProspectCard>> list(@RequestParam(required = false) String q, @RequestParam(required = false) ProspectStage stage,
                                                        @RequestParam(required = false) Long productId, @RequestParam(required = false) Long ownerId,
                                                        @RequestParam(required = false) String source,
                                                        @RequestParam(defaultValue = "false") boolean openOnly,
                                                        @RequestParam(required = false) Boolean stale,
                                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new ProspectService.Filters(q, stage, productId, ownerId, source, openOnly, stale),
                PageQuery.of(page, size, sort)));
    }

    @GetMapping("/prospects/board")
    @PreAuthorize("hasAuthority('prospects.read')")
    public ApiResponse<List<BoardColumn>> board(@RequestParam(required = false) String q, @RequestParam(required = false) Long productId,
                                                @RequestParam(required = false) Long ownerId, @RequestParam(required = false) String source,
                                                @RequestParam(required = false) Boolean stale) {
        return ApiResponse.ok(service.board(new ProspectService.Filters(q, null, productId, ownerId, source, false, stale)));
    }

    @GetMapping("/prospects/{id}")
    @PreAuthorize("hasAuthority('prospects.read')")
    public ApiResponse<ProspectDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/prospects")
    @PreAuthorize("hasAuthority('prospects.create')")
    public ApiResponse<ProspectDetail> create(@Valid @RequestBody ProspectRequest request) {
        return ApiResponse.ok(service.create(request), "Prospecto registrado");
    }

    @PutMapping("/prospects/{id}")
    @PreAuthorize("hasAuthority('prospects.update')")
    public ApiResponse<ProspectDetail> update(@PathVariable Long id, @Valid @RequestBody ProspectRequest request) {
        return ApiResponse.ok(service.update(id, request), "Prospecto actualizado");
    }

    @PostMapping("/prospects/{id}/stage")
    @PreAuthorize("hasAuthority('prospects.update')")
    public ApiResponse<ProspectDetail> stage(@PathVariable Long id, @Valid @RequestBody StageRequest request) {
        return ApiResponse.ok(service.changeStage(id, request));
    }

    @PostMapping("/prospects/{id}/activities")
    @PreAuthorize("hasAuthority('prospects.update')")
    public ApiResponse<ActivityResponse> activity(@PathVariable Long id, @Valid @RequestBody ActivityRequest request) {
        return ApiResponse.ok(service.addActivity(id, null, request), "Actividad registrada");
    }

    @GetMapping("/customers/{id}/activities")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<List<ActivityResponse>> customerActivities(@PathVariable Long id) {
        return ApiResponse.ok(service.customerActivities(id));
    }

    @PostMapping("/customers/{id}/activities")
    @PreAuthorize("hasAuthority('customers.update')")
    public ApiResponse<ActivityResponse> customerActivity(@PathVariable Long id, @Valid @RequestBody ActivityRequest request) {
        return ApiResponse.ok(service.addActivity(null, id, request), "Actividad registrada");
    }

    @PostMapping("/activities/{id}/complete")
    @PreAuthorize("hasAuthority('prospects.update') or hasAuthority('customers.update')")
    public ApiResponse<ActivityResponse> complete(@PathVariable Long id, @Valid @RequestBody CompleteRequest request) {
        return ApiResponse.ok(service.completeActivity(id, request.outcome()), "Actividad completada");
    }

    @GetMapping("/activities/upcoming")
    @PreAuthorize("hasAuthority('prospects.read')")
    public ApiResponse<List<ActivityResponse>> upcoming(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(service.upcoming(Instant.now().plus(Duration.ofDays(Math.min(Math.max(days, 1), 90)))));
    }
}
