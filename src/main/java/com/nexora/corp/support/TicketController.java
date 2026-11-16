package com.nexora.corp.support;

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

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService service;

    @GetMapping
    @PreAuthorize("hasAuthority('support.read')")
    public ApiResponse<PageResponse<TicketService.TicketRow>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                                                   @RequestParam(required = false) String priority, @RequestParam(required = false) Long assigneeId,
                                                                   @RequestParam(required = false) Long customerId, @RequestParam(required = false) Long productId,
                                                                   @RequestParam(defaultValue = "false") boolean openOnly,
                                                                   @RequestParam(defaultValue = "false") boolean mine,
                                                                   @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                                   @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new TicketService.Filters(q, status, priority, assigneeId, customerId, productId, openOnly, mine),
                PageQuery.of(page, size, sort)));
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAuthority('support.read')")
    public ApiResponse<Map<String, Object>> metrics(@RequestParam(required = false) Long productId) {
        return ApiResponse.ok(service.metrics(productId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('support.read')")
    public ApiResponse<TicketService.TicketDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('support.manage')")
    public ApiResponse<TicketService.TicketDetail> create(@Valid @RequestBody TicketService.TicketRequest request) {
        return ApiResponse.ok(service.create(request), "Ticket creado");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('support.manage')")
    public ApiResponse<TicketService.TicketDetail> update(@PathVariable Long id, @Valid @RequestBody TicketService.TicketRequest request) {
        return ApiResponse.ok(service.update(id, request), "Ticket actualizado");
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('support.manage')")
    public ApiResponse<TicketService.TicketDetail> status(@PathVariable Long id, @Valid @RequestBody TicketService.StatusRequest request) {
        return ApiResponse.ok(service.changeStatus(id, request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('support.manage')")
    public ApiResponse<TicketService.TicketDetail> assign(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        return ApiResponse.ok(service.assign(id, body.get("userId")));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAuthority('support.manage')")
    public ApiResponse<TicketService.TicketDetail> comment(@PathVariable Long id, @Valid @RequestBody TicketService.CommentRequest request) {
        return ApiResponse.ok(service.comment(id, request));
    }
}

