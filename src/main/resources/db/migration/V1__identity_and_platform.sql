-- =====================================================================================
-- Nexora Corporación — V1: identidad, sesiones, permisos, auditoría, configuración e infraestructura.
-- Ver docs/02-MODELO-DE-DATOS.md. Hibernate solo valida (ddl-auto=validate).
-- Todas las fechas-hora en TIMESTAMPTZ (UTC); las fechas de negocio en DATE según la zona de negocio.
-- =====================================================================================

-- Tablas de solo inserción (auditoría, libro de eventos).
CREATE OR REPLACE FUNCTION nx_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es de solo inserción: % no permitido', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

-- Tablas financieras: se pueden insertar y actualizar (estados), nunca borrar.
CREATE OR REPLACE FUNCTION nx_forbid_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Los registros de % no se eliminan: se anulan o se revierten', TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

-- ── Permisos y roles ─────────────────────────────────────────────────────────────────
CREATE TABLE permissions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(60)  NOT NULL,
    module       VARCHAR(40)  NOT NULL,
    description  VARCHAR(200) NOT NULL,
    -- Sensible: otorgarlo exige reautenticación y queda auditado con detalle.
    sensitive    BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order   INT          NOT NULL DEFAULT 0,
    CONSTRAINT ux_permissions_code UNIQUE (code),
    CONSTRAINT ck_permissions_code CHECK (code ~ '^[a-z]+(\.[a-z_]+)+$')
);

CREATE TABLE roles (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(250),
    -- Rol de sistema: no se elimina ni se renombra su código. SUPER_ADMIN además no se puede recortar.
    system       BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      INT          NOT NULL DEFAULT 0,
    CONSTRAINT ux_roles_code UNIQUE (code),
    CONSTRAINT ck_roles_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$')
);

CREATE TABLE role_permissions (
    role_id        BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id  BIGINT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

-- ── Usuarios internos (no hay registro público) ──────────────────────────────────────
CREATE TABLE users (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                  VARCHAR(150) NOT NULL,
    password_hash          VARCHAR(100) NOT NULL,
    first_name             VARCHAR(100) NOT NULL,
    last_name              VARCHAR(100) NOT NULL,
    phone                  VARCHAR(20),
    job_title              VARCHAR(100),
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password   BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    -- Bloqueos sucesivos duplican la duración (ralentización progresiva).
    lock_count             INT          NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    last_login_ip          VARCHAR(45),
    password_changed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- MFA TOTP. El secreto se guarda cifrado (AES-256-GCM); last_step evita reutilizar un código.
    mfa_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret_enc         VARCHAR(255),
    mfa_pending_secret_enc VARCHAR(255),
    mfa_last_step          BIGINT,
    permissions_version    INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_by             BIGINT,
    version                INT          NOT NULL DEFAULT 0,
    CONSTRAINT ux_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

CREATE TABLE mfa_recovery_codes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_mfa_recovery_codes UNIQUE (user_id, code_hash)
);

-- ── Sesiones: una por inicio de sesión. Los refresh tokens rotan dentro de la sesión ─
CREATE TABLE user_sessions (
    id             UUID         PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ip             VARCHAR(45),
    user_agent     VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(60),
    -- Última reautenticación con contraseña: habilita operaciones sensibles por unos minutos.
    reauth_at      TIMESTAMPTZ
);
CREATE INDEX idx_user_sessions_user ON user_sessions (user_id, created_at DESC);

CREATE TABLE refresh_tokens (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      UUID         NOT NULL REFERENCES user_sessions(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  BIGINT       REFERENCES refresh_tokens(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);

CREATE TABLE password_reset_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_password_reset_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);

-- ── Límite de solicitudes coordinado entre instancias (ventana fija en PostgreSQL) ───
CREATE UNLOGGED TABLE rate_limit_counters (
    bucket_key    VARCHAR(200) PRIMARY KEY,
    window_start  TIMESTAMPTZ  NOT NULL,
    hits          INT          NOT NULL
);

-- ── Bitácora de auditoría (solo inserción) ───────────────────────────────────────────
CREATE TABLE audit_logs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id   BIGINT,
    actor_email     VARCHAR(150),
    action          VARCHAR(60)   NOT NULL,
    module          VARCHAR(40)   NOT NULL,
    entity_type     VARCHAR(60),
    entity_id       VARCHAR(60),
    before_data     JSONB,
    after_data      JSONB,
    result          VARCHAR(10)   NOT NULL,
    detail          VARCHAR(500),
    correlation_id  VARCHAR(64),
    ip              VARCHAR(45),
    user_agent      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED'))
);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_module ON audit_logs (module, created_at DESC);
CREATE TRIGGER trg_audit_logs_immutable BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();
CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION nx_forbid_mutation();

-- ── Configuración de negocio (editable desde el módulo de configuración) ─────────────
CREATE TABLE system_settings (
    setting_key   VARCHAR(80)  PRIMARY KEY,
    value         JSONB        NOT NULL,
    category      VARCHAR(40)  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    BIGINT,
    version       INT          NOT NULL DEFAULT 0
);

-- ── Integraciones (secretos cifrados, nunca se devuelven por la API) ─────────────────
CREATE TABLE integration_configs (
    code          VARCHAR(40)  PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    config        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    secret_enc    TEXT,
    last_test_at  TIMESTAMPTZ,
    last_test_ok  BOOLEAN,
    last_test_msg VARCHAR(300),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    BIGINT,
    version       INT          NOT NULL DEFAULT 0
);

-- ── Ejecuciones de tareas programadas (observabilidad e idempotencia) ───────────────
CREATE TABLE job_runs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job          VARCHAR(60)  NOT NULL,
    business_date DATE        NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    status       VARCHAR(12)  NOT NULL,
    processed    INT          NOT NULL DEFAULT 0,
    failed       INT          NOT NULL DEFAULT 0,
    detail       VARCHAR(1000),
    CONSTRAINT ck_job_runs_status CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'SKIPPED'))
);
CREATE INDEX idx_job_runs_job ON job_runs (job, started_at DESC);
