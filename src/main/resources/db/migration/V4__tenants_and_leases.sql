-- =====================================================================
-- V4: Tenants, charge types, leases and recurring charges
-- =====================================================================

CREATE TABLE tenants (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL REFERENCES organizations(id),
    tenant_type                 varchar(10) NOT NULL DEFAULT 'INDIVIDUAL'
                                CHECK (tenant_type IN ('INDIVIDUAL','COMPANY')),
    name                        varchar(150) NOT NULL,    -- person's full name or company name
    contact_person              varchar(150),             -- for company tenants
    id_type                     varchar(15)
                                CHECK (id_type IN ('NATIONAL_ID','PASSPORT','ALIEN_ID','COMPANY_REG')),
    id_number_enc               bytea,                    -- encrypted by the app (Data Protection Act)
    id_number_hash              varchar(64),              -- SHA-256, for duplicate checks without decrypting
    kra_pin                     varchar(20),
    email                       citext,
    phone                       varchar(20) NOT NULL,
    alt_phone                   varchar(20),
    employer                    varchar(150),
    postal_address              text,
    next_of_kin_name            varchar(150),
    next_of_kin_phone           varchar(20),
    next_of_kin_relationship    varchar(50),
    -- Fallback Paybill account number for tenants who pay with their own code
    account_code                varchar(30) NOT NULL,
    status                      varchar(12) NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('PROSPECT','ACTIVE','FORMER','BLACKLISTED')),
    notes                       text,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    created_by                  uuid,
    deleted_at                  timestamptz,
    UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX ux_tenants_account_code ON tenants (organization_id, upper(account_code)) WHERE deleted_at IS NULL;
CREATE INDEX ix_tenants_org_name  ON tenants (organization_id, name);
CREATE INDEX ix_tenants_org_phone ON tenants (organization_id, phone);
CREATE INDEX ix_tenants_id_hash   ON tenants (organization_id, id_number_hash);

-- Tenant portal login: link a user account to a tenant record
ALTER TABLE users ADD COLUMN tenant_id uuid;
ALTER TABLE users ADD CONSTRAINT fk_users_tenant
    FOREIGN KEY (organization_id, tenant_id) REFERENCES tenants (organization_id, id);

-- ---------------------------------------------------------------------
-- Charge types: rent, service charge, water, garbage, penalty, deposit...
-- Seeded per organisation at sign-up (see README); orgs can add their own.
-- ---------------------------------------------------------------------
CREATE TABLE charge_types (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    code                    varchar(30)  NOT NULL,
    name                    varchar(100) NOT NULL,
    category                varchar(10)  NOT NULL
                            CHECK (category IN ('RENT','SERVICE','UTILITY','DEPOSIT','PENALTY','OTHER')),
    is_taxable              boolean NOT NULL DEFAULT false,         -- VAT applies (commercial)
    vat_rate                numeric(5,2) NOT NULL DEFAULT 0 CHECK (vat_rate >= 0),
    -- false = income belongs to the managing org (e.g. agency fee), not the owner
    owner_income            boolean NOT NULL DEFAULT true,
    -- Lower number is paid off first when a payment is allocated
    allocation_priority     smallint NOT NULL DEFAULT 50,
    active                  boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, code)
);

