-- =====================================================================
-- V6: Expenses, owner statements, maintenance, inspections, documents,
--     notifications and audit log
-- =====================================================================

-- ---------------------------------------------------------------------
-- Vendors / contractors (plumbers, electricians, security firms)
-- ---------------------------------------------------------------------
CREATE TABLE vendors (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    name                varchar(150) NOT NULL,
    service_category    varchar(30),
    contact_person      varchar(150),
    phone               varchar(20),
    email               citext,
    kra_pin             varchar(20),
    mpesa_phone         varchar(20),
    notes               text,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id)
);

-- ---------------------------------------------------------------------
-- Maintenance
-- ---------------------------------------------------------------------
CREATE TABLE maintenance_requests (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    ticket_no           varchar(30) NOT NULL,
    property_id         uuid NOT NULL,
    unit_id             uuid,                       -- NULL = common area
    tenant_id           uuid,                       -- set when raised by a tenant
    raised_by           uuid,                       -- user id
    title               varchar(200) NOT NULL,
    description         text,
    category            varchar(15) NOT NULL DEFAULT 'OTHER'
                        CHECK (category IN ('PLUMBING','ELECTRICAL','CARPENTRY','PAINTING','ROOFING','PEST_CONTROL',
                                            'LIFT','GENERATOR','SECURITY','CLEANING','APPLIANCE','OTHER')),
    priority            varchar(10) NOT NULL DEFAULT 'MEDIUM'
                        CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    status              varchar(12) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','ON_HOLD','RESOLVED','CLOSED','CANCELLED')),
    assigned_to         uuid,                       -- staff user (caretaker)
    vendor_id           uuid,
    scheduled_date      date,
    resolved_at         timestamptz,
    estimated_cost      numeric(14,2),
    actual_cost         numeric(14,2),
    charge_to           varchar(12) NOT NULL DEFAULT 'OWNER'
                        CHECK (charge_to IN ('OWNER','TENANT','ORGANIZATION')),
    tenant_rating       smallint CHECK (tenant_rating BETWEEN 1 AND 5),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, ticket_no),
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id),
    FOREIGN KEY (organization_id, unit_id)     REFERENCES units      (organization_id, id),
    FOREIGN KEY (organization_id, tenant_id)   REFERENCES tenants    (organization_id, id),
    FOREIGN KEY (organization_id, vendor_id)   REFERENCES vendors    (organization_id, id),
    FOREIGN KEY (organization_id, assigned_to) REFERENCES users      (organization_id, id)
);
CREATE INDEX ix_maint_org_status   ON maintenance_requests (organization_id, status);
CREATE INDEX ix_maint_org_property ON maintenance_requests (organization_id, property_id);
CREATE INDEX ix_maint_assigned     ON maintenance_requests (organization_id, assigned_to) WHERE status IN ('ASSIGNED','IN_PROGRESS');

CREATE TABLE maintenance_updates (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    request_id          uuid NOT NULL,
    user_id             uuid,
    comment             text,
    status_from         varchar(12),
    status_to           varchar(12),
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, request_id) REFERENCES maintenance_requests (organization_id, id) ON DELETE CASCADE
);

-- Preventive maintenance (lifts, generators, fire equipment) for commercial buildings
CREATE TABLE maintenance_schedules (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    property_id         uuid NOT NULL,
    title               varchar(200) NOT NULL,
    category            varchar(15) NOT NULL DEFAULT 'OTHER',
    frequency_months    smallint NOT NULL CHECK (frequency_months > 0),
    next_due_date       date NOT NULL,
    vendor_id           uuid,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, property_id) REFERENCES properties (organization_id, id),
    FOREIGN KEY (organization_id, vendor_id)   REFERENCES vendors    (organization_id, id)
);

-- Move-in / move-out / routine inspections (checklist in JSON)
CREATE TABLE inspections (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    unit_id             uuid NOT NULL,
    lease_id            uuid,
    inspection_type     varchar(10) NOT NULL CHECK (inspection_type IN ('MOVE_IN','MOVE_OUT','ROUTINE')),
    inspection_date     date NOT NULL,
    inspected_by        uuid,
    items               jsonb NOT NULL DEFAULT '[]'::jsonb,   -- [{"area":"Kitchen","item":"Sink","condition":"GOOD","photo":"..."}]
    deposit_deductions  numeric(14,2) NOT NULL DEFAULT 0 CHECK (deposit_deductions >= 0),
    notes               text,
    tenant_signed_at    timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, unit_id)  REFERENCES units  (organization_id, id),
    FOREIGN KEY (organization_id, lease_id) REFERENCES leases (organization_id, id)
);

