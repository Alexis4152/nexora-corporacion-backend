package com.nexora.corp.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

/** Catálogo de parámetros de negocio editables, con tipo, valor por defecto y validación. */
public enum Setting {

    COMPANY_NAME("company.name", "company", "Nombre corporativo", "Nexora Systems", Type.TEXT),
    COMPANY_LEGAL_NAME("company.legalName", "company", "Razón social", "", Type.TEXT_OPTIONAL),
    COMPANY_EMAIL("company.email", "company", "Correo de contacto", "", Type.EMAIL_OPTIONAL),
    COMPANY_PHONE("company.phone", "company", "Teléfono", "", Type.TEXT_OPTIONAL),
    COMPANY_WEBSITE("company.website", "company", "Sitio web", "", Type.URL_OPTIONAL),
    PRIMARY_COLOR("company.primaryColor", "company", "Color principal", "#5b5bf7", Type.COLOR),
    ACCENT_COLOR("company.accentColor", "company", "Color de acento", "#14b8a6", Type.COLOR),
    TIMEZONE("business.timezone", "business", "Zona horaria de negocio", "America/Mexico_City", Type.ZONE),
    CURRENCY("finance.currency", "finance", "Moneda corporativa", "MXN", Type.CURRENCY),
    TAX_RATE("finance.taxRate", "finance", "Tasa de IVA", 0.16, Type.RATE),
    PRICES_INCLUDE_TAX("finance.pricesIncludeTax", "finance", "Precios con IVA incluido por defecto", false, Type.BOOL),
    DEFAULT_GRACE_DAYS("billing.defaultGraceDays", "billing", "Días de gracia por defecto", 5, Type.DAYS),
    DEFAULT_TRIAL_DAYS("billing.defaultTrialDays", "billing", "Días de prueba por defecto", 14, Type.DAYS),
    CHARGE_LEAD_DAYS("billing.chargeLeadDays", "billing", "Días de anticipación para emitir cargos", 5, Type.DAYS),
    DUE_SOON_DAYS("billing.dueSoonDays", "billing", "Días para considerar un pago próximo a vencer", 5, Type.DAYS),
    AGING_BUCKETS("collections.agingBuckets", "collections", "Intervalos de antigüedad de cartera (días)", new int[]{7, 15, 30, 60}, Type.BUCKETS),
    REMINDER_BEFORE_DAYS("collections.reminderBeforeDays", "collections", "Aviso previo (días antes del vencimiento)", 3, Type.DAYS),
    REMINDER_ON_DUE("collections.reminderOnDue", "collections", "Aviso el día del vencimiento", true, Type.BOOL),
    REMINDER_AFTER_DAYS("collections.reminderAfterDays", "collections", "Recordatorios por atraso (días)", new int[]{3, 10}, Type.BUCKETS),
    CUSTOMER_EMAILS("collections.customerEmails", "collections", "Enviar correos de cobranza a clientes", false, Type.BOOL),
    TRIAL_ENDING_DAYS("alerts.trialEndingDays", "alerts", "Alertar pruebas que terminan en (días)", 3, Type.DAYS),
    PROSPECT_STALE_DAYS("alerts.prospectStaleDays", "alerts", "Prospecto sin seguimiento después de (días)", 7, Type.DAYS),
    LARGE_PAYMENT("alerts.largePaymentAmount", "alerts", "Pago importante a partir de", 20000, Type.AMOUNT),
    RENEWAL_DAYS("alerts.renewalDays", "alerts", "Alertar renovaciones en (días)", 7, Type.DAYS);

    enum Type { TEXT, TEXT_OPTIONAL, EMAIL_OPTIONAL, URL_OPTIONAL, COLOR, ZONE, CURRENCY, RATE, BOOL, DAYS, BUCKETS, AMOUNT }

    private final String key;
    private final String category;
    private final String label;
    private final Object defaultValue;
    private final Type type;

    Setting(String key, String category, String label, Object defaultValue, Type type) {
        this.key = key;
        this.category = category;
        this.label = label;
        this.defaultValue = defaultValue;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public String category() {
        return category;
    }

    public String label() {
        return label;
    }

    public Object defaultValue() {
        return defaultValue;
    }

    public static Optional<Setting> byKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }

    /** Problema del valor o null si es válido. */
    String validate(JsonNode v) {
        return switch (type) {
            case TEXT -> v.isTextual() && !v.asText().isBlank() && v.asText().length() <= 150 ? null : "escribe un texto (máx. 150)";
            case TEXT_OPTIONAL -> v.isTextual() && v.asText().length() <= 250 ? null : "texto demasiado largo";
            case EMAIL_OPTIONAL -> v.isTextual() && (v.asText().isEmpty() || v.asText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$"))
                    ? null : "correo no válido";
            case URL_OPTIONAL -> v.isTextual() && (v.asText().isEmpty() || v.asText().matches("^https?://[^\\s]{3,250}$"))
                    ? null : "URL no válida (http/https)";
            case COLOR -> v.isTextual() && v.asText().matches("^#[0-9A-Fa-f]{6}$") ? null : "usa un color #RRGGBB";
            case ZONE -> v.isTextual() && validZone(v.asText()) ? null : "zona horaria no válida";
            case CURRENCY -> v.isTextual() && v.asText().matches("^[A-Z]{3}$") ? null : "moneda ISO de 3 letras";
            case RATE -> v.isNumber() && v.decimalValue().signum() >= 0 && v.decimalValue().compareTo(java.math.BigDecimal.ONE) < 0
                    ? null : "tasa entre 0 y 0.99";
            case BOOL -> v.isBoolean() ? null : "valor sí/no";
            case DAYS -> v.isInt() && v.asInt() >= 0 && v.asInt() <= 180 ? null : "días entre 0 y 180";
            case AMOUNT -> v.isNumber() && v.decimalValue().signum() > 0 ? null : "importe mayor a cero";
            case BUCKETS -> validBuckets(v) ? null : "lista ascendente de días (1 a 365, máx. 8)";
        };
    }

    private static boolean validZone(String zone) {
        try {
            ZoneId.of(zone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validBuckets(JsonNode v) {
        if (!v.isArray() || v.isEmpty() || v.size() > 8) {
            return false;
        }
        int prev = 0;
        for (JsonNode n : v) {
            if (!n.isInt() || n.asInt() <= prev || n.asInt() > 365) {
                return false;
            }
            prev = n.asInt();
        }
        return true;
    }
}

