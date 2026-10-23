-- =====================================================================
-- V5: Billing (invoices, meter readings, credit notes) and payments
--     (M-Pesa, bank, cash) with allocation to invoices
-- =====================================================================

-- ---------------------------------------------------------------------
-- Document numbering per organisation: INV-2026-000123, RCT-2026-000045
-- ---------------------------------------------------------------------
CREATE TABLE document_sequences (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    doc_type        varchar(20) NOT NULL
                    CHECK (doc_type IN ('INVOICE','CREDIT_NOTE','RECEIPT','LEASE','STATEMENT','EXPENSE')),
    year            smallint NOT NULL,
    prefix          varchar(10) NOT NULL,
    next_value      bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (organization_id, doc_type, year)
);

-- Gap-free, concurrency-safe numbering (row lock on the sequence row).
CREATE OR REPLACE FUNCTION next_document_number(p_org uuid, p_doc_type varchar, p_date date DEFAULT current_date)
RETURNS varchar LANGUAGE plpgsql AS $$
DECLARE
    v_year   smallint := extract(year FROM p_date)::smallint;
    v_prefix varchar;
    v_value  bigint;
BEGIN
    v_prefix := CASE p_doc_type
                    WHEN 'INVOICE'     THEN 'INV'
                    WHEN 'CREDIT_NOTE' THEN 'CN'
                    WHEN 'RECEIPT'     THEN 'RCT'
                    WHEN 'LEASE'       THEN 'LSE'
                    WHEN 'STATEMENT'   THEN 'OST'
                    WHEN 'EXPENSE'     THEN 'EXP'
                END;

    INSERT INTO document_sequences (organization_id, doc_type, year, prefix, next_value)
    VALUES (p_org, p_doc_type, v_year, v_prefix, 2)
    ON CONFLICT (organization_id, doc_type, year)
    DO UPDATE SET next_value = document_sequences.next_value + 1
    RETURNING next_value - 1, prefix INTO v_value, v_prefix;

    RETURN v_prefix || '-' || v_year || '-' || lpad(v_value::text, 6, '0');
END;
$$;

-- ---------------------------------------------------------------------
-- Meter readings (water / electricity sub-meters read by caretakers)
-- ---------------------------------------------------------------------
CREATE TABLE meter_readings (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    unit_id             uuid NOT NULL,
    utility             varchar(12) NOT NULL CHECK (utility IN ('WATER','ELECTRICITY','GAS')),
    reading_date        date NOT NULL,
    previous_reading    numeric(12,2) NOT NULL CHECK (previous_reading >= 0),
    current_reading     numeric(12,2) NOT NULL,
    consumption         numeric(12,2) GENERATED ALWAYS AS (current_reading - previous_reading) STORED,
    photo_url           text,
    read_by             uuid,
    billed              boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, unit_id, utility, reading_date),
    FOREIGN KEY (organization_id, unit_id) REFERENCES units (organization_id, id),
    CHECK (current_reading >= previous_reading)
);

-- ---------------------------------------------------------------------
-- Invoices
-- balance = total - amount_paid - withholding_tax_amount
-- amount_paid is maintained by the payment_allocations trigger below.
-- ---------------------------------------------------------------------
CREATE TABLE invoices (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    invoice_no              varchar(30) NOT NULL,
    tenant_id               uuid NOT NULL,
    lease_id                uuid,                      -- NULL for one-off invoices
    unit_id                 uuid,
    property_id             uuid,                      -- copied for fast per-property reporting
    source                  varchar(10) NOT NULL DEFAULT 'MANUAL'
                            CHECK (source IN ('SCHEDULED','MANUAL','PENALTY','MOVE_IN','MOVE_OUT')),
    period_start            date,
    period_end              date,
    issue_date              date NOT NULL DEFAULT current_date,
    due_date                date NOT NULL,
    subtotal                numeric(14,2) NOT NULL DEFAULT 0,
    vat_amount              numeric(14,2) NOT NULL DEFAULT 0,
    total                   numeric(14,2) NOT NULL DEFAULT 0,
    withholding_tax_amount  numeric(14,2) NOT NULL DEFAULT 0 CHECK (withholding_tax_amount >= 0),
    wht_certificate_no      varchar(50),
    amount_paid             numeric(14,2) NOT NULL DEFAULT 0,
    balance                 numeric(14,2) GENERATED ALWAYS AS (total - amount_paid - withholding_tax_amount) STORED,
    status                  varchar(15) NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','ISSUED','PARTIALLY_PAID','PAID','VOID')),
    penalty_applied_at      timestamptz,
    -- KRA eTIMS
    etims_status            varchar(10) CHECK (etims_status IN ('PENDING','SUBMITTED','FAILED')),
    etims_invoice_no        varchar(60),
    etims_qr_url            text,
    etims_submitted_at      timestamptz,
    notes                   text,
    sent_at                 timestamptz,
    voided_at               timestamptz,
    void_reason             text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, invoice_no),
    FOREIGN KEY (organization_id, tenant_id)   REFERENCES tenants    (organization_id, id),
    FOREIGN KEY (organization_id, lease_id)    REFERENCES leases     (organization_id, id),
    FOREIGN KEY (organization_id, unit_id)     REFERENCES units      (organization_id, id),
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id),
    CHECK (total = subtotal + vat_amount),
    CHECK (amount_paid >= 0),
    CHECK (total - amount_paid - withholding_tax_amount >= 0),     -- never over-paid
    CHECK (period_end IS NULL OR period_end >= period_start)
);
-- The invoicing job can run twice without double-billing a lease for the same period
CREATE UNIQUE INDEX ux_invoices_scheduled_period
    ON invoices (organization_id, lease_id, period_start)
    WHERE source = 'SCHEDULED' AND status <> 'VOID';
