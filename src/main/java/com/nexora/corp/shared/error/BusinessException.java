package com.nexora.corp.shared.error;

/** Violación de una regla de negocio. El mensaje se muestra tal cual: claro y sin detalles técnicos. */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        this(message, null);
    }

    public BusinessException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

