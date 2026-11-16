package com.nexora.corp.notifications;

import com.nexora.corp.billing.BillingEvents;
import com.nexora.corp.identity.SecurityEvent;
import com.nexora.corp.notifications.NotificationService.Alert;
import com.nexora.corp.notifications.NotificationService.Category;
import com.nexora.corp.notifications.NotificationService.Severity;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.subscriptions.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Traduce eventos de dominio a alertas. Corre dentro de la transacción del evento: si la operación se revierte, la
 * alerta tampoco existe (nunca se avisa algo que no pasó).
 */
@Component
@RequiredArgsConstructor
public class NotificationListeners {

    private final NotificationService notifications;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final DeliveryService deliveries;

    @EventListener
    public void onSecurity(SecurityEvent e) {
        String title = switch (e.type()) {
            case "NEW_LOCATION_LOGIN" -> "Inicio de sesión desde una ubicación nueva";
            case "ACCOUNT_LOCKED" -> "Tu cuenta se bloqueó temporalmente por intentos fallidos";
            case "TOKEN_REUSE" -> "Cerramos una sesión por posible robo de credenciales";
            case "MFA_DISABLED" -> "Se desactivó la verificación en dos pasos";
            case "PASSWORD_CHANGED" -> "Se cambió tu contraseña";
            default -> "Evento de seguridad";
        };
        Severity sev = "PASSWORD_CHANGED".equals(e.type()) ? Severity.INFO : Severity.WARNING;
        notifications.toUser(e.userId(), new Alert(Category.SECURITY, e.type(), sev, title,
                e.detail() + ". Si no fuiste tú, cambia tu contraseña y avisa al administrador.", "/cuenta", "User", e.userId(),
                "SEC:" + e.type() + ":" + System.currentTimeMillis()));
        if ("ACCOUNT_LOCKED".equals(e.type()) || "TOKEN_REUSE".equals(e.type())) {
            String email = jdbc.queryForList("SELECT email FROM users WHERE id = ?", String.class, e.userId()).stream().findFirst().orElse("");
            notifications.toCategory(new Alert(Category.SECURITY, "SUSPICIOUS_" + e.type(), Severity.CRITICAL,
                    "Actividad sospechosa en la cuenta " + email, e.detail(), "/auditoria?user=" + e.userId(), "User", e.userId(),
                    "SECADM:" + e.type() + ":" + e.userId() + ":" + (System.currentTimeMillis() / 600_000)), e.userId());
        }
    }

