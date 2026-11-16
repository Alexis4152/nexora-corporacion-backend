-- =====================================================================================
-- V3: motor de suscripciones y núcleo financiero (cargos, pagos, aplicaciones, devoluciones, cobranza).
-- Reglas: importes NUMERIC(14,2); nada financiero se borra; los saldos se calculan sobre movimientos válidos.
-- El estado de la suscripción (ciclo de vida) y el de cobranza (cargos) son conceptos separados.
-- =====================================================================================

CREATE SEQUENCE subscription_code_seq START 1;
CREATE TABLE subscriptions (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                       VARCHAR(20)    NOT NULL,
    customer_id                BIGINT         NOT NULL REFERENCES customers(id),
    product_id                 BIGINT         NOT NULL REFERENCES products(id),
    plan_id                    BIGINT         NOT NULL REFERENCES plans(id),
    plan_price_id              BIGINT         REFERENCES plan_prices(id),
    billing_period             VARCHAR(12)    NOT NULL,
    -- Precio público vigente al contratar (referencia) y precio pactado con el cliente (el que se cobra).
    list_price                 NUMERIC(14,2)  NOT NULL,
    price                      NUMERIC(14,2)  NOT NULL,
    currency                   VARCHAR(3)     NOT NULL,
    tax_included               BOOLEAN        NOT NULL,
    discount_type              VARCHAR(10)    NOT NULL DEFAULT 'NONE',
    discount_value             NUMERIC(14,2)  NOT NULL DEFAULT 0,
    -- Cargos restantes con descuento; NULL = permanente.
    discount_cycles_remaining  INT,
    coupon_id                  BIGINT         REFERENCES coupons(id),
    status                     VARCHAR(20)    NOT NULL,
    start_date                 DATE           NOT NULL,
    trial_ends_on              DATE,
    -- Día del mes al que se ancla la facturación (31 = último día en meses cortos).
    billing_anchor_day         INT            NOT NULL,
    current_period_start       DATE,
    current_period_end         DATE,
    next_charge_on             DATE,
    auto_renew                 BOOLEAN        NOT NULL DEFAULT TRUE,
    grace_days                 INT            NOT NULL,
    preferred_payment_method   VARCHAR(20),
    cancel_at_period_end       BOOLEAN        NOT NULL DEFAULT FALSE,
    cancellation_requested_at  TIMESTAMPTZ,
    cancelled_on               DATE,
    cancellation_reason        VARCHAR(40),
    cancellation_detail        VARCHAR(500),
    suspended_at               TIMESTAMPTZ,
    -- Cambio programado para el siguiente periodo (degradaciones y cambio de periodicidad).
    pending_plan_id            BIGINT         REFERENCES plans(id),
    pending_plan_price_id      BIGINT         REFERENCES plan_prices(id),
    pending_price              NUMERIC(14,2),
    pending_billing_period     VARCHAR(12),
    -- MRR vigente normalizado a mes, sin IVA y con descuento (0 si no es ingreso recurrente elegible).
    mrr                        NUMERIC(14,4)  NOT NULL DEFAULT 0,
    notes                      VARCHAR(2000),
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by                 BIGINT,
    updated_by                 BIGINT,
    version                    INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_subscriptions_code UNIQUE (code),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('PENDING_ACTIVATION', 'TRIAL', 'ACTIVE', 'GRACE', 'SUSPENDED',
        'CANCEL_SCHEDULED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_subscriptions_period CHECK (billing_period IN ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    CONSTRAINT ck_subscriptions_pending_period CHECK (pending_billing_period IS NULL
        OR pending_billing_period IN ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    CONSTRAINT ck_subscriptions_amounts CHECK (list_price >= 0 AND price >= 0 AND mrr >= 0),
    CONSTRAINT ck_subscriptions_discount CHECK (discount_type IN ('NONE', 'PERCENT', 'AMOUNT') AND discount_value >= 0
        AND (discount_type <> 'PERCENT' OR discount_value <= 100)
        AND (discount_type <> 'AMOUNT' OR discount_value <= price)),
    CONSTRAINT ck_subscriptions_anchor CHECK (billing_anchor_day BETWEEN 1 AND 31),
    CONSTRAINT ck_subscriptions_grace CHECK (grace_days BETWEEN 0 AND 60),
    CONSTRAINT ck_subscriptions_period_dates CHECK (current_period_end IS NULL OR current_period_end >= current_period_start),
    CONSTRAINT ck_subscriptions_cancelled CHECK (status NOT IN ('CANCELLED') OR cancelled_on IS NOT NULL),
    CONSTRAINT ck_subscriptions_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX idx_subscriptions_customer ON subscriptions (customer_id);
CREATE INDEX idx_subscriptions_product_status ON subscriptions (product_id, status);
CREATE INDEX idx_subscriptions_status ON subscriptions (status);
CREATE INDEX idx_subscriptions_next_charge ON subscriptions (next_charge_on) WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED');
CREATE INDEX idx_subscriptions_period_end ON subscriptions (current_period_end);
CREATE INDEX idx_subscriptions_trial_end ON subscriptions (trial_ends_on) WHERE status = 'TRIAL';

-- Historial inmutable. Cada evento guarda el MRR antes/después: base de los indicadores SaaS históricos
-- (MRR por mes, New/Expansion/Contraction/Churn/Reactivation, NRR, GRR, cohortes).
CREATE TABLE subscription_events (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id  BIGINT         NOT NULL REFERENCES subscriptions(id),
    customer_id      BIGINT         NOT NULL REFERENCES customers(id),
    product_id       BIGINT         NOT NULL REFERENCES products(id),
    type             VARCHAR(30)    NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20)    NOT NULL,
    effective_on     DATE           NOT NULL,
    mrr_before       NUMERIC(14,4)  NOT NULL DEFAULT 0,
    mrr_after        NUMERIC(14,4)  NOT NULL DEFAULT 0,
    movement         VARCHAR(15)    NOT NULL DEFAULT 'NONE',
    data             JSONB,
    user_id          BIGINT,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_subscription_events_movement CHECK (movement IN ('NONE', 'NEW', 'EXPANSION', 'CONTRACTION', 'CHURN', 'REACTIVATION'))
);
CREATE INDEX idx_subscription_events_sub ON subscription_events (subscription_id, effective_on, id);
CREATE INDEX idx_subscription_events_effective ON subscription_events (effective_on);
CREATE INDEX idx_subscription_events_customer ON subscription_events (customer_id, effective_on);
CREATE TRIGGER trg_subscription_events_immutable BEFORE UPDATE OR DELETE ON subscription_events
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();

-- ── Cargos (documento interno de cobro; NO es un CFDI) ───────────────────────────────
CREATE SEQUENCE charge_folio_seq START 1;
CREATE TABLE charges (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio            VARCHAR(20)    NOT NULL,
    customer_id      BIGINT         NOT NULL REFERENCES customers(id),
    subscription_id  BIGINT         REFERENCES subscriptions(id),
    product_id       BIGINT         REFERENCES products(id),
    plan_id          BIGINT         REFERENCES plans(id),
    type             VARCHAR(15)    NOT NULL,
    description      VARCHAR(300)   NOT NULL,
    period_start     DATE,
    period_end       DATE,
    issue_date       DATE           NOT NULL,
    due_date         DATE           NOT NULL,
    amount           NUMERIC(14,2)  NOT NULL,
    discount         NUMERIC(14,2)  NOT NULL DEFAULT 0,
    subtotal         NUMERIC(14,2)  NOT NULL,
    tax              NUMERIC(14,2)  NOT NULL,
    total            NUMERIC(14,2)  NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    status           VARCHAR(10)    NOT NULL DEFAULT 'OPEN',
    -- Acumulados mantenidos por el servicio dentro de la misma transacción que sus movimientos.
    paid_amount      NUMERIC(14,2)  NOT NULL DEFAULT 0,
    adjusted_amount  NUMERIC(14,2)  NOT NULL DEFAULT 0,
    voided_at        TIMESTAMPTZ,
    voided_by        BIGINT,
    void_reason      VARCHAR(300),
    -- Clave idempotente: cargos generados por tarea = SUB:<id>:<inicio de periodo>.
    idempotency_key  VARCHAR(80),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       BIGINT,
    version          INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_charges_folio UNIQUE (folio),
    CONSTRAINT ux_charges_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_charges_type CHECK (type IN ('SUBSCRIPTION', 'PRORATION', 'SETUP', 'SERVICE', 'OTHER')),
    CONSTRAINT ck_charges_status CHECK (status IN ('OPEN', 'PARTIAL', 'PAID', 'VOID')),
    CONSTRAINT ck_charges_amounts CHECK (amount >= 0 AND discount >= 0 AND discount <= amount AND subtotal >= 0
        AND tax >= 0 AND total = subtotal + tax AND total > 0),
    CONSTRAINT ck_charges_balance CHECK (paid_amount >= 0 AND adjusted_amount >= 0 AND paid_amount + adjusted_amount <= total),
    CONSTRAINT ck_charges_dates CHECK (due_date >= issue_date AND (period_end IS NULL OR period_end >= period_start)),
    CONSTRAINT ck_charges_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL))
);
-- Un solo cargo de suscripción vigente por periodo: aunque la tarea corra dos veces nunca duplica.
CREATE UNIQUE INDEX ux_charges_subscription_period ON charges (subscription_id, period_start)
    WHERE type = 'SUBSCRIPTION' AND status <> 'VOID';