CREATE INDEX ix_invoices_org_tenant   ON invoices (organization_id, tenant_id);
CREATE INDEX ix_invoices_org_property ON invoices (organization_id, property_id, issue_date);
CREATE INDEX ix_invoices_open         ON invoices (organization_id, due_date)
    WHERE status IN ('ISSUED','PARTIALLY_PAID');

CREATE TABLE invoice_lines (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    invoice_id          uuid NOT NULL,
    charge_type_id      uuid NOT NULL,
    description         varchar(255) NOT NULL,
    quantity            numeric(12,3) NOT NULL DEFAULT 1,
    unit_price          numeric(14,2) NOT NULL,
    amount              numeric(14,2) NOT NULL,           -- quantity x unit_price, rounded
    vat_rate            numeric(5,2)  NOT NULL DEFAULT 0,
    vat_amount          numeric(14,2) NOT NULL DEFAULT 0,
    meter_reading_id    uuid,
    sort_order          smallint NOT NULL DEFAULT 0,
    FOREIGN KEY (organization_id, invoice_id)       REFERENCES invoices       (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, charge_type_id)   REFERENCES charge_types   (organization_id, id),
    FOREIGN KEY (organization_id, meter_reading_id) REFERENCES meter_readings (organization_id, id)
);
CREATE INDEX ix_invoice_lines_invoice ON invoice_lines (organization_id, invoice_id);

CREATE TABLE credit_notes (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    credit_note_no      varchar(30) NOT NULL,
    tenant_id           uuid NOT NULL,
    invoice_id          uuid,                         -- invoice being corrected, if any
    issue_date          date NOT NULL DEFAULT current_date,
    amount              numeric(14,2) NOT NULL CHECK (amount > 0),
    amount_allocated    numeric(14,2) NOT NULL DEFAULT 0,
    reason              text NOT NULL,
    status              varchar(10) NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','VOID')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, credit_note_no),
    FOREIGN KEY (organization_id, tenant_id)  REFERENCES tenants  (organization_id, id),
    FOREIGN KEY (organization_id, invoice_id) REFERENCES invoices (organization_id, id),
    CHECK (amount_allocated BETWEEN 0 AND amount)
);

-- ---------------------------------------------------------------------
-- Payment channels: each organisation's own Paybill/Till (or one per property)
-- Webhooks find the organisation from the shortcode.
-- ---------------------------------------------------------------------
CREATE TABLE payment_channels (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    property_id             uuid,                       -- NULL = used for all properties
    channel_type            varchar(15) NOT NULL
                            CHECK (channel_type IN ('MPESA_PAYBILL','MPESA_TILL','BANK_ACCOUNT')),
    name                    varchar(100) NOT NULL,
    shortcode               varchar(20),                -- Paybill / Till number
    bank_name               varchar(100),
    bank_account_no_enc     bytea,
    -- Daraja credentials, encrypted by the app (AES-GCM, key outside the DB)
    consumer_key_enc        bytea,
    consumer_secret_enc     bytea,
    passkey_enc             bytea,
    environment             varchar(10) NOT NULL DEFAULT 'SANDBOX' CHECK (environment IN ('SANDBOX','PRODUCTION')),
    urls_registered_at      timestamptz,                -- C2B URLs registered with Safaricom
    active                  boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id)
);
-- A shortcode belongs to exactly one organisation on the platform
CREATE UNIQUE INDEX ux_payment_channels_shortcode
    ON payment_channels (channel_type, shortcode) WHERE active AND shortcode IS NOT NULL;

