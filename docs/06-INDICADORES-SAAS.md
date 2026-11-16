# 06 · Indicadores SaaS

Todos se calculan sobre **MRR sin IVA**, normalizado a mes y con el descuento vigente. Pruebas gratuitas y
suscripciones suspendidas no aportan MRR. Las definiciones también se muestran en la pantalla *Indicadores*.

## Fuente: libro de eventos

Cada cambio de una suscripción guarda en `subscription_events` el MRR antes y después y un **movimiento** clasificado a
nivel **cliente** (no de suscripción):

| Movimiento | Condición (MRR del cliente antes → después) |
|---|---|
| NEW | 0 → >0 y el cliente nunca había tenido MRR |
| REACTIVATION | 0 → >0 y el cliente ya había tenido MRR |
| EXPANSION | >0 → mayor |
| CONTRACTION | >0 → menor, sin llegar a 0 |
| CHURN | >0 → 0 |

El MRR de una fecha pasada es la suma del `mrr_after` del último evento de cada suscripción hasta esa fecha: un mes
cerrado no cambia aunque hoy se modifique una suscripción. Ejemplo: un cliente con POS de 499 y Hotel de 899 aporta
1,398 de MRR; si cancela POS es CONTRACTION de 499, no churn.

## Fórmulas

| Indicador | Fórmula |
|---|---|
| MRR | Σ (precio − descuento) sin IVA ÷ meses del periodo, de suscripciones ACTIVE, GRACE y CANCEL_SCHEDULED |
| ARR | MRR × 12 (proyección del contrato vigente, no dinero cobrado) |
| Net New MRR | MRR final − MRR inicial = New + Expansion + Reactivation − Contraction − Churn |
| Churn de clientes | clientes con MRR al inicio del mes que terminan en 0 ÷ clientes con MRR al inicio |
| Churn de ingresos | (Churn + Contraction) ÷ MRR inicial |
| NRR | (MRR inicial + Expansion − Contraction − Churn) ÷ MRR inicial |
| GRR | (MRR inicial − Contraction − Churn) ÷ MRR inicial (≤ 100 %) |
| ARPA | MRR al cierre ÷ clientes con MRR al cierre |
| LTV | ARPA ÷ churn mensual promedio de clientes de los últimos 6 meses cerrados. Sin margen bruto; no se calcula si el churn es 0 |
| CAC | gastos de categorías marcadas como «costo de adquisición» de los últimos 3 meses ÷ clientes NEW en ese lapso. Solo si existen esos gastos |
| LTV/CAC | solo si ambos son calculables |

Cuando un indicador no es calculable la API devuelve `null` y el motivo; la pantalla lo explica en vez de mostrar 0.

## Dinero cobrado ≠ MRR

*Cobrado* = pagos confirmados o conciliados por fecha de recepción − devoluciones. *Facturado* = cargos emitidos no
anulados. La rentabilidad por producto usa cobrado − gastos asignados al producto.

## Salud del cliente

Regla explicable que parte de 100 y resta por: adeudo vencido (−15/−25/−35 según días), atrasos recurrentes, tickets
abiertos o urgentes, cancelaciones previas, suspensiones, cancelación programada y antigüedad menor a 3 meses; suma 5 por
más de 12 meses. Saludable ≥ 75, atención ≥ 50, riesgo < 50. Cada factor se muestra con su detalle.