CREATE INDEX idx_charges_customer ON charges (customer_id, due_date);
CREATE INDEX idx_charges_open_due ON charges (due_date) WHERE status IN ('OPEN', 'PARTIAL');
CREATE INDEX idx_charges_subscription ON charges (subscription_id, due_date);
CREATE INDEX idx_charges_issue ON charges (issue_date);
CREATE TRIGGER trg_charges_no_delete BEFORE DELETE ON charges FOR EACH ROW EXECUTE FUNCTION nx_forbid_delete();

-- Ajustes a favor del cliente (condonación, bonificación, corrección). Solo inserción.
CREATE TABLE charge_adjustments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    charge_id   BIGINT         NOT NULL REFERENCES charges(id),
    amount      NUMERIC(14,2)  NOT NULL,
    reason      VARCHAR(300)   NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_charge_adjustments_amount CHECK (amount > 0)
);
CREATE INDEX idx_charge_adjustments_charge ON charge_adjustments (charge_id);
CREATE TRIGGER trg_charge_adjustments_immutable BEFORE UPDATE OR DELETE ON charge_adjustments
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();

-- ── Pagos ────────────────────────────────────────────────────────────────────────────
CREATE SEQUENCE payment_folio_seq START 1;
CREATE TABLE payments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folio             VARCHAR(20)    NOT NULL,
    customer_id       BIGINT         NOT NULL REFERENCES customers(id),
    received_on       DATE           NOT NULL,
    amount            NUMERIC(14,2)  NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    method            VARCHAR(20)    NOT NULL,
    reference         VARCHAR(80),
    -- CAPTURED: registrado; CONFIRMED: dinero verificado; RECONCILED: cotejado contra estado de cuenta.
    status            VARCHAR(12)    NOT NULL DEFAULT 'CAPTURED',
    notes             VARCHAR(1000),
    idempotency_key   VARCHAR(80)    NOT NULL,
    registered_by     BIGINT,
    confirmed_at      TIMESTAMPTZ,
    confirmed_by      BIGINT,
    reconciled_at     TIMESTAMPTZ,
    reconciled_by     BIGINT,
    rejected_at       TIMESTAMPTZ,
    rejected_by       BIGINT,
    reject_reason     VARCHAR(300),
    reversed_at       TIMESTAMPTZ,
    reversed_by       BIGINT,
    reversal_reason   VARCHAR(300),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version           INT            NOT NULL DEFAULT 0,
    CONSTRAINT ux_payments_folio UNIQUE (folio),
    CONSTRAINT ux_payments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_method CHECK (method IN ('TRANSFER', 'SPEI', 'CASH', 'CARD', 'DEPOSIT', 'GATEWAY', 'OTHER')),
    CONSTRAINT ck_payments_status CHECK (status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED', 'REJECTED', 'REVERSED')),
    CONSTRAINT ck_payments_reference CHECK (method IN ('CASH', 'OTHER') OR reference IS NOT NULL)
);
-- La misma referencia bancaria no puede registrarse dos veces mientras el pago siga vigente.
CREATE UNIQUE INDEX ux_payments_reference ON payments (method, upper(reference))
    WHERE reference IS NOT NULL AND status NOT IN ('REJECTED', 'REVERSED');
