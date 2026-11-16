package com.nexora.corp.shared.error;

/** El recurso cambió o ya existe (duplicado, versión desactualizada, operación ya realizada). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

