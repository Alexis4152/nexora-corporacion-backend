package com.nexora.corp.integrations;

import com.nexora.corp.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API versionada para que cada producto Nexora consulte el estado de licencia de SUS clientes (llave por producto,
 * nunca ve otros productos). Solo informa: la decisión de restringir el servicio es del producto, con su propia
 * política explícita; Corporación nunca suspende automáticamente una plataforma de cliente.
 */
@RestController
@RequestMapping("/api/integration/v1")
@RequiredArgsConstructor
public class LicenseApiController {

    private final IntegrationService integrations;
    private final JdbcTemplate jdbc;

    @GetMapping("/license")
    public ResponseEntity<ApiResponse<Object>> license(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                       @RequestParam String customer) {
        Long productId = integrations.productForKey(key);
        if (productId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Llave de API no válida", "INVALID_API_KEY", null));
        }
        if (!customer.matches("^CL-\\d{1,10}$")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Código de cliente no válido"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.code AS customer, s.code AS subscription, s.status, sc.collection_status, pl.code AS plan, pl.max_users, pl.max_branches,
                       s.current_period_end, s.trial_ends_on, sc.days_overdue, s.grace_days
                FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN plans pl ON pl.id = s.plan_id
                JOIN subscription_collection sc ON sc.subscription_id = s.id
                WHERE c.code = ? AND s.product_id = ? ORDER BY s.status IN ('CANCELLED', 'EXPIRED'), s.created_at DESC""", customer, productId);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Sin suscripción para ese cliente", "NOT_FOUND", null));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("apiVersion", "1", "subscriptions", rows)));
    }
}

