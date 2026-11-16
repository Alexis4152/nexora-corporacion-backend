package com.nexora.corp.shared.util;

import java.util.regex.Pattern;

/** Patrones compartidos de validación (espejo en frontend: src/utils/validators.js). */
public final class Validation {

    public static final String PHONE = "^\\d{10}$";
    public static final String RFC = "^[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}$";
    public static final String ZIP = "^\\d{5}$";
    public static final String HEX = "^#[0-9A-Fa-f]{6}$";
    public static final String CODE = "^[A-Z][A-Z0-9_]{1,29}$";
    public static final String EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";
    public static final String URL = "^https?://[^\\s]{3,250}$";

    private static final Pattern PASSWORD_UPPER = Pattern.compile("\\p{Lu}");
    private static final Pattern PASSWORD_LOWER = Pattern.compile("\\p{Ll}");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile("\\d");
    private static final Pattern PASSWORD_SYMBOL = Pattern.compile("[^\\p{L}\\d]");

    private Validation() {
    }

    /**
     * Política de contraseñas: 12–72 caracteres (límite de BCrypt), mayúscula, minúscula, número y símbolo, sin
     * contener el correo ni palabras obvias. Devuelve el problema o null.
     */
    public static String passwordProblem(String password, String email) {
        if (password == null || password.length() < 12) {
            return "La contraseña debe tener al menos 12 caracteres";
        }
        if (password.length() > 72) {
            return "La contraseña no puede tener más de 72 caracteres";
        }
        if (!PASSWORD_UPPER.matcher(password).find() || !PASSWORD_LOWER.matcher(password).find()
                || !PASSWORD_DIGIT.matcher(password).find() || !PASSWORD_SYMBOL.matcher(password).find()) {
            return "Incluye mayúscula, minúscula, número y símbolo";
        }
        String lower = password.toLowerCase();
        for (String weak : new String[]{"password", "contrasena", "contraseña", "nexora", "qwerty", "123456", "admin"}) {
            if (lower.contains(weak)) {
                return "La contraseña contiene una palabra demasiado común";
            }
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase();
            if (local.length() >= 4 && lower.contains(local)) {
                return "La contraseña no debe contener tu correo";
            }
        }
        return null;
    }
}
