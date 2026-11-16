-- =====================================================================================
-- V5: datos de referencia reales: permisos granulares, roles iniciales, catálogo inicial de productos
-- (los siete sistemas que comercializa Nexora), configuración por defecto y plantillas de cobranza.
-- No se siembran planes ni precios: son decisiones comerciales que captura el administrador.
-- =====================================================================================

INSERT INTO permissions (code, module, description, sensitive, sort_order) VALUES
 ('dashboard.read',            'dashboard',     'Ver el dashboard ejecutivo (indicadores no financieros)', FALSE, 10),
 ('products.read',             'products',      'Consultar productos y planes', FALSE, 20),
 ('products.manage',           'products',      'Crear y editar productos', FALSE, 21),
 ('plans.manage',              'products',      'Crear planes, modificar precios, cupones y promociones', TRUE, 22),
 ('customers.read',            'customers',     'Consultar clientes', FALSE, 30),
 ('customers.create',          'customers',     'Registrar clientes', FALSE, 31),
 ('customers.update',          'customers',     'Editar clientes, contactos, notas y etiquetas', FALSE, 32),
 ('customers.fiscal_read',     'customers',     'Ver datos fiscales (RFC, régimen, código postal fiscal)', TRUE, 33),
 ('customers.archive',         'customers',     'Archivar clientes', FALSE, 34),
 ('prospects.read',            'prospects',     'Consultar prospectos asignados', FALSE, 40),
 ('prospects.read_all',        'prospects',     'Consultar prospectos de todo el equipo', FALSE, 41),
 ('prospects.create',          'prospects',     'Registrar prospectos', FALSE, 42),
 ('prospects.update',          'prospects',     'Dar seguimiento y mover prospectos en el embudo', FALSE, 43),
 ('prospects.convert',         'prospects',     'Convertir prospectos ganados en clientes', FALSE, 44),
 ('subscriptions.read',        'subscriptions', 'Consultar suscripciones', FALSE, 50),
 ('subscriptions.create',      'subscriptions', 'Crear y activar suscripciones', FALSE, 51),
 ('subscriptions.update',      'subscriptions', 'Cambiar plan, periodicidad, descuentos y renovar', FALSE, 52),
 ('subscriptions.suspend',     'subscriptions', 'Suspender y reactivar suscripciones', FALSE, 53),
 ('subscriptions.cancel',      'subscriptions', 'Cancelar suscripciones', FALSE, 54),
 ('charges.read',              'billing',       'Consultar cargos y estados de cuenta', FALSE, 60),
 ('charges.create',            'billing',       'Generar cargos manuales', FALSE, 61),
 ('charges.void',              'billing',       'Anular cargos', TRUE, 62),
 ('charges.adjust',            'billing',       'Registrar ajustes y bonificaciones', TRUE, 63),
 ('payments.read',             'billing',       'Consultar pagos', FALSE, 70),
 ('payments.create',           'billing',       'Registrar pagos y aplicarlos', FALSE, 71),
 ('payments.confirm',          'billing',       'Confirmar, conciliar y rechazar pagos', FALSE, 72),
 ('payments.reverse',          'billing',       'Revertir pagos y desaplicarlos', TRUE, 73),
 ('payments.refund',           'billing',       'Registrar devoluciones', TRUE, 74),
 ('collections.read',          'collections',   'Consultar cuentas por cobrar y morosidad', FALSE, 80),
 ('collections.manage',        'collections',   'Registrar gestiones de cobranza', FALSE, 81),
 ('reports.commercial_read',   'reports',       'Reportes comerciales (prospectos, conversión, suscripciones)', FALSE, 90),
 ('reports.financial_read',    'reports',       'Información financiera: ingresos, MRR, cartera, rentabilidad', TRUE, 91),
 ('reports.export',            'reports',       'Exportar reportes (CSV, Excel, PDF)', TRUE, 92),
 ('expenses.read',             'expenses',      'Consultar gastos', FALSE, 100),
 ('expenses.manage',           'expenses',      'Registrar y anular gastos', FALSE, 101),
 ('support.read',              'support',       'Consultar tickets de soporte', FALSE, 110),
 ('support.manage',            'support',       'Crear, asignar y resolver tickets', FALSE, 111),
 ('quotes.read',               'quotes',        'Consultar cotizaciones', FALSE, 120),
 ('quotes.manage',             'quotes',        'Crear, enviar y versionar cotizaciones', FALSE, 121),
 ('documents.read',            'documents',     'Descargar documentos y comprobantes', FALSE, 130),
 ('documents.upload',          'documents',     'Adjuntar documentos', FALSE, 131),
 ('documents.delete',          'documents',     'Retirar documentos', TRUE, 132),
 ('calendar.read',             'calendar',      'Ver el calendario corporativo', FALSE, 140),
 ('users.read',                'users',         'Consultar usuarios', FALSE, 150),
 ('users.manage',              'users',         'Crear usuarios, asignar roles, restablecer accesos', TRUE, 151),
 ('roles.manage',              'users',         'Administrar roles y permisos', TRUE, 152),
 ('audit.read',                'audit',         'Consultar la bitácora de auditoría', TRUE, 160),
 ('settings.read',             'settings',      'Consultar la configuración corporativa', FALSE, 170),
 ('settings.manage',           'settings',      'Modificar configuración, plantillas y preferencias', TRUE, 171),
 ('integrations.manage',       'settings',      'Configurar integraciones y llaves de API', TRUE, 172);