-- ---------------------------------------------------------------------
-- Raw M-Pesa callbacks (C2B confirmation and STK Push), kept for audit
-- and reprocessing. organization_id is NULL if the shortcode is unknown.
-- ---------------------------------------------------------------------
CREATE TABLE mpesa_transactions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid REFERENCES organizations(id),
    payment_channel_id      uuid REFERENCES payment_channels(id),
    source                  varchar(5)  NOT NULL CHECK (source IN ('C2B','STK')),
    trans_id                varchar(30),                -- M-Pesa receipt, e.g. SJK3XXXXXX
    trans_type              varchar(30),
    trans_time              timestamptz,
    amount                  numeric(14,2),
    business_shortcode      varchar(20),
    bill_ref_number         varchar(50),                -- the account number the tenant typed
    msisdn                  varchar(64),                -- may be masked/hashed by Safaricom
    payer_name              varchar(150),
    checkout_request_id     varchar(60),                -- STK only
    merchant_request_id     varchar(60),
    result_code             integer,
    result_desc             text,
    processing_status       varchar(12) NOT NULL DEFAULT 'RECEIVED'
                            CHECK (processing_status IN ('RECEIVED','MATCHED','UNMATCHED','DUPLICATE','FAILED','ERROR')),
    payment_id              uuid,                       -- set once a payment row is created
    raw_payload             jsonb NOT NULL,
    received_at             timestamptz NOT NULL DEFAULT now(),
    processed_at            timestamptz
);
-- The same M-Pesa receipt can never be processed twice
CREATE UNIQUE INDEX ux_mpesa_trans_id   ON mpesa_transactions (trans_id) WHERE trans_id IS NOT NULL;
CREATE UNIQUE INDEX ux_mpesa_checkout   ON mpesa_transactions (checkout_request_id) WHERE checkout_request_id IS NOT NULL;
CREATE INDEX ix_mpesa_org_status        ON mpesa_transactions (organization_id, processing_status);

-- ---------------------------------------------------------------------
-- Payments received from tenants (any method)
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    receipt_no          varchar(30) NOT NULL,
    tenant_id           uuid,                          -- NULL until matched
    lease_id            uuid,
    unit_id             uuid,
    property_id         uuid,
    payment_channel_id  uuid,
    method              varchar(10) NOT NULL CHECK (method IN ('MPESA','BANK','CHEQUE','CASH','CARD','OTHER')),
    source              varchar(12) NOT NULL DEFAULT 'MANUAL'
                        CHECK (source IN ('MPESA_C2B','MPESA_STK','MANUAL','BANK_IMPORT')),
    reference           varchar(60),                   -- M-Pesa receipt, cheque no., bank ref
    amount              numeric(14,2) NOT NULL CHECK (amount > 0),
    amount_allocated    numeric(14,2) NOT NULL DEFAULT 0,
    unallocated         numeric(14,2) GENERATED ALWAYS AS (amount - amount_allocated) STORED,
    payer_name          varchar(150),
    payer_phone         varchar(64),
    paid_at             timestamptz NOT NULL,
    status              varchar(12) NOT NULL DEFAULT 'UNMATCHED'
                        CHECK (status IN ('UNMATCHED','UNALLOCATED','PARTIAL','ALLOCATED','REVERSED')),
    reversed_at         timestamptz,
    reversal_reason     text,
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, receipt_no),
    FOREIGN KEY (organization_id, tenant_id)          REFERENCES tenants          (organization_id, id),
    FOREIGN KEY (organization_id, lease_id)           REFERENCES leases           (organization_id, id),
    FOREIGN KEY (organization_id, unit_id)            REFERENCES units            (organization_id, id),
    FOREIGN KEY (organization_id, property_id)        REFERENCES properties       (organization_id, id),
    FOREIGN KEY (organization_id, payment_channel_id) REFERENCES payment_channels (organization_id, id),
    CHECK (amount_allocated BETWEEN 0 AND amount),
    CHECK (status = 'UNMATCHED' OR tenant_id IS NOT NULL)
);
-- A bank/M-Pesa reference is recorded once per organisation (no double counting)
CREATE UNIQUE INDEX ux_payments_reference ON payments (organization_id, method, upper(reference))
    WHERE reference IS NOT NULL AND status <> 'REVERSED';