    @EventListener
    public void onSubscription(SubscriptionService.StatusChanged e) {
        Map<String, Object> info = jdbc.queryForMap("""
                SELECT c.trade_name, p.name AS product, s.mrr, s.trial_ends_on,
                       (SELECT COALESCE(MAX(id), 0) FROM subscription_events WHERE subscription_id = s.id) AS last_event
                FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id WHERE s.id = ?""",
                e.subscriptionId());
        String who = info.get("trade_name") + " · " + info.get("product");
        String link = "/suscripciones/" + e.subscriptionId();
        String episode = String.valueOf(info.get("last_event"));
        Alert a = switch (e.type()) {
            case "ACTIVATED", "TRIAL_CONVERTED" -> new Alert(Category.SUBSCRIPTIONS, "NEW_SUBSCRIPTION", Severity.SUCCESS,
                    ("TRIAL_CONVERTED".equals(e.type()) ? "Prueba convertida: " : "Nueva contratación: ") + who,
                    "Suscripción " + e.code() + " activa.", link, "Subscription", e.subscriptionId(), e.type() + ":" + e.subscriptionId() + ":" + episode);
            case "CREATED" -> e.to().name().equals("TRIAL") ? new Alert(Category.TRIALS, "TRIAL_STARTED", Severity.INFO,
                    "Nueva prueba gratuita: " + who, "Termina el " + info.get("trial_ends_on") + ".", link, "Subscription",
                    e.subscriptionId(), "TRIAL_STARTED:" + e.subscriptionId()) : null;
            case "CANCELLED" -> new Alert(Category.CANCELLATIONS, "CANCELLED", Severity.WARNING, "Cancelación registrada: " + who,
                    "La suscripción " + e.code() + " quedó cancelada.", link, "Subscription", e.subscriptionId(), "CANCELLED:" + e.subscriptionId() + ":" + episode);
            case "CANCEL_SCHEDULED" -> new Alert(Category.CANCELLATIONS, "CANCEL_SCHEDULED", Severity.WARNING, "Cancelación programada: " + who,
                    "Se cancelará al final del periodo actual.", link, "Subscription", e.subscriptionId(), "CANCEL_SCHEDULED:" + e.subscriptionId() + ":" + episode);
            case "TRIAL_EXPIRED" -> new Alert(Category.TRIALS, "TRIAL_EXPIRED", Severity.WARNING, "Terminó la prueba sin convertir: " + who,
                    "No se generó ningún cobro. Contacta al cliente para convertirla.", link, "Subscription", e.subscriptionId(), "TRIAL_EXPIRED:" + e.subscriptionId());
            case "EXPIRED" -> new Alert(Category.SUBSCRIPTIONS, "EXPIRED", Severity.WARNING, "Suscripción expirada: " + who,
                    "La renovación manual no se confirmó.", link, "Subscription", e.subscriptionId(), "EXPIRED:" + e.subscriptionId() + ":" + episode);
            case "GRACE_STARTED" -> new Alert(Category.COLLECTION, "GRACE_STARTED", Severity.WARNING, "Cliente con adeudo: " + who,
                    "La suscripción entró en periodo de gracia.", link, "Subscription", e.subscriptionId(), "GRACE:" + e.subscriptionId() + ":" + episode);
            case "GRACE_ENDED" -> new Alert(Category.COLLECTION, "GRACE_ENDED", Severity.CRITICAL, "Terminó la gracia: " + who,
                    "La política del producto es suspensión manual: decide si suspender.", link, "Subscription", e.subscriptionId(),
                    "GRACE_ENDED:" + e.subscriptionId() + ":" + episode);
            case "SUSPENDED" -> new Alert(Category.COLLECTION, "SUSPENDED", Severity.CRITICAL, "Suscripción suspendida: " + who,
                    "Se retiró del MRR hasta que se reactive.", link, "Subscription", e.subscriptionId(), "SUSPENDED:" + e.subscriptionId() + ":" + episode);
            case "READY_TO_REACTIVATE" -> new Alert(Category.PAYMENTS, "READY_TO_REACTIVATE", Severity.INFO, "Al corriente y suspendida: " + who,
                    "Se recibió el pago del adeudo. Puedes reactivar la suscripción.", link, "Subscription", e.subscriptionId(),
                    "READY:" + e.subscriptionId() + ":" + episode);
            case "REACTIVATED" -> new Alert(Category.SUBSCRIPTIONS, "REACTIVATED", Severity.SUCCESS, "Suscripción reactivada: " + who, null,
                    link, "Subscription", e.subscriptionId(), "REACTIVATED:" + e.subscriptionId() + ":" + episode);
            default -> null;
        };
        if (a != null) {
            notifications.toCategory(a, SecurityUtils.currentUserId());
        }
        if ("SUSPENDED".equals(e.type())) {
            deliveries.suspensionNotice(e.subscriptionId());
        }
    }

    @EventListener
    public void onPayment(BillingEvents.PaymentRegistered e) {
        if (e.amount().compareTo(settings.decimal(Setting.LARGE_PAYMENT)) >= 0) {
            String amount = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).format(e.amount());
            notifications.toCategory(new Alert(Category.PAYMENTS, "LARGE_PAYMENT", Severity.SUCCESS, "Pago importante recibido: " + amount,
                    e.customerName() + " · " + e.folio(), "/cobranza/pagos/" + e.paymentId(), "Payment", e.paymentId(), "LARGE:" + e.paymentId()),
                    SecurityUtils.currentUserId());
        }
    }

    @EventListener
    public void onReversal(BillingEvents.PaymentReversed e) {
        String name = jdbc.queryForList("SELECT trade_name FROM customers WHERE id = ?", String.class, e.customerId()).stream().findFirst().orElse("");
        notifications.toCategory(new Alert(Category.PAYMENTS, "PAYMENT_REVERSED", Severity.WARNING, "Pago revertido: " + e.folio(),
                name + " · " + e.reason(), "/cobranza/pagos/" + e.paymentId(), "Payment", e.paymentId(), "REVERSED:" + e.paymentId()),
                SecurityUtils.currentUserId());
    }

    static List<String> none() {
        return List.of();
    }
}

