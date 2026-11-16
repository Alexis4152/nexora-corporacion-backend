package com.nexora.corp.quotes;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.export.Downloads;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService service;

    @GetMapping
    @PreAuthorize("hasAuthority('quotes.read')")
    public ApiResponse<PageResponse<QuoteService.QuoteRow>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) Long customerId, @RequestParam(required = false) Long prospectId,
                                                                 @RequestParam(required = false) Long ownerId,
                                                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(q, status, customerId, prospectId, ownerId, PageQuery.of(page, size, null)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('quotes.read')")
    public ApiResponse<QuoteService.QuoteDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('quotes.manage')")
    public ApiResponse<QuoteService.QuoteDetail> create(@Valid @RequestBody QuoteService.QuoteRequest request) {
        return ApiResponse.ok(service.create(request), "Cotización creada");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('quotes.manage')")
    public ApiResponse<QuoteService.QuoteDetail> update(@PathVariable Long id, @Valid @RequestBody QuoteService.QuoteRequest request) {
        return ApiResponse.ok(service.update(id, request), "Cotización actualizada");
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('quotes.manage')")
    public ApiResponse<QuoteService.QuoteDetail> status(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.setStatus(id, String.valueOf(body.get("status"))));
    }

    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAuthority('quotes.manage')")
    public ApiResponse<QuoteService.QuoteDetail> newVersion(@PathVariable Long id) {
        return ApiResponse.ok(service.newVersion(id), "Nueva versión creada");
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('quotes.read')")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        QuoteService.QuoteDetail q = service.get(id);
        return Downloads.of(q.quote().folio() + "-v" + q.quote().versionNo() + ".pdf", "application/pdf", service.pdf(id));
    }
}

