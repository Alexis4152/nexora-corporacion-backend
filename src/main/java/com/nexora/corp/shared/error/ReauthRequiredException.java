package com.nexora.corp.shared.error;

/** Operación sensible sin reautenticación reciente: el frontend pide la contraseña y reintenta. */
public class ReauthRequiredException extends RuntimeException {

    public ReauthRequiredException() {
        super("Confirma tu contraseña para continuar con esta operación");
    }
}

