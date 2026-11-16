package com.nexora.corp.analytics;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.export.Downloads;
import com.nexora.corp.shared.export.TableExporter;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalyticsController {

    private final DashboardService dashboard;
    private final SaasMetricsService saas;
    private final ReportService reports;
    private final Customer360Service customer360;
    private final HealthService health;
    private final CalendarService calendar;
    private final SearchService search;
    private final TableExporter exporter;
    private final AuditService audit;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('dashboard.read')")
    public ApiResponse<Map<String, Object>> executive(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(required = false) Long productId, @RequestParam(required = false) Long planId) {
        return ApiResponse.ok(dashboard.executive(from, to, productId, planId));
    }

    @GetMapping("/dashboard/products/{id}")
    @PreAuthorize("hasAuthority('dashboard.read') and hasAuthority('products.read')")
    public ApiResponse<Map<String, Object>> product(@PathVariable Long id,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(dashboard.product(id, from, to));
    }

    @GetMapping("/metrics/saas")
    @PreAuthorize("hasAuthority('reports.financial_read')")
    public ApiResponse<Map<String, Object>> saas(@RequestParam(defaultValue = "12") int months, @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(saas.monthly(Math.min(Math.max(months, 2), 36), productId));
    }

    @GetMapping("/metrics/cohorts")
    @PreAuthorize("hasAuthority('reports.financial_read')")
    public ApiResponse<Map<String, Object>> cohorts(@RequestParam(defaultValue = "12") int months, @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(saas.cohorts(Math.min(Math.max(months, 3), 24), productId));
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('reports.commercial_read') or hasAuthority('reports.financial_read')")
    public ApiResponse<List<ReportService.ReportDef>> catalog() {
        return ApiResponse.ok(reports.available());
    }

    @GetMapping("/reports/{type}")
    @PreAuthorize("hasAuthority('reports.commercial_read') or hasAuthority('reports.financial_read')")
    public ApiResponse<ReportService.Report> report(@PathVariable String type,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(reports.run(type, from, to, productId));
    }

    @GetMapping("/reports/{type}/export")
    @PreAuthorize("hasAuthority('reports.export')")
    public ResponseEntity<byte[]> export(@PathVariable String type, @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long productId) {
        ReportService.Report r = reports.run(type, from, to, productId);
        TableExporter.ExportFile file = exporter.export(reports.table(r), format, "nexora-" + type + "-" + r.from() + "_" + r.to());
        audit.record(AuditEntry.success("reports", "REPORT_EXPORTED", "Report", type)
                .detail(format + " · " + r.rows().size() + " filas · " + r.from() + " a " + r.to()).build());
        return Downloads.of(file);
    }

    @GetMapping("/customers/{id}/overview")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<Map<String, Object>> overview(@PathVariable Long id) {
        return ApiResponse.ok(customer360.overview(id));
    }

    @GetMapping("/customers/{id}/timeline")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable Long id) {
        return ApiResponse.ok(customer360.timeline(id));
    }

    @GetMapping("/customers/{id}/statement")
    @PreAuthorize("hasAuthority('charges.read')")
    public ApiResponse<Map<String, Object>> statement(@PathVariable Long id) {
        return ApiResponse.ok(customer360.statement(id));
    }

    @GetMapping("/customers/{id}/health")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<HealthService.Health> health(@PathVariable Long id) {
        return ApiResponse.ok(health.of(id));
    }

    @GetMapping("/health/at-risk")
    @PreAuthorize("hasAuthority('customers.read')")
    public ApiResponse<List<Map<String, Object>>> atRisk(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(health.atRisk(Math.min(limit, 100)));
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('calendar.read')")
    public ApiResponse<List<Map<String, Object>>> calendar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 62) {
            throw new BusinessException("Consulta hasta 62 días a la vez");
        }
        return ApiResponse.ok(calendar.events(from, to));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String q) {
        if (q.length() > 80) {
            throw new BusinessException("Búsqueda demasiado larga");
        }
        if (SecurityUtils.currentPrincipal().isEmpty()) {
            throw new AccessDeniedException("Sin sesión");
        }
        return ApiResponse.ok(search.search(q));
    }
}
