package com.nexora.corp.audit;

import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.export.Downloads;
import com.nexora.corp.shared.export.TableExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Consulta de la bitácora (solo lectura; la tabla es inmutable por triggers). */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditQueryController {

    private final NamedParameterJdbcTemplate jdbc;
    private final TableExporter exporter;
    private final AuditService audit;

    private Sql filters(String q, String module, String action, Long actorId, String entityType, String entityId, String result, LocalDate from, LocalDate to) {
        return new Sql()
                .and(q != null && !q.isBlank(), "(lower(a.action) LIKE :q OR lower(COALESCE(a.actor_email, '')) LIKE :q OR lower(COALESCE(a.detail, '')) LIKE :q)",
                        "q", q == null ? null : Sql.like(q))
                .and(Sql.has(module), "a.module = :m", "m", module)
                .and(Sql.has(action), "a.action = :ac", "ac", action)
                .and(actorId != null, "a.actor_user_id = :actor", "actor", actorId)
                .and(Sql.has(entityType), "a.entity_type = :et", "et", entityType)
                .and(Sql.has(entityId), "a.entity_id = :eid", "eid", entityId)
                .and(Sql.has(result), "a.result = :r", "r", result)
                .and(from != null, "a.created_at >= :from", "from", from == null ? null : java.sql.Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .and(to != null, "a.created_at < :to", "to", to == null ? null : java.sql.Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.read')")
    public ApiResponse<PageResponse<Map<String, Object>>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String module,
                                                               @RequestParam(required = false) String action, @RequestParam(required = false) Long actorId,
                                                               @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
                                                               @RequestParam(required = false) String result,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                               @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        PageQuery p = PageQuery.of(page, size, null);
        Sql sql = filters(q, module, action, actorId, entityType, entityId, result, from, to);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs a" + sql.where(), sql.params(), Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.actor_user_id, a.actor_email, a.action, a.module, a.entity_type, a.entity_id, a.before_data::text AS before_data,
                       a.after_data::text AS after_data, a.result, a.detail, a.correlation_id, a.ip, a.created_at
                FROM audit_logs a""" + sql.where() + " ORDER BY a.id DESC" + p.limit(), sql.params());
        return ApiResponse.ok(PageResponse.of(rows, p, total == null ? 0 : total));
    }

    @GetMapping("/modules")
    @PreAuthorize("hasAuthority('audit.read')")
    public ApiResponse<List<Map<String, Object>>> modules() {
        return ApiResponse.ok(jdbc.queryForList("SELECT module, action, COUNT(*) AS total FROM audit_logs GROUP BY module, action ORDER BY module, action", Map.of()));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('audit.read') and hasAuthority('reports.export')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format, @RequestParam(required = false) String module,
                                         @RequestParam(required = false) String result,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Sql sql = filters(null, module, null, null, null, null, result, from, to);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.created_at::text AS created_at, a.actor_email, a.module, a.action, a.entity_type, a.entity_id, a.result, a.detail, a.ip
                FROM audit_logs a""" + sql.where() + " ORDER BY a.id DESC LIMIT 50000", sql.params());
        var table = new TableExporter.Table("Bitácora de auditoría", null, List.of(new TableExporter.Column("id", "#", "number"),
                new TableExporter.Column("created_at", "Fecha (UTC)", "text"), new TableExporter.Column("actor_email", "Usuario", "text"),
                new TableExporter.Column("module", "Módulo", "text"), new TableExporter.Column("action", "Acción", "text"),
                new TableExporter.Column("entity_type", "Entidad", "text"), new TableExporter.Column("entity_id", "Id", "text"),
                new TableExporter.Column("result", "Resultado", "text"), new TableExporter.Column("detail", "Detalle", "text"),
                new TableExporter.Column("ip", "IP", "text")), rows, List.of());
        audit.record(AuditEntry.success("audit", "AUDIT_EXPORTED", "AuditLog", null).detail(format + " · " + rows.size() + " filas").build());
        return Downloads.of(exporter.export(table, format, "auditoria-" + LocalDate.now()));
    }
}