INSERT INTO roles (code, name, description, system) VALUES
 ('SUPER_ADMIN',   'Super administrador', 'Acceso completo. Administra usuarios, seguridad, productos e integraciones.', TRUE),
 ('ADMINISTRADOR', 'Administrador',       'Operación administrativa completa según los permisos asignados.', TRUE),
 ('FINANZAS',      'Finanzas',            'Ingresos, pagos, cobranza, gastos y reportes financieros.', TRUE),
 ('VENTAS',        'Ventas',              'Prospectos, seguimiento comercial, cotizaciones y alta de clientes.', TRUE),
 ('SOPORTE',       'Soporte',             'Clientes autorizados, productos contratados y tickets.', TRUE),
 ('AUDITOR',       'Auditor',             'Solo lectura de registros y reportes autorizados.', TRUE);

-- SUPER_ADMIN recibe todos los permisos en código (PermissionResolver); aquí también para que la matriz lo muestre.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'SUPER_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code NOT IN ('users.manage', 'roles.manage', 'integrations.manage')
WHERE r.code = 'ADMINISTRADOR';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'dashboard.read', 'products.read', 'customers.read', 'customers.fiscal_read', 'subscriptions.read',
  'charges.read', 'charges.create', 'charges.void', 'charges.adjust',
  'payments.read', 'payments.create', 'payments.confirm', 'payments.reverse', 'payments.refund',
  'collections.read', 'collections.manage', 'reports.commercial_read', 'reports.financial_read', 'reports.export',
  'expenses.read', 'expenses.manage', 'quotes.read', 'documents.read', 'documents.upload', 'calendar.read')
WHERE r.code = 'FINANZAS';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'dashboard.read', 'products.read', 'customers.read', 'customers.create', 'customers.update',
  'prospects.read', 'prospects.create', 'prospects.update', 'prospects.convert',
  'subscriptions.read', 'subscriptions.create', 'reports.commercial_read', 'quotes.read', 'quotes.manage',
  'documents.read', 'documents.upload', 'calendar.read', 'support.read')
WHERE r.code = 'VENTAS';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'dashboard.read', 'products.read', 'customers.read', 'subscriptions.read', 'support.read', 'support.manage',
  'documents.read', 'documents.upload', 'calendar.read')
WHERE r.code = 'SOPORTE';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'dashboard.read', 'products.read', 'customers.read', 'customers.fiscal_read', 'prospects.read', 'prospects.read_all',
  'subscriptions.read', 'charges.read', 'payments.read', 'collections.read', 'reports.commercial_read',
  'reports.financial_read', 'reports.export', 'expenses.read', 'support.read', 'quotes.read', 'documents.read',
  'calendar.read', 'users.read', 'audit.read', 'settings.read')
WHERE r.code = 'AUDITOR';

