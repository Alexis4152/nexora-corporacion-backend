# Nexora Corporación · Backend

Plataforma interna de Nexora Systems (CRM, suscripciones, cobranza, indicadores SaaS). Java 17, Spring Boot 3.2,
PostgreSQL, Flyway. Frontend en `../nexora-corporacion-frontend`. Documentación en `docs/`.

## Comandos

- `./mvnw spring-boot:run` — API en :8091 (lee `.env`; perfil dev)
- `./mvnw test` — unitarias + `PlatformIT` con Testcontainers (requiere Docker)

## Reglas que no se rompen

- No se modifican los proyectos HOTEL originales.
- Nada financiero se borra ni se edita en sitio: anular, revertir, ajustar con motivo (hay triggers que lo impiden).
- Los estados derivados (cobranza, relación del cliente) se calculan en vistas, no se guardan.
- Toda operación de negocio se audita (`AuditService`) y las sensibles exigen `ReauthGuard`.
- Productos y planes viven en el catálogo; jamás se codifican en el código.
- Datos demo solo en perfil dev (`DevDemoSeeder`); en prod la app no arranca con ellos.
- Migraciones aplicadas no se editan: nueva versión `V8__...`.
- Secretos solo en variables de entorno (`.env` está en `.gitignore`).
- Textos de la interfaz y mensajes de error en español.

## Contexto y estado (actualizado 18-oct-2026)

- Origen: `..\..\PROMPT MAESTRO — NEXORA CORPORACIÓN.txt`. Todo lo pedido está implementado; frontend en :5181, API en :8091,
  BD local `nexora_corporacion`.
- Usuarios: SUPER_ADMIN y usuarios demo por rol (admin@, finanzas@, ventas@, jorge.castillo@, soporte@, auditor@nexora.mx);
  las contraseñas están en `.env` (`APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD`, `APP_DEMO_PASSWORD`). Nunca copiarlas a archivos versionados.
- Pruebas al cierre: backend 32/32 (`PlatformIT` arranca el contenedor en bloque estático por el ciclo PER_CLASS), frontend 8/8.
- Rutas del frontend en español: `/metricas`, `/cobranza/cartera`, `/cobranza/pagos`, `/cobranza/cargos`, `/productos/:code`.
- Pendientes sugeridos: SMTP en Integraciones, PAC real para CFDI, conectar HOTEL a `/api/integration/v1/license`, MFA para
  superadmin y finanzas.
- Atribución por usuario (16-nov-2026, los socios son admins): cotizaciones con `owner_user_id` (filtro "Mis cotizaciones" por
  defecto), clientes con ejecutivo (por defecto quien los crea), suscripciones con `seller_user_id` (V7, editable y auditado) y
  reporte `team-performance` "Desempeño por ejecutivo". Todos los socios ven todo.
- El usuario escribe en español y hace commits solo cuando lo pide. Repos en GitHub (`Alexis4152/nexora-corporacion-*`,
  rama `main`) desplegados en Coolify: `corp.nexorasistemas.com.mx` y `api-corp.nexorasistemas.com.mx` (ver `docs/07-DESPLIEGUE.md`).
- `docs/sql/planes-base.sql`: carga manual (idempotente) de los planes de la cotización en todos los productos activos.
