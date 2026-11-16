-- =====================================================================================
-- V4: notificaciones y entregas, gastos, soporte, cotizaciones, documentos y llaves de integración.
-- =====================================================================================

-- ── Centro de alertas (por usuario). dedupe_key hace idempotente la generación ──────
CREATE TABLE notifications (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category     VARCHAR(20)   NOT NULL,
    type         VARCHAR(40)   NOT NULL,
    severity     VARCHAR(10)   NOT NULL DEFAULT 'INFO',
    title        VARCHAR(200)  NOT NULL,
    body         VARCHAR(600),
    link         VARCHAR(200),
    entity_type  VARCHAR(40),
    entity_id    BIGINT,
    dedupe_key   VARCHAR(160)  NOT NULL,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_notifications_dedupe UNIQUE (user_id, dedupe_key),
    CONSTRAINT ck_notifications_category CHECK (category IN ('COLLECTION', 'PROSPECTS', 'SUBSCRIPTIONS', 'PAYMENTS', 'TRIALS',
        'CANCELLATIONS', 'SUPPORT', 'SECURITY', 'ADMIN')),
    CONSTRAINT ck_notifications_severity CHECK (severity IN ('INFO', 'SUCCESS', 'WARNING', 'CRITICAL'))
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Preferencias por rol: qué categorías recibe cada rol en su centro de alertas y por correo.
CREATE TABLE notification_preferences (
    role_id   BIGINT       NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    category  VARCHAR(20)  NOT NULL,
    in_app    BOOLEAN      NOT NULL DEFAULT TRUE,
    email     BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_id, category)
);

CREATE TABLE notification_templates (
    code        VARCHAR(40)   PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    channel     VARCHAR(10)   NOT NULL DEFAULT 'EMAIL',
    subject     VARCHAR(200)  NOT NULL,
    body        TEXT          NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by  BIGINT,
    version     INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_notification_templates_channel CHECK (channel IN ('EMAIL', 'WHATSAPP'))
);

-- Bandeja de salida: cada intento real queda registrado (nunca se simulan envíos).
CREATE TABLE notification_deliveries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel        VARCHAR(10)   NOT NULL,
    template_code  VARCHAR(40)   REFERENCES notification_templates(code),
    recipient      VARCHAR(150)  NOT NULL,
    subject        VARCHAR(200)  NOT NULL,
    body           TEXT          NOT NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    customer_id    BIGINT        REFERENCES customers(id),
    entity_type    VARCHAR(40),
    entity_id      BIGINT,
    dedupe_key     VARCHAR(160)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_notification_deliveries_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_notification_deliveries_channel CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
);
CREATE INDEX idx_notification_deliveries_pending ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notification_deliveries_customer ON notification_deliveries (customer_id, created_at DESC);

-- ── Gastos ───────────────────────────────────────────────────────────────────────────
CREATE TABLE expense_categories (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(60)  NOT NULL,
    -- Costo de adquisición de clientes (publicidad, comisiones de venta): base del CAC.
    acquisition_cost  BOOLEAN      NOT NULL DEFAULT FALSE,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_expense_categories_name UNIQUE (name)
);

CREATE TABLE expenses (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    concept        VARCHAR(200)   NOT NULL,
    amount         NUMERIC(14,2)  NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    expense_date   DATE           NOT NULL,
    category_id    BIGINT         NOT NULL REFERENCES expense_categories(id),
    product_id     BIGINT         REFERENCES products(id),
    supplier       VARCHAR(150),
    periodicity    VARCHAR(12)    NOT NULL DEFAULT 'ONE_TIME',
    notes          VARCHAR(1000),
    voided_at      TIMESTAMPTZ,
    voided_by      BIGINT,
    void_reason    VARCHAR(300),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     BIGINT,
    updated_by     BIGINT,
    version        INT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_expenses_amount CHECK (amount > 0),
    CONSTRAINT ck_expenses_periodicity CHECK (periodicity IN ('ONE_TIME', 'MONTHLY', 'QUARTERLY', 'ANNUAL'))
);
CREATE INDEX idx_expenses_date ON expenses (expense_date);
CREATE INDEX idx_expenses_product ON expenses (product_id);
CREATE TRIGGER trg_expenses_no_delete BEFORE DELETE ON expenses FOR EACH ROW EXECUTE FUNCTION nx_forbid_delete();

-- ── Soporte ──────────────────────────────────────────────────────────────────────────
CREATE SEQUENCE ticket_folio_seq START 1;
CREATE TABLE support_tickets (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio               VARCHAR(20)    NOT NULL,
    customer_id         BIGINT         NOT NULL REFERENCES customers(id),
    product_id          BIGINT         REFERENCES products(id),
    subject             VARCHAR(200)   NOT NULL,
    description         VARCHAR(5000)  NOT NULL,
    category            VARCHAR(20)    NOT NULL,
    priority            VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM',
    status              VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    assignee_id         BIGINT         REFERENCES users(id),
    resolution          VARCHAR(3000),
    first_response_at   TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          BIGINT,
    version             INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_support_tickets_folio UNIQUE (folio),
    CONSTRAINT ck_support_tickets_category CHECK (category IN ('INCIDENT', 'QUESTION', 'REQUEST', 'BILLING', 'TRAINING', 'BUG')),
    CONSTRAINT ck_support_tickets_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_support_tickets_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_support_tickets_resolution CHECK (status NOT IN ('RESOLVED', 'CLOSED') OR resolution IS NOT NULL)
);
CREATE INDEX idx_support_tickets_customer ON support_tickets (customer_id, created_at DESC);
CREATE INDEX idx_support_tickets_status ON support_tickets (status);
CREATE INDEX idx_support_tickets_assignee ON support_tickets (assignee_id);

