-- =====================================================================
-- V2: Platform (SaaS) tables, users, roles and permissions
-- Conventions used in every migration:
--   * UUID primary keys
--   * organization_id on every tenant-owned table (enforced by RLS in V7)
--   * UNIQUE (organization_id, id) on parents so child tables can use
--     composite foreign keys: a child can never point at another
--     organisation's row, even by mistake
--   * Money: NUMERIC(14,2), currency KES
--   * Status columns: VARCHAR + CHECK (maps cleanly to Java enums with
--     @Enumerated(EnumType.STRING))
--   * created_at / updated_at / created_by, soft delete via deleted_at
-- =====================================================================

-- ---------------------------------------------------------------------
-- Plans (Starter, Growth, Professional, Enterprise)
-- ---------------------------------------------------------------------
CREATE TABLE plans (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                varchar(40)  NOT NULL UNIQUE,
    name                varchar(100) NOT NULL,
    description         text,
    currency            char(3)      NOT NULL DEFAULT 'KES',
    monthly_price       numeric(14,2) NOT NULL CHECK (monthly_price >= 0),
    annual_price        numeric(14,2) NOT NULL CHECK (annual_price >= 0),
    unit_limit          integer CHECK (unit_limit > 0),          -- NULL = unlimited
    user_limit          integer CHECK (user_limit > 0),          -- NULL = unlimited
    extra_unit_price    numeric(14,2) CHECK (extra_unit_price >= 0), -- NULL = no overage allowed
    sms_quota_monthly   integer NOT NULL DEFAULT 0 CHECK (sms_quota_monthly >= 0),
    features            jsonb   NOT NULL DEFAULT '{}'::jsonb,    -- {"TENANT_PORTAL": true, ...}
    is_public           boolean NOT NULL DEFAULT true,           -- false = custom/enterprise plan
    sort_order          integer NOT NULL DEFAULT 0,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Organisations (the SaaS customers: landlords, agencies, PM firms)
-- ---------------------------------------------------------------------
CREATE TABLE organizations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            varchar(150) NOT NULL,
    slug            citext       NOT NULL UNIQUE,    -- used for subdomain / portal URL
    legal_name      varchar(200),
    kra_pin         varchar(20),
    email           citext,
    phone           varchar(20),
    address         text,
    county          varchar(60),
    town            varchar(80),
    logo_url        text,
    currency        char(3)      NOT NULL DEFAULT 'KES',
    timezone        varchar(50)  NOT NULL DEFAULT 'Africa/Nairobi',
    status          varchar(20)  NOT NULL DEFAULT 'TRIAL'
                    CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED')),
    settings        jsonb        NOT NULL DEFAULT '{}'::jsonb,  -- reminder days, penalty defaults, etc.
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);

-- ---------------------------------------------------------------------
-- Subscriptions and our own billing of organisations
-- ---------------------------------------------------------------------
CREATE TABLE subscriptions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    plan_id                 uuid NOT NULL REFERENCES plans(id),
    status                  varchar(20) NOT NULL
                            CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED')),
    billing_cycle           varchar(10) NOT NULL DEFAULT 'MONTHLY'
                            CHECK (billing_cycle IN ('MONTHLY','ANNUAL')),
    price                   numeric(14,2) NOT NULL CHECK (price >= 0),  -- price locked at signup/renewal
    extra_units             integer NOT NULL DEFAULT 0 CHECK (extra_units >= 0),
    current_period_start    date NOT NULL,
    current_period_end      date NOT NULL,
    trial_ends_at           timestamptz,
    grace_ends_at           timestamptz,     -- read-only mode starts after this
    cancel_at_period_end    boolean NOT NULL DEFAULT false,
    cancelled_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CHECK (current_period_end >= current_period_start)
);
-- Only one live subscription per organisation
CREATE UNIQUE INDEX ux_subscriptions_one_live
    ON subscriptions (organization_id)
    WHERE status IN ('TRIALING','ACTIVE','PAST_DUE');

