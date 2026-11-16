package com.nexora.corp.expenses;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExpenseController {

    public record VoidRequest(@NotBlank(message = "Escribe el motivo") @Size(max = 300) String reason) {
    }

    private final ExpenseService service;

    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('expenses.read')")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long productId,
                                                 @RequestParam(defaultValue = "false") boolean includeVoided,
                                                 @RequestParam(required = false) Boolean companyOnly,
                                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(new ExpenseService.Filters(q, from, to, categoryId, productId, includeVoided, companyOnly),
                PageQuery.of(page, size, sort)));
    }

    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<Long> create(@Valid @RequestBody ExpenseService.ExpenseRequest request) {
        return ApiResponse.ok(service.create(request), "Gasto registrado");
    }

    @PutMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody ExpenseService.ExpenseRequest request) {
        service.update(id, request);
        return ApiResponse.ok(null, "Gasto actualizado");
    }

    @PostMapping("/expenses/{id}/void")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<Void> voidExpense(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        service.voidExpense(id, request.reason());
        return ApiResponse.ok(null, "Gasto anulado");
    }

    @GetMapping("/expense-categories")
    @PreAuthorize("hasAuthority('expenses.read')")
    public ApiResponse<List<ExpenseService.CategoryRow>> categories() {
        return ApiResponse.ok(service.categories());
    }

    @PostMapping("/expense-categories")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<List<ExpenseService.CategoryRow>> createCategory(@Valid @RequestBody ExpenseService.CategoryRequest request) {
        service.saveCategory(null, request);
        return ApiResponse.ok(service.categories(), "Categoría creada");
    }

    @PutMapping("/expense-categories/{id}")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<List<ExpenseService.CategoryRow>> updateCategory(@PathVariable Long id, @Valid @RequestBody ExpenseService.CategoryRequest request) {
        service.saveCategory(id, request);
        return ApiResponse.ok(service.categories(), "Categoría actualizada");
    }

    @PostMapping("/expense-categories/{id}/active")
    @PreAuthorize("hasAuthority('expenses.manage')")
    public ApiResponse<List<ExpenseService.CategoryRow>> toggle(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        service.toggleCategory(id, Boolean.TRUE.equals(body.get("active")));
        return ApiResponse.ok(service.categories());
    }
}

