package com.nexora.corp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuración de negocio en BD ({@code system_settings}), con tipos y validación por clave ({@link Setting}).
 * Caché de 30 s: con varias instancias, un cambio se ve en todas en menos de medio minuto.
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final long TTL_MS = 30_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final Clock clock;

    private volatile Map<String, JsonNode> cache = Map.of();
    private volatile long loadedAt;

    private Map<String, JsonNode> values() {
        if (System.currentTimeMillis() - loadedAt > TTL_MS || cache.isEmpty()) {
            Map<String, JsonNode> map = new ConcurrentHashMap<>();
            jdbc.query("SELECT setting_key, value::text FROM system_settings", rs -> {
                try {
                    map.put(rs.getString(1), objectMapper.readTree(rs.getString(2)));
                } catch (Exception e) {
                    throw new IllegalStateException("Valor de configuración ilegible: " + rs.getString(1), e);
                }
            });
            cache = map;
            loadedAt = System.currentTimeMillis();
        }
        return cache;
    }

    private JsonNode node(Setting s) {
        JsonNode n = values().get(s.key());
        return n != null ? n : objectMapper.valueToTree(s.defaultValue());
    }

    public String string(Setting s) {
        return node(s).asText();
    }

    public int integer(Setting s) {
        return node(s).asInt();
    }

    public boolean bool(Setting s) {
        return node(s).asBoolean();
    }

    public BigDecimal decimal(Setting s) {
        return node(s).decimalValue();
    }

    public List<Integer> intList(Setting s) {
        List<Integer> out = new ArrayList<>();
        node(s).forEach(n -> out.add(n.asInt()));
        return out;
    }

    public ZoneId zone() {
        return ZoneId.of(string(Setting.TIMEZONE));
    }

    /** "Hoy" en la zona horaria de negocio (no la del servidor, que corre en UTC). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    public String currency() {
        return string(Setting.CURRENCY);
    }

    public BigDecimal taxRate() {
        return decimal(Setting.TAX_RATE);
    }

    /** Todas las claves con su valor actual, agrupadas por categoría. */
    public Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Setting s : Setting.values()) {
            out.computeIfAbsent(s.category(), k -> new LinkedHashMap<>())
                    .put(s.key(), objectMapper.convertValue(node(s), Object.class));
        }
        return out;
    }

    @Transactional
    public Map<String, Map<String, Object>> update(Map<String, Object> changes) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (var entry : changes.entrySet()) {
            Setting s = Setting.byKey(entry.getKey())
                    .orElseThrow(() -> new BusinessException("Parámetro desconocido: " + entry.getKey()));
            JsonNode value = objectMapper.valueToTree(entry.getValue());
            String problem = s.validate(value);
            if (problem != null) {
                throw new BusinessException(s.label() + ": " + problem);
            }
            JsonNode old = node(s);
            if (old.equals(value)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO system_settings (setting_key, value, category, updated_by) VALUES (?, ?::jsonb, ?, ?)
                    ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(),
                      updated_by = EXCLUDED.updated_by, version = system_settings.version + 1""",
                    s.key(), value.toString(), s.category(), SecurityUtils.currentUserId());
            before.put(s.key(), objectMapper.convertValue(old, Object.class));
            after.put(s.key(), objectMapper.convertValue(value, Object.class));
        }
        if (!after.isEmpty()) {
            audit.record(AuditEntry.success("settings", "SETTINGS_UPDATED", "Settings", null).before(before).after(after).build());
        }
        loadedAt = 0;
        return all();
    }

    public void invalidate() {
        loadedAt = 0;
    }
}

