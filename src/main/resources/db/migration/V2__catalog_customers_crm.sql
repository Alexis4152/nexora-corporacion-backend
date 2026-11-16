-- =====================================================================================
-- V2: catálogo dinámico de productos y planes, clientes corporativos y CRM de prospectos.
-- =====================================================================================

-- ── Productos (catálogo dinámico; nunca listas fijas en código) ──────────────────────
CREATE TABLE products (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(30)   NOT NULL,
    name                VARCHAR(100)  NOT NULL,
    tagline             VARCHAR(150),
    description         VARCHAR(2000),
    category            VARCHAR(60),
    color               VARCHAR(7)    NOT NULL DEFAULT '#6366f1',
    icon                VARCHAR(40)   NOT NULL DEFAULT 'box',
    logo_file_id        BIGINT,
    marketing_url       VARCHAR(255),
    system_url          VARCHAR(255),
    default_trial_days  INT           NOT NULL DEFAULT 14,
    default_grace_days  INT           NOT NULL DEFAULT 5,
    renewal_policy      VARCHAR(20)   NOT NULL DEFAULT 'AUTO',
    -- MANUAL: al terminar la gracia solo se alerta. AUTO_AFTER_GRACE: la tarea suspende la suscripción interna.
    suspension_policy   VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    allowed_settings    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order          INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_by          BIGINT,
    version             INT           NOT NULL DEFAULT 0,
    CONSTRAINT ux_products_code UNIQUE (code),
    CONSTRAINT ck_products_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_products_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_products_days CHECK (default_trial_days BETWEEN 0 AND 180 AND default_grace_days BETWEEN 0 AND 60),
    CONSTRAINT ck_products_renewal CHECK (renewal_policy IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_products_suspension CHECK (suspension_policy IN ('MANUAL', 'AUTO_AFTER_GRACE'))
);

CREATE TABLE plans (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id        BIGINT        NOT NULL REFERENCES products(id),
    code              VARCHAR(30)   NOT NULL,
    name              VARCHAR(80)   NOT NULL,
    description       VARCHAR(1000),
    features          JSONB         NOT NULL DEFAULT '[]'::jsonb,
    usage_limits      JSONB         NOT NULL DEFAULT '{}'::jsonb,
    max_users         INT,
    max_branches      INT,
    trial_days        INT,
    highlighted       BOOLEAN       NOT NULL DEFAULT FALSE,
    custom            BOOLEAN       NOT NULL DEFAULT FALSE,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    available_from    DATE,
    available_until   DATE,
    sort_order        INT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_by        BIGINT,
    version           INT           NOT NULL DEFAULT 0,
    CONSTRAINT ux_plans_product_code UNIQUE (product_id, code),
    CONSTRAINT ck_plans_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_plans_limits CHECK ((max_users IS NULL OR max_users > 0) AND (max_branches IS NULL OR max_branches > 0)
        AND (trial_days IS NULL OR trial_days BETWEEN 0 AND 180)),
    CONSTRAINT ck_plans_dates CHECK (available_until IS NULL OR available_from IS NULL OR available_until >= available_from)
);
CREATE INDEX idx_plans_product ON plans (product_id);

-- Precio público por periodicidad. Cambiar un precio cierra la fila vigente (valid_to) y crea otra:
-- el histórico se conserva y las suscripciones guardan su propio precio contratado.
CREATE TABLE plan_prices (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id         BIGINT         NOT NULL REFERENCES plans(id),
    billing_period  VARCHAR(12)    NOT NULL,
    amount          NUMERIC(14,2)  NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    tax_included    BOOLEAN        NOT NULL DEFAULT FALSE,
    valid_from      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    created_by      BIGINT,
    CONSTRAINT ck_plan_prices_period CHECK (billing_period IN ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    CONSTRAINT ck_plan_prices_amount CHECK (amount >= 0),
    CONSTRAINT ck_plan_prices_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE UNIQUE INDEX ux_plan_prices_current ON plan_prices (plan_id, billing_period) WHERE valid_to IS NULL;
CREATE TRIGGER trg_plan_prices_no_delete BEFORE DELETE ON plan_prices FOR EACH ROW EXECUTE FUNCTION nx_forbid_delete();

CREATE TABLE coupons (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              VARCHAR(30)    NOT NULL,
    description       VARCHAR(200),
    discount_type     VARCHAR(10)    NOT NULL,
    discount_value    NUMERIC(14,2)  NOT NULL,
    -- Número de cargos a los que aplica; NULL = mientras dure la suscripción.
    duration_cycles   INT,
    product_id        BIGINT         REFERENCES products(id),
    valid_from        DATE,
    valid_until       DATE,
    max_redemptions   INT,
    redemptions       INT            NOT NULL DEFAULT 0,
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        BIGINT,
    version           INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_coupons_code UNIQUE (code),
    CONSTRAINT ck_coupons_code CHECK (code ~ '^[A-Z0-9_-]{3,30}$'),
    CONSTRAINT ck_coupons_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT ck_coupons_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    CONSTRAINT ck_coupons_redemptions CHECK (max_redemptions IS NULL OR redemptions <= max_redemptions)
);

-- ── Usuarios ↔ etiquetas ─────────────────────────────────────────────────────────────
CREATE TABLE tags (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(40)  NOT NULL,
    color       VARCHAR(7)   NOT NULL DEFAULT '#64748b',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_tags_name UNIQUE (name),
    CONSTRAINT ck_tags_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

-- ── Clientes corporativos (uno por empresa, contrate los productos que contrate) ─────
CREATE SEQUENCE customer_code_seq START 1001;
CREATE TABLE customers (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               VARCHAR(20)   NOT NULL,
    person_type        VARCHAR(10)   NOT NULL,
    trade_name         VARCHAR(150)  NOT NULL,
    legal_name         VARCHAR(250),
    contact_name       VARCHAR(150)  NOT NULL,
    rfc                VARCHAR(13),
    tax_regime         VARCHAR(5),
    tax_zip            VARCHAR(5),
    email              VARCHAR(150)  NOT NULL,
    phone              VARCHAR(20),
    whatsapp           VARCHAR(20),
    address            VARCHAR(300),
    city               VARCHAR(100),
    state              VARCHAR(100),
    country            VARCHAR(2)    NOT NULL DEFAULT 'MX',
    industry           VARCHAR(80),
    owner_user_id      BIGINT        REFERENCES users(id),
    source_prospect_id BIGINT,
    notes              VARCHAR(2000),
    archived           BOOLEAN       NOT NULL DEFAULT FALSE,
    customer_since     DATE          NOT NULL DEFAULT CURRENT_DATE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_by         BIGINT,
    version            INT           NOT NULL DEFAULT 0,
    CONSTRAINT ux_customers_code UNIQUE (code),
    CONSTRAINT ck_customers_person_type CHECK (person_type IN ('FISICA', 'MORAL')),
    CONSTRAINT ck_customers_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_customers_rfc CHECK (rfc IS NULL OR rfc ~ '^[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}$'),
    CONSTRAINT ck_customers_zip CHECK (tax_zip IS NULL OR tax_zip ~ '^[0-9]{5}$')
);
-- Evita duplicar el mismo cliente: RFC único (cuando existe) y correo único entre clientes activos.
CREATE UNIQUE INDEX ux_customers_rfc ON customers (rfc) WHERE rfc IS NOT NULL AND rfc NOT IN ('XAXX010101000', 'XEXX010101000');
CREATE UNIQUE INDEX ux_customers_email ON customers (email) WHERE NOT archived;
CREATE INDEX idx_customers_owner ON customers (owner_user_id);
CREATE INDEX idx_customers_trade_name ON customers (lower(trade_name));

CREATE TABLE customer_contacts (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    name         VARCHAR(150)  NOT NULL,
    position     VARCHAR(100),
    email        VARCHAR(150),
    phone        VARCHAR(20),
    is_primary   BOOLEAN       NOT NULL DEFAULT FALSE,
    is_billing   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_contacts_customer ON customer_contacts (customer_id);

CREATE TABLE customer_tags (
    customer_id  BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    tag_id       BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (customer_id, tag_id)
);

CREATE TABLE customer_notes (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT         NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    body         VARCHAR(4000)  NOT NULL,
    pinned       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_by   BIGINT         REFERENCES users(id),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_notes_customer ON customer_notes (customer_id, created_at DESC);

-- ── CRM: prospectos (cada prospecto es una oportunidad en el embudo) ────────────────
CREATE TABLE prospects (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contact_name          VARCHAR(150)   NOT NULL,
    business_name         VARCHAR(150)   NOT NULL,
    phone                 VARCHAR(20),
    whatsapp              VARCHAR(20),
    email                 VARCHAR(150),
    city                  VARCHAR(100),
    state                 VARCHAR(100),
    country               VARCHAR(2)     NOT NULL DEFAULT 'MX',
    industry              VARCHAR(80),
    source                VARCHAR(30)    NOT NULL,
    owner_user_id         BIGINT         REFERENCES users(id),
    estimated_budget      NUMERIC(14,2),
    estimated_value       NUMERIC(14,2),
    probability           INT            NOT NULL DEFAULT 10,
    stage                 VARCHAR(20)    NOT NULL DEFAULT 'NEW',
    stage_changed_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    next_contact_at       TIMESTAMPTZ,
    comments              VARCHAR(4000),
    lost_reason           VARCHAR(40),
    lost_detail           VARCHAR(500),
    converted_customer_id BIGINT         REFERENCES customers(id),
    converted_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_by            BIGINT,
    version               INT            NOT NULL DEFAULT 0,
    CONSTRAINT ck_prospects_stage CHECK (stage IN ('NEW', 'CONTACTED', 'QUALIFIED', 'DEMO_SCHEDULED', 'DEMO_DONE',
        'QUOTE_SENT', 'NEGOTIATION', 'WON', 'LOST', 'DISCARDED')),
    CONSTRAINT ck_prospects_probability CHECK (probability BETWEEN 0 AND 100),
    CONSTRAINT ck_prospects_amounts CHECK ((estimated_budget IS NULL OR estimated_budget >= 0)
        AND (estimated_value IS NULL OR estimated_value >= 0)),
    CONSTRAINT ck_prospects_email_lower CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_prospects_lost CHECK (stage <> 'LOST' OR lost_reason IS NOT NULL)
);
CREATE INDEX idx_prospects_stage ON prospects (stage);
CREATE INDEX idx_prospects_owner ON prospects (owner_user_id);
CREATE INDEX idx_prospects_next_contact ON prospects (next_contact_at) WHERE stage NOT IN ('WON', 'LOST', 'DISCARDED');
CREATE INDEX idx_prospects_created ON prospects (created_at);
ALTER TABLE customers ADD CONSTRAINT fk_customers_prospect FOREIGN KEY (source_prospect_id) REFERENCES prospects(id);

-- Un prospecto puede interesarse en varios productos.
CREATE TABLE prospect_interests (
    prospect_id  BIGINT NOT NULL REFERENCES prospects(id) ON DELETE CASCADE,
    product_id   BIGINT NOT NULL REFERENCES products(id),
    plan_id      BIGINT REFERENCES plans(id),
    PRIMARY KEY (prospect_id, product_id)
);
CREATE INDEX idx_prospect_interests_product ON prospect_interests (product_id);

-- Seguimiento comercial (llamadas, reuniones, demos, notas, compromisos, cambios de etapa).
CREATE TABLE sales_activities (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prospect_id   BIGINT        REFERENCES prospects(id) ON DELETE CASCADE,
    customer_id   BIGINT        REFERENCES customers(id) ON DELETE CASCADE,
    type          VARCHAR(20)   NOT NULL,
    subject       VARCHAR(200)  NOT NULL,
    body          VARCHAR(4000),
    scheduled_at  TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    outcome       VARCHAR(300),
    user_id       BIGINT        REFERENCES users(id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_sales_activities_type CHECK (type IN ('CALL', 'MESSAGE', 'EMAIL', 'MEETING', 'DEMO', 'NOTE',
        'COMMITMENT', 'STAGE_CHANGE', 'CONVERSION')),
    CONSTRAINT ck_sales_activities_owner CHECK (prospect_id IS NOT NULL OR customer_id IS NOT NULL)
);
CREATE INDEX idx_sales_activities_prospect ON sales_activities (prospect_id, created_at DESC);
CREATE INDEX idx_sales_activities_customer ON sales_activities (customer_id, created_at DESC);
CREATE INDEX idx_sales_activities_scheduled ON sales_activities (scheduled_at) WHERE completed_at IS NULL;
