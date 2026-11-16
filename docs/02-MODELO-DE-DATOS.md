# 02 · Modelo de datos

Migraciones Flyway en `src/main/resources/db/migration`. Nunca se edita una migración aplicada: los cambios van en una
nueva versión.

| Versión | Contenido |
|---|---|
| V1 | identidad (usuarios, roles, permisos, sesiones, MFA, tokens), límites de peticiones, auditoría, parámetros, integraciones, `job_runs` |
| V2 | catálogo (productos, planes, precios versionados, cupones), clientes, contactos, notas, etiquetas, prospectos, actividades |
| V3 | suscripciones, `subscription_events`, cargos, ajustes, pagos, aplicaciones, devoluciones, gestiones de cobranza |
| V4 | notificaciones, plantillas, entregas, gastos, tickets, cotizaciones, documentos, llaves de API de productos |
| V5 | datos de referencia: permisos, roles, productos de Nexora, parámetros, categorías de gasto, plantillas |
| V6 | modelos de lectura: `nx_today()`, `charge_balances`, `subscription_collection`, `customer_overview`, `payment_balances` |

## Relaciones principales

```
products 1─* plans 1─* plan_prices (versionadas: valid_from/valid_to, una vigente por periodicidad)
customers 1─* subscriptions *─1 plans
subscriptions 1─* subscription_events (inmutable: mrr_before, mrr_after, movement)
subscriptions 1─* charges 1─* payment_allocations *─1 payments 1─* payment_refunds
customers 1─* contacts, notes, customer_tags, support_tickets, quotes, documents, collection_actions
prospects 1─* prospect_interests, sales_activities   ·   customers.source_prospect_id → prospects
```

## Integridad garantizada por la base de datos

| Regla | Mecanismo |
|---|---|
| Un solo cargo de suscripción por periodo | índice único `(subscription_id, period_start)` para cargos `SUBSCRIPTION` no anulados |
| Sin pagos duplicados por referencia | índice único `(method, upper(reference))` mientras el pago esté vigente |
| Reintentos seguros | `idempotency_key` única en pagos y cargos |
| Un cargo nunca se paga de más | `CHECK (paid_amount + adjusted_amount <= total)` |
| Nada financiero se borra | triggers `nx_forbid_delete` en cargos, pagos, gastos y precios |
| Historia inalterable | triggers que impiden `UPDATE/DELETE` en `audit_logs`, `subscription_events`, ajustes y devoluciones |
| Aplicaciones solo se revierten | trigger en `payment_allocations`: solo se puede llenar `reversed_at` y su motivo |
| Clientes únicos | índices únicos por RFC y correo (normalizados) |
| Un precio vigente por plan y periodicidad | índice único parcial `WHERE valid_to IS NULL` |

## Estados derivados (vistas)

- **`charge_balances`**: saldo = total − pagado − ajustes; días de atraso contra `nx_today()`.
- **`subscription_collection`**: estado de cobranza (`CURRENT`, `DUE_SOON`, `PENDING`, `PARTIAL`, `OVERDUE`, `IN_COLLECTION`) independiente
  del estado del ciclo de vida.
- **`customer_overview`**: relación comercial (prospecto, en prueba, activo, moroso, inactivo, perdido), MRR, saldo y
  vencido por cliente.

## Preparado para CFDI (sin timbrado simulado)

Clientes con régimen fiscal, código postal, RFC y razón social; cargos con subtotal, IVA y total separados; la integración
`PAC_CFDI` existe en *Integraciones* pero está deshabilitada hasta configurar un PAC real. No se generan folios fiscales
ficticios.