-- ---------------------------------------------------------------------
-- Owner statements and remittances (declared before expenses so an
-- expense can record which statement it was charged on)
-- ---------------------------------------------------------------------
CREATE TABLE owner_statements (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    statement_no        varchar(30) NOT NULL,
    owner_id            uuid NOT NULL,
    period_start        date NOT NULL,
    period_end          date NOT NULL,
    opening_balance     numeric(14,2) NOT NULL DEFAULT 0,   -- carried from last statement
    collections         numeric(14,2) NOT NULL DEFAULT 0,   -- owner income collected in the period
    expenses            numeric(14,2) NOT NULL DEFAULT 0,
    management_fee      numeric(14,2) NOT NULL DEFAULT 0,
    management_fee_vat  numeric(14,2) NOT NULL DEFAULT 0,
    adjustments         numeric(14,2) NOT NULL DEFAULT 0,
    net_payable         numeric(14,2) GENERATED ALWAYS AS
                        (opening_balance + collections - expenses - management_fee - management_fee_vat + adjustments) STORED,
    amount_remitted     numeric(14,2) NOT NULL DEFAULT 0,
    status              varchar(10) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','APPROVED','SENT','PAID')),
    approved_by         uuid,
    approved_at         timestamptz,
    sent_at             timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, statement_no),
    UNIQUE (organization_id, owner_id, period_start),
    FOREIGN KEY (organization_id, owner_id) REFERENCES owners (organization_id, id),
    CHECK (period_end >= period_start)
);

CREATE TABLE owner_statement_lines (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    statement_id        uuid NOT NULL,
    line_type           varchar(12) NOT NULL CHECK (line_type IN ('COLLECTION','EXPENSE','FEE','ADJUSTMENT')),
    property_id         uuid,
    unit_id             uuid,
    description         varchar(255) NOT NULL,
    amount              numeric(14,2) NOT NULL,
    source_type         varchar(20),          -- PAYMENT / EXPENSE
    source_id           uuid,
    FOREIGN KEY (organization_id, statement_id) REFERENCES owner_statements (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_owner_stmt_lines ON owner_statement_lines (organization_id, statement_id);

CREATE TABLE owner_remittances (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    owner_id            uuid NOT NULL,
    statement_id        uuid,
    amount              numeric(14,2) NOT NULL CHECK (amount > 0),
    method              varchar(10) NOT NULL CHECK (method IN ('BANK','MPESA','CHEQUE','CASH')),
    reference           varchar(60),
    paid_at             timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    FOREIGN KEY (organization_id, owner_id)     REFERENCES owners           (organization_id, id),
    FOREIGN KEY (organization_id, statement_id) REFERENCES owner_statements (organization_id, id)
);

-- ---------------------------------------------------------------------
-- Expenses
-- ---------------------------------------------------------------------
CREATE TABLE expenses (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    expense_no              varchar(30) NOT NULL,
    property_id             uuid,                    -- NULL = organisation overhead
    unit_id                 uuid,
    vendor_id               uuid,
    maintenance_request_id  uuid,
    category                varchar(15) NOT NULL
                            CHECK (category IN ('REPAIRS','UTILITIES','SECURITY','CLEANING','GARDENING','INSURANCE',
                                                'LAND_RATES','LEGAL','SALARIES','COMMISSION','OFFICE','OTHER')),
    description             varchar(255) NOT NULL,
    amount                  numeric(14,2) NOT NULL CHECK (amount > 0),
    vat_amount              numeric(14,2) NOT NULL DEFAULT 0,
    expense_date            date NOT NULL,
    charge_to               varchar(12) NOT NULL DEFAULT 'OWNER'
                            CHECK (charge_to IN ('OWNER','TENANT','ORGANIZATION')),
    payment_method          varchar(10) CHECK (payment_method IN ('MPESA','BANK','CHEQUE','CASH','CARD','OTHER')),
    reference               varchar(60),
    status                  varchar(16) NOT NULL DEFAULT 'PENDING_APPROVAL'
                            CHECK (status IN ('PENDING_APPROVAL','APPROVED','PAID','REJECTED')),
    approved_by             uuid,
    approved_at             timestamptz,
    owner_statement_id      uuid,                    -- set when included in an owner statement
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, expense_no),
    FOREIGN KEY (organization_id, property_id)            REFERENCES properties           (organization_id, id),
    FOREIGN KEY (organization_id, unit_id)                REFERENCES units                (organization_id, id),
    FOREIGN KEY (organization_id, vendor_id)              REFERENCES vendors              (organization_id, id),
    FOREIGN KEY (organization_id, maintenance_request_id) REFERENCES maintenance_requests (organization_id, id),
    FOREIGN KEY (organization_id, owner_statement_id)     REFERENCES owner_statements     (organization_id, id),
    CHECK (charge_to <> 'OWNER' OR property_id IS NOT NULL)
);
CREATE INDEX ix_expenses_org_property ON expenses (organization_id, property_id, expense_date);
CREATE INDEX ix_expenses_unstated     ON expenses (organization_id, property_id)
    WHERE owner_statement_id IS NULL AND status IN ('APPROVED','PAID') AND charge_to = 'OWNER';

-- ---------------------------------------------------------------------
-- Documents (files live in S3-compatible storage; this is the index)
-- ---------------------------------------------------------------------
CREATE TABLE documents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    entity_type         varchar(15) NOT NULL
                        CHECK (entity_type IN ('ORGANIZATION','OWNER','PROPERTY','UNIT','TENANT','LEASE','INVOICE',
                                               'PAYMENT','EXPENSE','MAINTENANCE','INSPECTION','STATEMENT')),
    entity_id           uuid NOT NULL,
    category            varchar(20) NOT NULL DEFAULT 'OTHER'
                        CHECK (category IN ('LEASE_AGREEMENT','ID_COPY','KRA_PIN','TITLE_DEED','RECEIPT','INVOICE_PDF',
                                            'STATEMENT_PDF','PHOTO','INSPECTION','WHT_CERTIFICATE','OTHER')),
    name                varchar(255) NOT NULL,
    storage_key         text NOT NULL,          -- object key in the bucket, e.g. org/<uuid>/leases/<uuid>.pdf
    mime_type           varchar(100),
    size_bytes          bigint CHECK (size_bytes >= 0),
    uploaded_by         uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz
);
CREATE INDEX ix_documents_entity ON documents (organization_id, entity_type, entity_id);