-- ---------------------------------------------------------------------
-- Leases
-- ---------------------------------------------------------------------
CREATE TABLE leases (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL REFERENCES organizations(id),
    lease_no                    varchar(30) NOT NULL,
    unit_id                     uuid NOT NULL,
    tenant_id                   uuid NOT NULL,
    lease_type                  varchar(12) NOT NULL DEFAULT 'RESIDENTIAL'
                                CHECK (lease_type IN ('RESIDENTIAL','COMMERCIAL')),
    status                      varchar(15) NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT','ACTIVE','NOTICE_GIVEN','EXPIRED','TERMINATED')),
    start_date                  date NOT NULL,
    end_date                    date,                    -- NULL = periodic (month-to-month)
    -- Rent and billing
    rent_amount                 numeric(14,2) NOT NULL CHECK (rent_amount >= 0),
    billing_frequency           varchar(12) NOT NULL DEFAULT 'MONTHLY'
                                CHECK (billing_frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    billing_day                 smallint NOT NULL DEFAULT 1 CHECK (billing_day BETWEEN 1 AND 28),
    due_days                    smallint NOT NULL DEFAULT 5 CHECK (due_days >= 0),  -- due date = invoice date + due_days
    next_billing_date           date,                    -- maintained by the invoicing job
    -- Deposit
    deposit_amount              numeric(14,2) NOT NULL DEFAULT 0 CHECK (deposit_amount >= 0),
    -- Escalation, e.g. 5% every 12 months
    escalation_type             varchar(10) NOT NULL DEFAULT 'NONE'
                                CHECK (escalation_type IN ('NONE','PERCENT','FIXED')),
    escalation_value            numeric(10,2) NOT NULL DEFAULT 0 CHECK (escalation_value >= 0),
    escalation_interval_months  smallint CHECK (escalation_interval_months > 0),
    next_escalation_date        date,
    -- Tax (commercial)
    vat_applicable              boolean NOT NULL DEFAULT false,
    withholding_tax_applicable  boolean NOT NULL DEFAULT false,
    -- Late payment penalty
    penalty_type                varchar(10) NOT NULL DEFAULT 'NONE'
                                CHECK (penalty_type IN ('NONE','PERCENT','FIXED')),
    penalty_value               numeric(10,2) NOT NULL DEFAULT 0 CHECK (penalty_value >= 0),
    penalty_grace_days          smallint NOT NULL DEFAULT 0 CHECK (penalty_grace_days >= 0),
    -- Notice and move in/out
    notice_period_days          smallint NOT NULL DEFAULT 30,
    notice_given_date           date,
    move_in_date                date,
    move_out_date               date,
    termination_reason          text,
    notes                       text,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    created_by                  uuid,
    deleted_at                  timestamptz,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, lease_no),
    FOREIGN KEY (organization_id, unit_id)   REFERENCES units   (organization_id, id),
    FOREIGN KEY (organization_id, tenant_id) REFERENCES tenants (organization_id, id),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK (escalation_type = 'NONE' OR escalation_interval_months IS NOT NULL),
    -- A unit can never have two live leases for overlapping dates
    CONSTRAINT ex_leases_no_overlap EXCLUDE USING gist (
        organization_id WITH =,
        unit_id         WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    ) WHERE (status IN ('ACTIVE','NOTICE_GIVEN') AND deleted_at IS NULL)
);
CREATE INDEX ix_leases_org_tenant  ON leases (organization_id, tenant_id);
CREATE INDEX ix_leases_org_status  ON leases (organization_id, status);
CREATE INDEX ix_leases_next_billing ON leases (next_billing_date) WHERE status IN ('ACTIVE','NOTICE_GIVEN');
CREATE INDEX ix_leases_end_date    ON leases (organization_id, end_date) WHERE status IN ('ACTIVE','NOTICE_GIVEN');

-- Recurring charges on a lease other than rent (service charge, garbage, parking)
-- and metered utilities (water billed per unit from meter readings)
CREATE TABLE lease_charges (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    lease_id            uuid NOT NULL,
    charge_type_id      uuid NOT NULL,
    amount              numeric(14,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),     -- fixed amount per period
    is_metered          boolean NOT NULL DEFAULT false,
    rate_per_unit       numeric(10,2) CHECK (rate_per_unit >= 0),                  -- e.g. KES per m3 of water
    start_date          date NOT NULL,
    end_date            date,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, lease_id)       REFERENCES leases       (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, charge_type_id) REFERENCES charge_types (organization_id, id),
    CHECK (NOT is_metered OR rate_per_unit IS NOT NULL)
);
CREATE INDEX ix_lease_charges_lease ON lease_charges (organization_id, lease_id);

-- History of rent changes (escalations, renegotiations)
CREATE TABLE lease_rent_history (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    lease_id            uuid NOT NULL,
    effective_date      date NOT NULL,
    old_rent            numeric(14,2) NOT NULL,
    new_rent            numeric(14,2) NOT NULL,
    reason              varchar(20) NOT NULL CHECK (reason IN ('ESCALATION','RENEGOTIATION','CORRECTION')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    FOREIGN KEY (organization_id, lease_id) REFERENCES leases (organization_id, id) ON DELETE CASCADE
);

CREATE TRIGGER trg_tenants_updated       BEFORE UPDATE ON tenants       FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_charge_types_updated  BEFORE UPDATE ON charge_types  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_leases_updated        BEFORE UPDATE ON leases        FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_lease_charges_updated BEFORE UPDATE ON lease_charges FOR EACH ROW EXECUTE FUNCTION set_updated_at();
