package com.nexora.corp.shared.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Envoltura de todas las respuestas: {@code {success, message, code, data, errors, requestId}} (contrato de HOTEL
 * ampliado con {@code code}, que el frontend usa para reaccionar sin depender del texto, p. ej. REAUTH_REQUIRED).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private String code;
    private T data;
    private Map<String, String> errors;
    private String requestId;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return ApiResponse.<T>builder().success(true).data(data).message(message).build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(message, null, null);
    }

    public static <T> ApiResponse<T> error(String message, String code, Map<String, String> errors) {
        return ApiResponse.<T>builder().success(false).message(message).code(code).errors(errors)
                .requestId(MDC.get("requestId")).build();
    }
}