CREATE TABLE support_comments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id   BIGINT         NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    body        VARCHAR(5000)  NOT NULL,
    internal    BOOLEAN        NOT NULL DEFAULT TRUE,
    -- Evento automático (cambio de estado, asignación) o comentario de una persona.
    system      BOOLEAN        NOT NULL DEFAULT FALSE,
    created_by  BIGINT         REFERENCES users(id),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_comments_ticket ON support_comments (ticket_id, created_at);

-- ── Cotizaciones (versionadas: cada versión es inmutable una vez enviada) ───────────
CREATE SEQUENCE quote_folio_seq START 1;
CREATE TABLE quotes (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio          VARCHAR(20)    NOT NULL,
    version_no     INT            NOT NULL DEFAULT 1,
    customer_id    BIGINT         REFERENCES customers(id),
    prospect_id    BIGINT         REFERENCES prospects(id),
    title          VARCHAR(200)   NOT NULL,
    status         VARCHAR(10)    NOT NULL DEFAULT 'DRAFT',
    valid_until    DATE           NOT NULL,
    conditions     VARCHAR(4000),
    currency       VARCHAR(3)     NOT NULL,
    tax_rate       NUMERIC(6,4)   NOT NULL,
    subtotal       NUMERIC(14,2)  NOT NULL DEFAULT 0,
    discount       NUMERIC(14,2)  NOT NULL DEFAULT 0,
    tax            NUMERIC(14,2)  NOT NULL DEFAULT 0,
    total          NUMERIC(14,2)  NOT NULL DEFAULT 0,
    owner_user_id  BIGINT         REFERENCES users(id),
    sent_at        TIMESTAMPTZ,
    decided_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     BIGINT,
    version        INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_quotes_folio_version UNIQUE (folio, version_no),
    CONSTRAINT ck_quotes_status CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_quotes_target CHECK (customer_id IS NOT NULL OR prospect_id IS NOT NULL),
    CONSTRAINT ck_quotes_totals CHECK (total = subtotal - discount + tax AND discount <= subtotal)
);
CREATE INDEX idx_quotes_customer ON quotes (customer_id);
CREATE INDEX idx_quotes_prospect ON quotes (prospect_id);

CREATE TABLE quote_items (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quote_id        BIGINT         NOT NULL REFERENCES quotes(id) ON DELETE CASCADE,
    product_id      BIGINT         REFERENCES products(id),
    plan_id         BIGINT         REFERENCES plans(id),
    billing_period  VARCHAR(12),
    description     VARCHAR(300)   NOT NULL,
    quantity        INT            NOT NULL,
    unit_price      NUMERIC(14,2)  NOT NULL,
    discount        NUMERIC(14,2)  NOT NULL DEFAULT 0,
    line_total      NUMERIC(14,2)  NOT NULL,
    sort_order      INT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_quote_items_values CHECK (quantity > 0 AND unit_price >= 0 AND discount >= 0
        AND line_total = quantity * unit_price - discount AND line_total >= 0)
);
CREATE INDEX idx_quote_items_quote ON quote_items (quote_id);

-- ── Documentos y comprobantes (el archivo vive en APP_STORAGE_DIR; aquí los metadatos) ─
CREATE TABLE documents (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type     VARCHAR(20)   NOT NULL,
    owner_id       BIGINT        NOT NULL,
    category       VARCHAR(20)   NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    original_name  VARCHAR(255)  NOT NULL,
    mime_type      VARCHAR(100)  NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    sha256         VARCHAR(64)   NOT NULL,
    storage_key    VARCHAR(200)  NOT NULL,
    uploaded_by    BIGINT        REFERENCES users(id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    deleted_by     BIGINT,
    CONSTRAINT ux_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_documents_owner CHECK (owner_type IN ('CUSTOMER', 'SUBSCRIPTION', 'PAYMENT', 'EXPENSE', 'TICKET', 'QUOTE', 'PROSPECT')),
    CONSTRAINT ck_documents_category CHECK (category IN ('CONTRACT', 'RECEIPT', 'QUOTE', 'EVIDENCE', 'INVOICE', 'COMMERCIAL', 'OTHER')),
    CONSTRAINT ck_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_documents_mime CHECK (mime_type IN ('application/pdf', 'image/png', 'image/jpeg', 'image/webp',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'text/xml'))
);
CREATE INDEX idx_documents_owner ON documents (owner_type, owner_id) WHERE deleted_at IS NULL;
ALTER TABLE products ADD CONSTRAINT fk_products_logo FOREIGN KEY (logo_file_id) REFERENCES documents(id);

-- ── Llaves de API para que los productos Nexora consulten su estado de licencia ─────
CREATE TABLE product_api_keys (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id    BIGINT        NOT NULL REFERENCES products(id),
    name          VARCHAR(80)   NOT NULL,
    key_prefix    VARCHAR(12)   NOT NULL,
    key_hash      VARCHAR(64)   NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    revoked_at    TIMESTAMPTZ,
    revoked_by    BIGINT,
    CONSTRAINT ux_product_api_keys_hash UNIQUE (key_hash)
);
CREATE INDEX idx_product_api_keys_product ON product_api_keys (product_id);
