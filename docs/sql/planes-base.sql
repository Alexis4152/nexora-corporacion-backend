-- =====================================================================================
-- Carga de los planes base de la cotización (Cotizacion_Nexora_Systems.docx, 16-nov-2026) en TODOS los productos.
-- No es migración: es una carga de datos que se ejecuta a mano (DBeaver) cuando se necesita.
-- Idempotente: si el plan ya existe en un producto no se duplica, y no toca precios vigentes.
-- Código de cada plan = <CÓDIGO DEL PRODUCTO>_<SUFIJO>, p. ej. POS_SIN_EQUIPO, HOTEL_SIN_EQUIPO.
-- Para limitarlo a ciertos productos, cambia el filtro de "prods" (p. ej. WHERE code IN ('HOTEL','CITAS')).
-- =====================================================================================
BEGIN;

WITH prods AS (SELECT id, code FROM products WHERE active),
     sa    AS (SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
               WHERE r.code = 'SUPER_ADMIN' ORDER BY u.id LIMIT 1),
     base (suffix, name, description, features, highlighted, custom, sort_order) AS (VALUES
 ('PAGO_UNICO', 'Pago único con equipo',
  'Sistema local con equipo completo por $9,000 MXN en una sola exhibición. Opera solo en la computadora del cliente; al terminar la garantía el soporte se cobra por separado.',
  jsonb_build_array('Equipo completo: CPU, cajón de dinero, impresora de tickets y escáner',
                    'Una (1) instalación', 'Dos (2) días de capacitación', 'Garantía de 3 meses',
                    'Opera de forma local, solo en la computadora del cliente',
                    'Soporte técnico con costo adicional al terminar la garantía'),
  FALSE, TRUE, 1),
 ('EQUIPO_12M', 'Mensual con equipo · 12 meses',
  'Contrato a 12 meses: $999 MXN al mes ($299 del servicio + $700 del equipo). Al concluir el año la mensualidad baja a $299 (plan sin equipo) y el equipo queda en propiedad del cliente.',
  jsonb_build_array('Equipo incluido: CPU, cajón, impresora de tickets, escáner y generadora de códigos',
                    'El equipo pasa a ser propiedad del cliente al terminar de pagarlo',
                    'Tienda en línea para vender y mostrar sus productos',
                    'Sistema de apartados', 'Código QR con acceso directo a su página',
                    'Acceso multiplataforma: celular, tablet y computadora',
                    'Soporte técnico L-V de 9:00 a.m. a 6:00 p.m.'),
  TRUE, FALSE, 2),
 ('EQUIPO_FLEX', 'Mensual con equipo · menos de 12 meses',
  'Contrato menor a 12 meses: $1,089 MXN al mes ($369 del servicio + $700 del equipo). Al finalizar debe liquidarse el saldo restante del equipo; una vez cubierto, es propiedad del cliente.',
  jsonb_build_array('Equipo incluido: CPU, cajón, impresora de tickets, escáner y generadora de códigos',
                    'Al finalizar se liquida el saldo restante del equipo',
                    'Tienda en línea para vender y mostrar sus productos',
                    'Sistema de apartados', 'Código QR con acceso directo a su página',
                    'Acceso multiplataforma: celular, tablet y computadora',
                    'Soporte técnico L-V de 9:00 a.m. a 6:00 p.m.'),
  FALSE, FALSE, 3),
 ('SIN_EQUIPO', 'Mensual sin equipo',
  'Ideal si el cliente ya cuenta con su propio equipo. $299 MXN al mes.',
  jsonb_build_array('Tienda en línea para vender y mostrar sus productos',
                    'Sistema de apartados', 'Código QR con acceso directo a su página',
                    'Acceso multiplataforma: celular, tablet y computadora',
                    'Soporte técnico L-V de 9:00 a.m. a 6:00 p.m.'),
  FALSE, FALSE, 4))
INSERT INTO plans (product_id, code, name, description, features, highlighted, custom, active, sort_order, created_by, updated_by)
SELECT p.id, LEFT(p.code, 29 - length(b.suffix)) || '_' || b.suffix, b.name, b.description, b.features,
       b.highlighted, b.custom, TRUE, b.sort_order, sa.id, sa.id
FROM prods p CROSS JOIN base b LEFT JOIN sa ON TRUE
ON CONFLICT (product_id, code) DO NOTHING;

-- Precios mensuales con IVA incluido (el pago único no es recurrente: se cotiza como concepto de $9,000).
INSERT INTO plan_prices (plan_id, billing_period, amount, currency, tax_included)
SELECT pl.id, 'MONTHLY', v.amount, 'MXN', TRUE
FROM products p
JOIN (VALUES ('EQUIPO_12M', 999.00), ('EQUIPO_FLEX', 1089.00), ('SIN_EQUIPO', 299.00)) AS v(suffix, amount) ON TRUE
JOIN plans pl ON pl.product_id = p.id AND pl.code = LEFT(p.code, 29 - length(v.suffix)) || '_' || v.suffix
WHERE p.active
  AND NOT EXISTS (SELECT 1 FROM plan_prices pp WHERE pp.plan_id = pl.id AND pp.billing_period = 'MONTHLY' AND pp.valid_to IS NULL);

-- Revisión
SELECT p.code AS producto, pl.code AS plan, pl.name, pl.custom, pp.billing_period, pp.amount, pp.tax_included
FROM plans pl JOIN products p ON p.id = pl.product_id
LEFT JOIN plan_prices pp ON pp.plan_id = pl.id AND pp.valid_to IS NULL
WHERE pl.code ~ '_(PAGO_UNICO|EQUIPO_12M|EQUIPO_FLEX|SIN_EQUIPO)$'
ORDER BY p.sort_order, pl.sort_order;

COMMIT;
