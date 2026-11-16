# 01 · Arquitectura

## Visión general

Monolito modular: un solo despliegue, módulos con fronteras claras dentro de `com.nexora.corp`. Se eligió sobre
microservicios porque el dominio comparte transacciones (convertir un prospecto crea cliente, suscripción, cargo y pago
de forma atómica) y el equipo es pequeño; si un módulo crece se puede extraer sin rediseñar el modelo.

```
Navegador (React)  ──HTTPS──▶  API REST /api/v1  ──▶  Servicios (reglas, @Transactional)  ──▶  PostgreSQL
                                     │                         │                              ▲
Productos Nexora ──X-Api-Key──▶ /api/integration/v1     eventos de aplicación ──▶ notificaciones / bitácora
```

## Módulos

| Paquete | Responsabilidad |
|---|---|
| `identity` | usuarios, roles, permisos, sesiones, MFA, recuperación de contraseña |
| `settings` | parámetros del negocio editables (IVA, zona horaria, días de gracia, tramos de antigüedad, marca) |
| `catalog` | productos, planes, precios versionados, cupones — nada de productos codificado en el código |
| `customers` | clientes, contactos, notas, etiquetas, datos fiscales listos para CFDI |
| `crm` | prospectos, embudo Kanban, actividades comerciales, conversión transaccional |
| `subscriptions` | motor de ciclo de vida y libro inmutable de eventos con efecto en MRR |
| `billing` | cargos, pagos, aplicaciones, devoluciones, reversos, cobranza y antigüedad de saldos |
| `notifications` | alertas internas, preferencias, plantillas, bandeja de salida con reintentos |
| `analytics` | dashboard, indicadores SaaS, cohortes, reportes, salud del cliente, vista 360, calendario, búsqueda |
| `support`, `quotes`, `expenses`, `documents` | tickets, cotizaciones con PDF, gastos y rentabilidad, archivos |
| `integrations` | credenciales cifradas de servicios externos y API de licencias para los productos |
| `audit` | consulta de la bitácora inmutable |
| `shared` | seguridad, errores, exportación CSV/Excel/PDF, tareas, almacenamiento, utilidades de dinero |

## Capas y convenciones

- **Escritura:** controlador → servicio (`@Transactional`, valida reglas, bloquea filas) → repositorio JPA.
- **Lectura:** consultas SQL explícitas con `NamedParameterJdbcTemplate` sobre vistas (`charge_balances`,
  `subscription_collection`, `customer_overview`). Los estados derivados se **calculan**, no se guardan.
- **Respuesta uniforme:** `{success, message, code, data, errors, requestId}`. `code` permite al frontend reaccionar
  sin depender del texto (`REAUTH_REQUIRED`, `DUPLICATE_SUBSCRIPTION`…).
- **Paginación y orden** por lista blanca (`PageQuery`): nunca se concatena texto del usuario al SQL.
- **Dinero:** `BigDecimal` con redondeo `HALF_UP` a 2 decimales (`Money`); MRR a 4 decimales.
- **Fechas de negocio:** `SettingsService.today()` y `nx_today()` en SQL usan la zona horaria configurada, no la del
  servidor.

## Tareas programadas

`SubscriptionJobs.runCycle()` corre al arrancar y cada hora (minuto 5): activaciones, fin de pruebas, renovaciones,
emisión anticipada de cargos y reglas de cobranza. `JobRunner` garantiza:

- un candado `pg_try_advisory_lock` por tarea (seguro con varias instancias);
- una transacción por elemento (un error no revierte a los demás);
- idempotencia por restricciones únicas (repetir la tarea no duplica cargos);
- registro en `job_runs` (visible en *Administración → Tareas*).

Las alertas (`AlertJobs`) y la bandeja de salida de correo (`DeliveryService`, `FOR UPDATE SKIP LOCKED`) siguen el
mismo patrón.

## Frontend

React 18 + Vite. El access token vive solo en memoria; el refresh viaja en cookie httpOnly. Un interceptor resuelve
renovaciones simultáneas con una sola petición y abre el diálogo de reautenticación cuando la API responde
`REAUTH_REQUIRED`. Los menús y acciones se muestran según permisos, pero la autorización real siempre está en la API.