CREATE INDEX ix_payments_org_tenant  ON payments (organization_id, tenant_id, paid_at);
CREATE INDEX ix_payments_org_status  ON payments (organization_id, status);
CREATE INDEX ix_payments_org_paid_at ON payments (organization_id, property_id, paid_at);

ALTER TABLE mpesa_transactions ADD CONSTRAINT fk_mpesa_payment FOREIGN KEY (payment_id) REFERENCES payments(id);

-- ---------------------------------------------------------------------
-- Allocation of a payment (or credit note) to invoices
-- ---------------------------------------------------------------------
CREATE TABLE payment_allocations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    payment_id          uuid,
    credit_note_id      uuid,
    invoice_id          uuid NOT NULL,
    amount              numeric(14,2) NOT NULL CHECK (amount > 0),
    allocated_at        timestamptz NOT NULL DEFAULT now(),
    allocated_by        uuid,                          -- NULL = automatic
    FOREIGN KEY (organization_id, payment_id)     REFERENCES payments     (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, credit_note_id) REFERENCES credit_notes (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, invoice_id)     REFERENCES invoices     (organization_id, id),
    CHECK (num_nonnulls(payment_id, credit_note_id) = 1)
);
CREATE INDEX ix_alloc_payment ON payment_allocations (payment_id);
CREATE INDEX ix_alloc_credit  ON payment_allocations (credit_note_id);
CREATE INDEX ix_alloc_invoice ON payment_allocations (invoice_id);

-- Keeps invoices.amount_paid, payments.amount_allocated and
-- credit_notes.amount_allocated in step with the allocation rows.
-- The CHECK constraints on those tables then reject any over-allocation.
CREATE OR REPLACE FUNCTION sync_allocation_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            (CASE WHEN TG_OP <> 'INSERT' THEN OLD.invoice_id     END,
             CASE WHEN TG_OP <> 'INSERT' THEN OLD.payment_id     END,
             CASE WHEN TG_OP <> 'INSERT' THEN OLD.credit_note_id END),
            (CASE WHEN TG_OP <> 'DELETE' THEN NEW.invoice_id     END,
             CASE WHEN TG_OP <> 'DELETE' THEN NEW.payment_id     END,
             CASE WHEN TG_OP <> 'DELETE' THEN NEW.credit_note_id END)
        ) AS t(invoice_id, payment_id, credit_note_id)
    LOOP
        IF r.invoice_id IS NOT NULL THEN
            UPDATE invoices i
               SET amount_paid = COALESCE((SELECT sum(amount) FROM payment_allocations WHERE invoice_id = i.id), 0),
                   status = CASE
                       WHEN i.status IN ('DRAFT','VOID') THEN i.status
                       WHEN i.total - COALESCE((SELECT sum(amount) FROM payment_allocations WHERE invoice_id = i.id), 0)
                            - i.withholding_tax_amount = 0 THEN 'PAID'
                       WHEN COALESCE((SELECT sum(amount) FROM payment_allocations WHERE invoice_id = i.id), 0) > 0 THEN 'PARTIALLY_PAID'
                       ELSE 'ISSUED' END
             WHERE i.id = r.invoice_id;
        END IF;
        IF r.payment_id IS NOT NULL THEN
            UPDATE payments p
               SET amount_allocated = COALESCE((SELECT sum(amount) FROM payment_allocations WHERE payment_id = p.id), 0),
                   status = CASE
                       WHEN p.status = 'REVERSED' THEN p.status
                       WHEN p.tenant_id IS NULL THEN 'UNMATCHED'
                       WHEN COALESCE((SELECT sum(amount) FROM payment_allocations WHERE payment_id = p.id), 0) = p.amount THEN 'ALLOCATED'
                       WHEN COALESCE((SELECT sum(amount) FROM payment_allocations WHERE payment_id = p.id), 0) > 0 THEN 'PARTIAL'
                       ELSE 'UNALLOCATED' END,
                   updated_at = now()
             WHERE p.id = r.payment_id;
        END IF;
        IF r.credit_note_id IS NOT NULL THEN
            UPDATE credit_notes c
               SET amount_allocated = COALESCE((SELECT sum(amount) FROM payment_allocations WHERE credit_note_id = c.id), 0)
             WHERE c.id = r.credit_note_id;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_payment_allocations_sync
    AFTER INSERT OR UPDATE OR DELETE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION sync_allocation_totals();

CREATE TRIGGER trg_invoices_updated         BEFORE UPDATE ON invoices         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payments_updated         BEFORE UPDATE ON payments         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payment_channels_updated BEFORE UPDATE ON payment_channels FOR EACH ROW EXECUTE FUNCTION set_updated_at();