-- Preferencias de alertas por rol (editable en Configuración → Alertas).
INSERT INTO notification_preferences (role_id, category, in_app, email)
SELECT r.id, c.category, TRUE, FALSE FROM roles r
CROSS JOIN (VALUES ('COLLECTION'), ('PROSPECTS'), ('SUBSCRIPTIONS'), ('PAYMENTS'), ('TRIALS'), ('CANCELLATIONS'),
                   ('SUPPORT'), ('SECURITY'), ('ADMIN')) AS c(category)
WHERE (r.code IN ('SUPER_ADMIN', 'ADMINISTRADOR'))
   OR (r.code = 'FINANZAS' AND c.category IN ('COLLECTION', 'PAYMENTS', 'SUBSCRIPTIONS', 'CANCELLATIONS', 'TRIALS', 'SECURITY'))
   OR (r.code = 'VENTAS' AND c.category IN ('PROSPECTS', 'TRIALS', 'SUBSCRIPTIONS', 'SECURITY'))
   OR (r.code = 'SOPORTE' AND c.category IN ('SUPPORT', 'SECURITY'))
   OR (r.code = 'AUDITOR' AND c.category IN ('SECURITY'));

-- ── Catálogo inicial (dinámico: se edita desde Productos) ───────────────────────────
INSERT INTO products (code, name, tagline, description, category, color, icon, sort_order) VALUES
 ('POS',          'NEXORA POS',          'Punto de venta',                   'Punto de venta para comercios: ventas, inventario, cortes de caja y clientes.', 'Comercio',             '#2563eb', 'store',          1),
 ('HOTEL',        'NEXORA HOTEL',        'Administración hotelera',          'Reservaciones, habitaciones, caja, check-in/out y portal de huéspedes.',       'Hospitalidad',         '#0d9488', 'bed-double',     2),
 ('CITAS',        'NEXORA CITAS',        'Agenda y reservación de citas',    'Agenda en línea, recordatorios y administración de citas por profesional.',    'Servicios',            '#7c3aed', 'calendar-clock', 3),
 ('RESTAURANTES', 'NEXORA RESTAURANTES', 'Administración de restaurantes',   'Comandas, mesas, cocina, inventario de insumos y cortes.',                     'Alimentos y bebidas',  '#ea580c', 'utensils',       4),
 ('TAXIS',        'NEXORA TAXIS',        'Taxis y flotillas',                'Unidades, conductores, viajes, liquidaciones y control de flotilla.',          'Movilidad',            '#ca8a04', 'car-taxi-front', 5),
 ('GYM',          'NEXORA GYM',          'Administración de gimnasios',      'Membresías, acceso, clases, pagos recurrentes de socios.',                     'Bienestar',            '#dc2626', 'dumbbell',       6),
 ('ECOMMERCE',    'NEXORA ECOMMERCE',    'Comercio electrónico',             'Tienda en línea, catálogo, pedidos, pagos y envíos.',                          'Comercio electrónico', '#db2777', 'shopping-bag',   7);

-- ── Configuración corporativa por defecto ────────────────────────────────────────────
INSERT INTO system_settings (setting_key, value, category) VALUES
 ('company.name',                 '"Nexora Systems"',          'company'),
 ('company.legalName',            '""',                        'company'),
 ('company.email',                '""',                        'company'),
 ('company.phone',                '""',                        'company'),
 ('company.website',              '""',                        'company'),
 ('company.primaryColor',         '"#5b5bf7"',                 'company'),
 ('company.accentColor',          '"#14b8a6"',                 'company'),
 ('business.timezone',            '"America/Mexico_City"',     'business'),
 ('finance.currency',             '"MXN"',                     'finance'),
 ('finance.taxRate',              '0.16',                      'finance'),
 ('finance.pricesIncludeTax',     'false',                     'finance'),
 ('billing.defaultGraceDays',     '5',                         'billing'),
 ('billing.defaultTrialDays',     '14',                        'billing'),
 ('billing.chargeLeadDays',       '5',                         'billing'),
 ('billing.dueSoonDays',          '5',                         'billing'),
 ('collections.agingBuckets',     '[7, 15, 30, 60]',           'collections'),
 ('collections.reminderBeforeDays', '3',                       'collections'),
 ('collections.reminderOnDue',    'true',                      'collections'),
 ('collections.reminderAfterDays', '[3, 10]',                  'collections'),
 ('collections.customerEmails',   'false',                     'collections'),
 ('alerts.trialEndingDays',       '3',                         'alerts'),
 ('alerts.prospectStaleDays',     '7',                         'alerts'),
 ('alerts.largePaymentAmount',    '20000',                     'alerts'),
 ('alerts.renewalDays',           '7',                         'alerts');