CREATE TABLE subscription_invoices (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    subscription_id     uuid NOT NULL REFERENCES subscriptions(id),
    invoice_no          varchar(30) NOT NULL UNIQUE,
    period_start        date NOT NULL,
    period_end          date NOT NULL,
    subtotal            numeric(14,2) NOT NULL,
    vat_amount          numeric(14,2) NOT NULL DEFAULT 0,
    total               numeric(14,2) NOT NULL,
    status              varchar(10) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('DRAFT','OPEN','PAID','VOID')),
    due_date            date NOT NULL,
    paid_at             timestamptz,
    etims_ref           varchar(100),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscription_payments (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL REFERENCES organizations(id),
    subscription_invoice_id     uuid REFERENCES subscription_invoices(id),
    amount                      numeric(14,2) NOT NULL CHECK (amount > 0),
    method                      varchar(10) NOT NULL CHECK (method IN ('MPESA','CARD','BANK')),
    reference                   varchar(100),            -- M-Pesa receipt / gateway ref
    status                      varchar(10) NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    paid_at                     timestamptz,
    raw_response                jsonb,
    created_at                  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_subscription_payments_ref
    ON subscription_payments (method, reference) WHERE reference IS NOT NULL;

-- ---------------------------------------------------------------------
-- Permissions, roles, users
-- ---------------------------------------------------------------------
CREATE TABLE permissions (
    code        varchar(60) PRIMARY KEY,          -- e.g. INVOICE_CREATE
    module      varchar(40) NOT NULL,             -- e.g. BILLING
    description text
);

-- organization_id NULL = system role shared by all organisations
-- (ORG_OWNER, PROPERTY_MANAGER, ...). Orgs on higher plans may add custom roles.
CREATE TABLE roles (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id),
    code            varchar(40)  NOT NULL,
    name            varchar(100) NOT NULL,
    description     text,
    is_system       boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_roles_org_code ON roles (organization_id, code) NULLS NOT DISTINCT;

CREATE TABLE role_permissions (
    role_id         uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_code varchar(60) NOT NULL REFERENCES permissions(code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE users (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid REFERENCES organizations(id),   -- NULL only for platform (super admin) users
    user_type           varchar(10) NOT NULL
                        CHECK (user_type IN ('PLATFORM','STAFF','TENANT','OWNER')),
    first_name          varchar(80)  NOT NULL,
    last_name           varchar(80),
    email               citext,
    phone               varchar(20),                         -- E.164, e.g. +2547XXXXXXXX
    password_hash       varchar(100),                        -- NULL for OTP-only users (tenants/owners)
    status              varchar(10) NOT NULL DEFAULT 'INVITED'
                        CHECK (status IN ('INVITED','ACTIVE','DISABLED')),
    email_verified_at   timestamptz,
    phone_verified_at   timestamptz,
    mfa_enabled         boolean NOT NULL DEFAULT false,
    mfa_secret_enc      bytea,                               -- encrypted by the app, never plain text
    last_login_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz,
    UNIQUE (organization_id, id),
    CHECK ((user_type = 'PLATFORM') = (organization_id IS NULL)),
    CHECK (email IS NOT NULL OR phone IS NOT NULL)
);
CREATE UNIQUE INDEX ux_users_org_email ON users (organization_id, email) NULLS NOT DISTINCT
    WHERE deleted_at IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX ux_users_org_phone ON users (organization_id, phone) NULLS NOT DISTINCT
    WHERE deleted_at IS NULL AND phone IS NOT NULL;
CREATE INDEX ix_users_email ON users (email);   -- login lookup across orgs
CREATE INDEX ix_users_phone ON users (phone);

CREATE TABLE user_roles (
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id         uuid NOT NULL REFERENCES roles(id),
    organization_id uuid REFERENCES organizations(id),       -- copied from the user, for RLS
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      varchar(100) NOT NULL UNIQUE,   -- SHA-256 of the token, never the token itself
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    replaced_by     uuid REFERENCES refresh_tokens(id),
    user_agent      text,
    ip_address      inet,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- updated_at triggers
CREATE TRIGGER trg_plans_updated         BEFORE UPDATE ON plans                 FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_organizations_updated BEFORE UPDATE ON organizations         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_subscriptions_updated BEFORE UPDATE ON subscriptions         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_sub_invoices_updated  BEFORE UPDATE ON subscription_invoices FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_roles_updated         BEFORE UPDATE ON roles                 FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_users_updated         BEFORE UPDATE ON users                 FOR EACH ROW EXECUTE FUNCTION set_updated_at();
