# 04 · Reglas financieras

## Estados de una suscripción (ciclo de vida)

| Estado | Significado | ¿Aporta MRR? |
|---|---|---|
| `TRIAL` | prueba gratuita | no |
| `PENDING_ACTIVATION` | contratada con inicio futuro | no |
| `ACTIVE` | vigente y al corriente | sí |
| `GRACE` | vigente con adeudo vencido, dentro de los días de gracia | sí |
| `CANCEL_SCHEDULED` | se cancelará al terminar el periodo | sí |
| `SUSPENDED` | servicio suspendido | no |
| `CANCELLED` / `EXPIRED` | terminada / prueba no convertida | no |

El **estado de cobranza** (al corriente, por vencer, pendiente, pago parcial, vencido, en gestión de cobranza) es independiente y se calcula
de los cargos (`subscription_collection`).

## Reglas clave

1. **Una prueba gratuita nunca se cobra sola.** Al terminar expira; convertirla es una acción explícita.
2. **Periodos y fin de mes:** el día ancla es el de inicio. Si un mes no tiene ese día se usa el último día del mes y
   se regresa al ancla en cuanto existe (31 ene → 28 feb → 31 mar).
3. **Cargos:** se emite el cargo del periodo al activarse y el del siguiente `billing.chargeLeadDays` días antes. El
   índice único por periodo hace imposible duplicarlo aunque la tarea se repita.
4. **IVA:** los precios se capturan sin IVA (o con IVA incluido si el precio lo indica); cada cargo guarda subtotal, IVA
   y total. Tasa configurable (`finance.taxRate`, 16 %).
5. **Descuentos y cupones:** porcentaje o importe, por un número de ciclos o indefinido. El descuento nunca supera el
   precio.
6. **Cambio de plan:**
   - subida inmediata → cargo de prorrateo = (nuevo − anterior) × días restantes ÷ días del periodo;
   - bajadas y cambios de periodicidad → al final del periodo (sin reembolsos ni saldos negativos);
   - en prueba o pendiente → cambia sin cargo.
7. **Cancelación:** inmediata o al final del periodo. Inmediata anula cargos futuros sin pagos; condonar el saldo vencido
   requiere permiso `charges.void` y reautenticación.
8. **Gracia y suspensión:** un cargo vencido pasa la suscripción a `GRACE`. Al superar los días de gracia solo se
   suspende automáticamente si el producto tiene política `AUTO_AFTER_GRACE`; si es `MANUAL` (por omisión) se avisa.
9. **Una suscripción suspendida no se reactiva sola** aunque el cliente pague: se avisa a cobranza para que alguien la
   reactive (y el producto vuelva a habilitarse) de forma consciente.
10. **Reactivación:** suspendida dentro de su periodo continúa el periodo; cancelada o suspendida vencida abre un periodo
    nuevo con su cargo.

## Pagos

- Estados: `CAPTURED` (registrado), `CONFIRMED` (el dinero llegó), `RECONCILED` (cuadrado con el banco), `REJECTED`,
  `REVERSED`. Solo cuenta como cobrado lo confirmado o conciliado.
- Aplicación automática a los cargos más antiguos, manual por cargo o sin aplicar (saldo a favor).
- Defensas contra duplicados: llave de idempotencia obligatoria, bloqueo de la fila del cliente, referencia bancaria
  única por método mientras el pago esté vigente y CHECK de saldo en el cargo.
- **Nada se borra:** rechazar o revertir un pago desaplica sus aplicaciones con fecha, usuario y motivo; el cargo recupera
  su saldo. Las devoluciones solo salen del saldo a favor.

## Antigüedad de saldos

Tramos configurables (`collections.agingBuckets`, por omisión 1–7, 8–15, 16–30, 31–60 y más de 60 días). Cada tramo
muestra importe, cargos y clientes.

## Integración con los productos

`GET /api/integration/v1/license?customer=CL-xxxxxx` con `X-Api-Key` del producto devuelve el estado de la suscripción
(activa, en gracia, suspendida…). Nexora Corporación **no** apaga sistemas por su cuenta: cada producto decide qué hacer
con ese estado.