INSERT INTO expense_categories (name, acquisition_cost) VALUES
 ('Hosting', FALSE), ('Dominios', FALSE), ('Servidores', FALSE), ('Licencias', FALSE), ('Servicios de terceros', FALSE),
 ('Publicidad', TRUE), ('Comisiones de venta', TRUE), ('Infraestructura', FALSE), ('Gastos operativos', FALSE),
 ('Soporte', FALSE), ('Otros gastos', FALSE);

INSERT INTO tags (name, color) VALUES
 ('Cliente VIP', '#a855f7'), ('Cliente estratégico', '#2563eb'), ('Requiere seguimiento', '#f59e0b'),
 ('Riesgo de cancelación', '#ef4444'), ('Cuenta corporativa', '#0ea5e9'), ('Renovación especial', '#10b981');

-- Plantillas de cobranza. Variables: {{cliente}} {{producto}} {{folio}} {{importe}} {{vencimiento}} {{dias}} {{empresa}}
INSERT INTO notification_templates (code, name, subject, body) VALUES
 ('PAYMENT_REMINDER', 'Aviso previo al vencimiento', 'Tu pago de {{producto}} vence el {{vencimiento}}',
  '<p>Hola {{cliente}},</p><p>Te recordamos que el cargo <b>{{folio}}</b> de <b>{{producto}}</b> por <b>{{importe}}</b> vence el <b>{{vencimiento}}</b>.</p><p>Gracias por tu preferencia.<br>{{empresa}}</p>'),
 ('PAYMENT_DUE_TODAY', 'Aviso el día del vencimiento', 'Hoy vence tu pago de {{producto}}',
  '<p>Hola {{cliente}},</p><p>Hoy vence el cargo <b>{{folio}}</b> de <b>{{producto}}</b> por <b>{{importe}}</b>.</p><p>Si ya realizaste el pago, ignora este mensaje.<br>{{empresa}}</p>'),
 ('PAYMENT_OVERDUE', 'Recordatorio por atraso', 'Tienes un pago pendiente de {{producto}}',
  '<p>Hola {{cliente}},</p><p>El cargo <b>{{folio}}</b> de <b>{{producto}}</b> por <b>{{importe}}</b> tiene <b>{{dias}} días</b> de atraso.</p><p>Por favor regulariza tu pago para evitar la suspensión del servicio.<br>{{empresa}}</p>'),
 ('GRACE_ENDING', 'Aviso de terminación de gracia', 'Último aviso: tu periodo de gracia de {{producto}} termina',
  '<p>Hola {{cliente}},</p><p>Tu periodo de gracia para el cargo <b>{{folio}}</b> ({{importe}}) termina hoy. Después de esta fecha el servicio puede suspenderse.</p><p>{{empresa}}</p>'),
 ('SUSPENSION_NOTICE', 'Notificación de suspensión', 'Tu servicio {{producto}} fue suspendido',
  '<p>Hola {{cliente}},</p><p>Tu suscripción a <b>{{producto}}</b> fue suspendida por falta de pago. Al regularizar tu saldo la reactivaremos.</p><p>{{empresa}}</p>'),
 ('REGULARIZATION', 'Recordatorio de regularización', 'Regulariza tu cuenta de {{producto}}',
  '<p>Hola {{cliente}},</p><p>Tu cuenta de <b>{{producto}}</b> presenta un saldo pendiente de <b>{{importe}}</b>. Contáctanos para ayudarte a regularizarla.</p><p>{{empresa}}</p>');

INSERT INTO integration_configs (code, enabled, config) VALUES
 ('SMTP', FALSE, '{"host": "", "port": 587, "username": "", "from": "", "starttls": true}'::jsonb),
 ('WHATSAPP', FALSE, '{}'::jsonb),
 ('PAC_CFDI', FALSE, '{}'::jsonb),
 ('PAYMENT_GATEWAY', FALSE, '{}'::jsonb);
