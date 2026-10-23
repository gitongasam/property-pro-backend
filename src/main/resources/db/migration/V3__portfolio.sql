-- =====================================================================
-- V3: Portfolio - owners (landlords), properties, units
-- =====================================================================

CREATE TABLE owners (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    owner_type              varchar(10) NOT NULL DEFAULT 'INDIVIDUAL'
                            CHECK (owner_type IN ('INDIVIDUAL','COMPANY')),
    name                    varchar(150) NOT NULL,
    id_number               varchar(30),          -- national ID / passport / company reg no.
    kra_pin                 varchar(20),
    email                   citext,
    phone                   varchar(20),
    address                 text,
    -- Remittance details
    bank_name               varchar(100),
    bank_branch             varchar(100),
    bank_account_name       varchar(150),
    bank_account_no_enc     bytea,                -- encrypted by the app
    mpesa_phone             varchar(20),
    -- Management terms
    management_fee_type     varchar(10) NOT NULL DEFAULT 'PERCENT'
                            CHECK (management_fee_type IN ('PERCENT','FIXED')),
    management_fee_value    numeric(10,2) NOT NULL DEFAULT 0 CHECK (management_fee_value >= 0),
    remittance_day          smallint CHECK (remittance_day BETWEEN 1 AND 28),
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    deleted_at              timestamptz,
    UNIQUE (organization_id, id)
);
CREATE INDEX ix_owners_org_name ON owners (organization_id, name);

CREATE TABLE properties (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    owner_id                uuid,
    code                    varchar(20)  NOT NULL,     -- short code, e.g. 'KLM' (used in unit payment codes)
    name                    varchar(150) NOT NULL,
    property_type           varchar(15)  NOT NULL
                            CHECK (property_type IN ('RESIDENTIAL','COMMERCIAL','MIXED_USE','INDUSTRIAL','LAND')),
    county                  varchar(60),
    town                    varchar(80),
    estate                  varchar(100),              -- estate / neighbourhood, e.g. Kilimani
    address                 text,
    latitude                numeric(9,6),
    longitude               numeric(9,6),
    lr_number               varchar(60),               -- land reference number
    year_built              smallint,
    floors                  smallint,
    description             text,
    amenities               jsonb NOT NULL DEFAULT '[]'::jsonb,   -- ["borehole","lift","CCTV"]
    cover_photo_url         text,
    -- Overrides the owner's management fee for this property when set
    management_fee_type     varchar(10) CHECK (management_fee_type IN ('PERCENT','FIXED')),
    management_fee_value    numeric(10,2) CHECK (management_fee_value >= 0),
    status                  varchar(10) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    deleted_at              timestamptz,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, owner_id) REFERENCES owners (organization_id, id)
);
CREATE UNIQUE INDEX ux_properties_org_code ON properties (organization_id, code) WHERE deleted_at IS NULL;
CREATE INDEX ix_properties_org_owner ON properties (organization_id, owner_id);

CREATE TABLE units (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    property_id             uuid NOT NULL,
    unit_no                 varchar(20) NOT NULL,      -- e.g. 'B4', 'Shop 12', '3rd Flr Suite A'
    block                   varchar(30),
    floor                   varchar(20),
    unit_type               varchar(15) NOT NULL
                            CHECK (unit_type IN ('BEDSITTER','STUDIO','ONE_BR','TWO_BR','THREE_BR','FOUR_PLUS_BR',
                                                 'MAISONETTE','HOUSE','SHOP','OFFICE','WAREHOUSE','STALL',
                                                 'PARKING','OTHER')),
    bedrooms                smallint,
    bathrooms               smallint,
    size_sqft               numeric(10,2) CHECK (size_sqft > 0),
    market_rent             numeric(14,2) NOT NULL DEFAULT 0 CHECK (market_rent >= 0),
    deposit_amount          numeric(14,2) NOT NULL DEFAULT 0 CHECK (deposit_amount >= 0),
    service_charge          numeric(14,2) NOT NULL DEFAULT 0 CHECK (service_charge >= 0),
    -- Account number tenants type on the Paybill, e.g. 'KLM-B4'. Stays with the unit,
    -- so a new tenant pays with the same code and the active lease decides who it belongs to.
    payment_code            varchar(30) NOT NULL,
    water_meter_no          varchar(40),
    electricity_meter_no    varchar(40),
    status                  varchar(20) NOT NULL DEFAULT 'VACANT'
                            CHECK (status IN ('VACANT','OCCUPIED','RESERVED','UNDER_MAINTENANCE','UNAVAILABLE')),
    notes                   text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    deleted_at              timestamptz,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id)
);
CREATE UNIQUE INDEX ux_units_property_unit_no ON units (organization_id, property_id, unit_no) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_units_payment_code     ON units (organization_id, upper(payment_code)) WHERE deleted_at IS NULL;
CREATE INDEX ix_units_org_status ON units (organization_id, status);

-- Which properties a manager or caretaker may work on.
-- No rows for a user = access to all properties (org owners, accountants).
CREATE TABLE user_property_access (
    organization_id uuid NOT NULL,
    user_id         uuid NOT NULL,
    property_id     uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, property_id),
    FOREIGN KEY (organization_id, user_id)     REFERENCES users (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id) ON DELETE CASCADE
);

-- Owner portal login: link a user account to an owner record
ALTER TABLE users ADD COLUMN owner_id uuid;
ALTER TABLE users ADD CONSTRAINT fk_users_owner
    FOREIGN KEY (organization_id, owner_id) REFERENCES owners (organization_id, id);

CREATE TRIGGER trg_owners_updated     BEFORE UPDATE ON owners     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_properties_updated BEFORE UPDATE ON properties FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_units_updated      BEFORE UPDATE ON units      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
