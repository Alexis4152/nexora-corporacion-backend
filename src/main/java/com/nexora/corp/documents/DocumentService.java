package com.nexora.corp.documents;

import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Contratos, comprobantes, cotizaciones y evidencias. Para adjuntar o descargar se exige, además de
 * documents.*, el permiso de lectura del registro dueño (un vendedor no descarga comprobantes de pago).
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    public record DocumentRow(Long id, String ownerType, Long ownerId, String category, String title, String originalName,
                              String mimeType, long sizeBytes, String uploadedBy, Instant createdAt) {
    }

    public record Download(String filename, String mime, byte[] content) {
    }

    private static final Map<String, String> OWNER_TABLE = Map.of("CUSTOMER", "customers", "SUBSCRIPTION", "subscriptions",
            "PAYMENT", "payments", "EXPENSE", "expenses", "TICKET", "support_tickets", "QUOTE", "quotes", "PROSPECT", "prospects");
    private static final Map<String, String> OWNER_PERMISSION = Map.of("CUSTOMER", "customers.read", "SUBSCRIPTION", "subscriptions.read",
            "PAYMENT", "payments.read", "EXPENSE", "expenses.read", "TICKET", "support.read", "QUOTE", "quotes.read", "PROSPECT", "prospects.read");

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageService storage;
    private final AuditService audit;
    private final ReauthGuard reauth;

    private void checkOwner(String ownerType, Long ownerId) {
        String table = OWNER_TABLE.get(ownerType);
        if (table == null) {
            throw new BusinessException("Tipo de registro no válido");
        }
        if (!SecurityUtils.has(OWNER_PERMISSION.get(ownerType))) {
            throw new org.springframework.security.access.AccessDeniedException("Sin permiso sobre el registro");
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = :id", Map.of("id", ownerId), Integer.class);
        if (n == null || n == 0) {
            throw new NotFoundException("Registro no encontrado");
        }
        if ("PROSPECT".equals(ownerType) && !SecurityUtils.has("prospects.read_all")) {
            Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM prospects WHERE id = :id AND owner_user_id = :me",
                    Map.of("id", ownerId, "me", SecurityUtils.currentUserId()), Integer.class);
            if (mine == null || mine == 0) {
                throw new NotFoundException("Registro no encontrado");
            }
        }
    }

    @Transactional
    public DocumentRow upload(String ownerType, Long ownerId, String category, String title, String originalName, byte[] content) {
        checkOwner(ownerType, ownerId);
        String safeName = originalName == null ? "archivo" : originalName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safeName.length() > 200) {
            safeName = safeName.substring(safeName.length() - 200);
        }
        StorageService.Stored s = storage.save(content, safeName);
        Long id = jdbc.queryForObject("""
                INSERT INTO documents (owner_type, owner_id, category, title, original_name, mime_type, size_bytes, sha256, storage_key, uploaded_by)
                VALUES (:ot, :oid, :cat, :title, :name, :mime, :size, :sha, :key, :by) RETURNING id""", new MapSqlParameterSource()
                .addValue("ot", ownerType).addValue("oid", ownerId).addValue("cat", category)
                .addValue("title", title == null || title.isBlank() ? safeName : title.trim()).addValue("name", safeName)
                .addValue("mime", s.mime()).addValue("size", s.size()).addValue("sha", s.sha256()).addValue("key", s.key())
                .addValue("by", SecurityUtils.currentUserId()), Long.class);
        audit.record(AuditEntry.success("documents", "DOCUMENT_UPLOADED", ownerType, ownerId)
                .after(Map.of("document", id, "name", safeName, "sha256", s.sha256())).build());
        return list(ownerType, ownerId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<DocumentRow> list(String ownerType, Long ownerId) {
        checkOwner(ownerType, ownerId);
        return jdbc.query("""
                SELECT d.id, d.owner_type, d.owner_id, d.category, d.title, d.original_name, d.mime_type, d.size_bytes,
                       u.first_name || ' ' || u.last_name, d.created_at
                FROM documents d LEFT JOIN users u ON u.id = d.uploaded_by
                WHERE d.owner_type = :ot AND d.owner_id = :oid AND d.deleted_at IS NULL ORDER BY d.created_at DESC""",
                Map.of("ot", ownerType, "oid", ownerId), (rs, i) -> new DocumentRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
                        rs.getTimestamp(10).toInstant()));
    }

    @Transactional
    public Download download(Long id) {
        Map<String, Object> d = find(id);
        checkOwner((String) d.get("owner_type"), ((Number) d.get("owner_id")).longValue());
        audit.record(AuditEntry.success("documents", "DOCUMENT_DOWNLOADED", "Document", id).detail((String) d.get("original_name")).build());
        return new Download((String) d.get("original_name"), (String) d.get("mime_type"), storage.read((String) d.get("storage_key")));
    }

    @Transactional
    public void delete(Long id) {
        reauth.require();
        Map<String, Object> d = find(id);
        checkOwner((String) d.get("owner_type"), ((Number) d.get("owner_id")).longValue());
        jdbc.update("UPDATE documents SET deleted_at = now(), deleted_by = :by WHERE id = :id", Map.of("by", SecurityUtils.currentUserId(), "id", id));
        audit.record(AuditEntry.success("documents", "DOCUMENT_REMOVED", "Document", id).detail((String) d.get("original_name")).build());
    }

    private Map<String, Object> find(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM documents WHERE id = :id AND deleted_at IS NULL", Map.of("id", id));
        if (rows.isEmpty()) {
            throw new NotFoundException("Documento no encontrado");
        }
        return rows.get(0);
    }
}

