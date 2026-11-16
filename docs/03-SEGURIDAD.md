# 03 · Seguridad

## Autenticación

- Contraseñas con BCrypt (costo 12). Política: 12–72 caracteres, mayúscula, minúscula, número y símbolo, sin palabras
  obvias ni el correo del usuario.
- **Access token** JWT HS256 de 15 min (`iss`, `aud`, `typ=access`, `sid` de sesión). Vive solo en memoria del navegador.
- **Refresh token** opaco, rotado en cada uso, guardado como hash; viaja en cookie `nxc_rt` httpOnly, `SameSite=Strict`,
  limitada a `/api/v1/auth`. Refresh y logout exigen `X-Requested-With` (un formulario ajeno no puede enviarla).
  Reutilizar un refresh ya consumido no da sesión.
- **Sesiones** en `user_sessions`: el usuario ve y cierra sus sesiones; desactivar un usuario o cambiar su contraseña
  revoca todas.
- **Bloqueo progresivo**: 5 intentos fallidos bloquean 15 min; cada bloqueo siguiente duplica la duración (máximo 4 h).
- **MFA TOTP** (RFC 6238) opcional por usuario, con QR, secreto cifrado AES-256-GCM, protección contra repetición del
  mismo código y 8 códigos de recuperación de un solo uso.
- **Recuperación de contraseña** por token de un solo uso (30 min), respuesta idéntica exista o no el correo.

## Autorización

Permisos granulares con código (`payments.reverse`, `customers.fiscal_read`, `reports.financial_read`…) agrupados en
roles editables. Roles base:

| Rol | Alcance |
|---|---|
| SUPER_ADMIN | todo, incluida la administración de roles y la configuración |
| ADMINISTRADOR | operación completa salvo roles y seguridad |
| FINANZAS | cobranza, pagos, gastos, reportes financieros, datos fiscales |
| VENTAS | CRM, clientes y suscripciones; sin importes financieros globales ni datos fiscales; ve solo sus prospectos salvo permiso `prospects.read_all` |
| SOPORTE | clientes (lectura) y tickets |
| AUDITOR | lectura de todo, incluida la bitácora; no modifica nada |

Cada endpoint valida con `@PreAuthorize`; el frontend solo oculta lo que el usuario no puede usar.

## Reautenticación

Revertir pagos, registrar devoluciones, anular o ajustar cargos, condonar adeudos, administrar usuarios y roles,
gestionar MFA y editar credenciales de integraciones exigen haber confirmado la contraseña en los últimos 10 minutos
(`ReauthGuard`). Un access token robado no basta.

## Protección adicional

- Límite de peticiones en base de datos (válido con varias instancias) para login, MFA, refresh, recuperación, reautenticación, registro de pagos, exportaciones y API de integración.
- Archivos: validación por firma de bytes (no por extensión), nombres aleatorios fuera del directorio web, descarga con
  permiso.
- Exportaciones CSV/Excel neutralizan fórmulas (`=`, `+`, `-`, `@`).
- Credenciales de integraciones cifradas; la API nunca las devuelve completas.
- Cabeceras de seguridad y CSP en nginx; CORS restringido al frontend.
- `ProductionReadinessValidator` impide arrancar en `prod` con secretos cortos o repetidos, cookie no segura, CORS local,
  datos demo o almacenamiento relativo.

## Bitácora

`audit_logs` registra quién, qué, cuándo, desde qué IP, antes y después, para cada operación de negocio y de seguridad
(incluidos accesos fallidos). Es inmutable a nivel de base de datos.