CREATE INDEX idx_payments_customer ON payments (customer_id, received_on DESC);
CREATE INDEX idx_payments_received ON payments (received_on);
CREATE INDEX idx_payments_status ON payments (status);
CREATE TRIGGER trg_payments_no_delete BEFORE DELETE ON payments FOR EACH ROW EXECUTE FUNCTION nx_forbid_delete();

-- Aplicación de un pago a un cargo. Desaplicar = marcar reversed_at (nunca borrar ni cambiar el importe).
CREATE TABLE payment_allocations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id       BIGINT         NOT NULL REFERENCES payments(id),
    charge_id        BIGINT         NOT NULL REFERENCES charges(id),
    amount           NUMERIC(14,2)  NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       BIGINT,
    reversed_at      TIMESTAMPTZ,
    reversed_by      BIGINT,
    reversal_reason  VARCHAR(300),
    CONSTRAINT ck_payment_allocations_amount CHECK (amount > 0)
);
CREATE INDEX idx_payment_allocations_payment ON payment_allocations (payment_id);
CREATE INDEX idx_payment_allocations_charge ON payment_allocations (charge_id);

CREATE OR REPLACE FUNCTION nx_allocation_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Las aplicaciones de pago no se eliminan' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.payment_id <> OLD.payment_id OR NEW.charge_id <> OLD.charge_id OR NEW.amount <> OLD.amount
        OR NEW.created_at <> OLD.created_at OR OLD.reversed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Una aplicación de pago solo puede revertirse una vez' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_payment_allocations_guard BEFORE UPDATE OR DELETE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION nx_allocation_guard();