-- ---------------------------------------------------------------------
-- Messaging
-- ---------------------------------------------------------------------
-- organization_id NULL = platform default template; an org can override by code
CREATE TABLE message_templates (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid REFERENCES organizations(id),
    code                varchar(40) NOT NULL,     -- RENT_REMINDER, PAYMENT_RECEIPT, ARREARS_NOTICE...
    channel             varchar(8)  NOT NULL CHECK (channel IN ('SMS','EMAIL','IN_APP')),
    subject             varchar(200),
    body                text NOT NULL,            -- placeholders: {{tenant_name}}, {{balance}}, {{due_date}}
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_templates_org_code_channel ON message_templates (organization_id, code, channel) NULLS NOT DISTINCT;

CREATE TABLE notifications (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    channel             varchar(8)  NOT NULL CHECK (channel IN ('SMS','EMAIL','IN_APP')),
    recipient_type      varchar(10) NOT NULL CHECK (recipient_type IN ('TENANT','OWNER','USER','VENDOR')),
    recipient_id        uuid,
    to_address          varchar(255) NOT NULL,    -- phone number or email
    template_code       varchar(40),
    subject             varchar(200),
    body                text NOT NULL,
    status              varchar(10) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED','SENT','DELIVERED','FAILED','READ')),
    provider_message_id varchar(100),
    sms_segments        smallint,                 -- counted against the plan's SMS quota
    cost                numeric(10,4),
    error               text,
    attempts            smallint NOT NULL DEFAULT 0,
    sent_at             timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_queue    ON notifications (status, created_at) WHERE status IN ('QUEUED','FAILED');
CREATE INDEX ix_notifications_org_time ON notifications (organization_id, created_at);
CREATE INDEX ix_notifications_inapp    ON notifications (organization_id, recipient_id) WHERE channel = 'IN_APP' AND status <> 'READ';

-- ---------------------------------------------------------------------
-- Audit log: who changed money and lease records, and what changed.
-- Written by trigger; append-only (UPDATE/DELETE revoked in V7).
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id     uuid,
    user_id             uuid,
    table_name          varchar(60) NOT NULL,
    record_id           uuid,
    action              varchar(6) NOT NULL CHECK (action IN ('INSERT','UPDATE','DELETE')),
    old_values          jsonb,
    new_values          jsonb,
    changed_fields      text[],
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_record  ON audit_logs (table_name, record_id);
CREATE INDEX ix_audit_org_time ON audit_logs (organization_id, created_at);

CREATE OR REPLACE FUNCTION audit_row_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_old jsonb := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
    v_new jsonb := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END;
    v_changed text[];
BEGIN
    IF TG_OP = 'UPDATE' THEN
        SELECT array_agg(n.key ORDER BY n.key) INTO v_changed
          FROM jsonb_each(v_new) n
         WHERE n.key NOT IN ('updated_at')
           AND n.value IS DISTINCT FROM v_old -> n.key;
        IF v_changed IS NULL THEN
            RETURN NULL;   -- nothing meaningful changed
        END IF;
    END IF;

    INSERT INTO audit_logs (organization_id, user_id, table_name, record_id, action, old_values, new_values, changed_fields)
    VALUES (COALESCE(v_new ->> 'organization_id', v_old ->> 'organization_id')::uuid,
            app_current_user(),
            TG_TABLE_NAME,
            COALESCE(v_new ->> 'id', v_old ->> 'id')::uuid,
            TG_OP, v_old, v_new, v_changed);
    RETURN NULL;
END;
$$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['units','leases','lease_charges','invoices','credit_notes','payments',
                             'payment_allocations','expenses','owner_statements','owner_remittances','payment_channels']
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%I_audit AFTER INSERT OR UPDATE OR DELETE ON %I
                        FOR EACH ROW EXECUTE FUNCTION audit_row_change()', t, t);
    END LOOP;
END $$;

-- updated_at triggers
CREATE TRIGGER trg_vendors_updated   BEFORE UPDATE ON vendors              FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_maint_updated     BEFORE UPDATE ON maintenance_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ostmt_updated     BEFORE UPDATE ON owner_statements     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_expenses_updated  BEFORE UPDATE ON expenses             FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_templates_updated BEFORE UPDATE ON message_templates    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