CREATE TABLE payment_refunds (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id   BIGINT         NOT NULL REFERENCES payments(id),
    amount       NUMERIC(14,2)  NOT NULL,
    refunded_on  DATE           NOT NULL,
    method       VARCHAR(20)    NOT NULL,
    reference    VARCHAR(80),
    reason       VARCHAR(300)   NOT NULL,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_refunds_amount CHECK (amount > 0)
);
CREATE INDEX idx_payment_refunds_payment ON payment_refunds (payment_id);
CREATE INDEX idx_payment_refunds_date ON payment_refunds (refunded_on);
CREATE TRIGGER trg_payment_refunds_immutable BEFORE UPDATE OR DELETE ON payment_refunds
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();

-- ── Gestión de cobranza ──────────────────────────────────────────────────────────────
CREATE TABLE collection_actions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id          BIGINT         NOT NULL REFERENCES customers(id),
    subscription_id      BIGINT         REFERENCES subscriptions(id),
    type                 VARCHAR(15)    NOT NULL,
    notes                VARCHAR(2000)  NOT NULL,
    promise_date         DATE,
    promise_amount       NUMERIC(14,2),
    next_action_on       DATE,
    responsible_user_id  BIGINT         REFERENCES users(id),
    created_by           BIGINT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_collection_actions_type CHECK (type IN ('CALL', 'EMAIL', 'WHATSAPP', 'VISIT', 'PROMISE', 'NOTE')),
    CONSTRAINT ck_collection_actions_promise CHECK (promise_amount IS NULL OR promise_amount > 0)
);
CREATE INDEX idx_collection_actions_customer ON collection_actions (customer_id, created_at DESC);
CREATE INDEX idx_collection_actions_next ON collection_actions (next_action_on);
