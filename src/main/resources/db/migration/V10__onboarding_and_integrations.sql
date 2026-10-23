-- =====================================================================
-- V10: Onboarding progress, staff invitations, M-Pesa webhook tokens,
--      opening balances for imported tenants
-- =====================================================================

-- Data changes below touch every organisation, so they need the RLS bypass
SELECT set_config('app.bypass_rls', 'on', true);

-- ---------------------------------------------------------------------
-- Onboarding: what the organisation told us at set-up and which
-- optional steps it skipped. Whether a step is DONE is worked out from
-- real data (properties, units, tenants...), never stored twice.
-- ---------------------------------------------------------------------
CREATE TABLE onboarding_progress (
    organization_id     uuid PRIMARY KEY REFERENCES organizations(id),
    business_type       varchar(12) CHECK (business_type IN ('LANDLORD','FIRM','AGENCY')),
    units_range         varchar(10) CHECK (units_range IN ('1-30','31-150','151-600','600+')),
    portfolio_focus     varchar(12) CHECK (portfolio_focus IN ('RESIDENTIAL','COMMERCIAL','MIXED')),
    skipped_steps       text[] NOT NULL DEFAULT '{}',
    completed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_onboarding_updated BEFORE UPDATE ON onboarding_progress FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- Staff invitations: a one-time link to set a password
-- ---------------------------------------------------------------------
CREATE TABLE user_invitations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          varchar(100) NOT NULL UNIQUE,
    expires_at          timestamptz NOT NULL,
    accepted_at         timestamptz,
    created_by          uuid,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_invitations_user ON user_invitations (user_id);

-- ---------------------------------------------------------------------
-- M-Pesa: each channel gets a secret token used in its callback URLs
-- (/webhooks/mpesa/<token>/confirmation), and a verified_at once a real
-- payment has arrived through it.
-- ---------------------------------------------------------------------
ALTER TABLE payment_channels ADD COLUMN webhook_token varchar(64);
ALTER TABLE payment_channels ADD COLUMN verified_at timestamptz;
UPDATE payment_channels SET webhook_token = encode(gen_random_bytes(24), 'hex') WHERE webhook_token IS NULL;
ALTER TABLE payment_channels ALTER COLUMN webhook_token SET NOT NULL;
ALTER TABLE payment_channels ALTER COLUMN webhook_token SET DEFAULT encode(gen_random_bytes(24), 'hex');
CREATE UNIQUE INDEX ux_payment_channels_webhook_token ON payment_channels (webhook_token);

-- ---------------------------------------------------------------------
-- Opening balances: what a tenant already owed when the organisation
-- moved onto PropertyPro becomes an OPENING invoice
-- ---------------------------------------------------------------------
ALTER TABLE invoices DROP CONSTRAINT invoices_source_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_source_check
    CHECK (source IN ('SCHEDULED','MANUAL','PENALTY','MOVE_IN','MOVE_OUT','OPENING'));

CREATE OR REPLACE FUNCTION create_organization_defaults(p_org uuid) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO charge_types (organization_id, code, name, category, is_taxable, vat_rate, owner_income, allocation_priority) VALUES
    (p_org, 'OPENING_BALANCE','Balance brought forward','OTHER', false,  0, true,   1),
    (p_org, 'DEPOSIT',        'Security deposit', 'DEPOSIT', false,  0, false,  5),
    (p_org, 'RENT',           'Rent',             'RENT',    true,  16, true,  10),
    (p_org, 'SERVICE_CHARGE', 'Service charge',   'SERVICE', true,  16, true,  20),
    (p_org, 'WATER',          'Water',            'UTILITY', false,  0, true,  30),
    (p_org, 'ELECTRICITY',    'Electricity',      'UTILITY', false,  0, true,  31),
    (p_org, 'GARBAGE',        'Garbage collection','SERVICE',false,  0, true,  32),
    (p_org, 'PARKING',        'Parking',          'OTHER',   true,  16, true,  40),
    (p_org, 'OTHER',          'Other charges',    'OTHER',   false,  0, true,  60),
    (p_org, 'PENALTY',        'Late payment penalty','PENALTY',false, 0, true,  90)
    ON CONFLICT (organization_id, code) DO NOTHING;
$$;

-- Existing organisations get the new charge type too
DO $$
DECLARE o uuid;
BEGIN
    FOR o IN SELECT id FROM organizations LOOP
        PERFORM create_organization_defaults(o);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- Row-Level Security for the new tables
-- ---------------------------------------------------------------------
ALTER TABLE onboarding_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onboarding_progress
    USING      (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

ALTER TABLE user_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_invitations
    USING      (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON onboarding_progress, user_invitations TO propertypro_app;
    END IF;
END $$;

-- Existing organisations get an onboarding row (treated as already completed)
INSERT INTO onboarding_progress (organization_id, completed_at)
SELECT id, now() FROM organizations
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Templates for staff invitations and the trial journey
-- ---------------------------------------------------------------------
INSERT INTO message_templates (organization_id, code, channel, subject, body) VALUES
(NULL,'STAFF_INVITE','EMAIL','{{inviter_name}} invited you to {{org_name}} on PropertyPro',
 '<p>Hi {{first_name}},</p><p>{{inviter_name}} has added you to <b>{{org_name}}</b> on PropertyPro as {{role_name}}.</p><p><a href="{{link}}">Set your password and sign in</a>. The link expires in 7 days.</p>'),
(NULL,'STAFF_INVITE','SMS',NULL,
 'Hi {{first_name}}, {{inviter_name}} added you to {{org_name}} on PropertyPro. Set your password here: {{link}} (expires in 7 days).'),
(NULL,'TRIAL_ADD_PROPERTY','EMAIL','Add your first property to PropertyPro',
 '<p>Hi {{first_name}},</p><p>Your {{org_name}} account is ready. Add your first property and its units — it takes about 5 minutes, or upload a spreadsheet.</p><p><a href="{{link}}">Continue setting up</a></p>'),
(NULL,'TRIAL_CONNECT_MPESA','EMAIL','Connect M-Pesa so rent reconciles itself',
 '<p>Hi {{first_name}},</p><p>Connect your Paybill or Till and every payment is matched to the right tenant automatically.</p><p><a href="{{link}}">Connect M-Pesa</a></p>'),
(NULL,'TRIAL_ENDING','EMAIL','Your PropertyPro trial ends on {{trial_end}}',
 '<p>Hi {{first_name}},</p><p>Your free trial for {{org_name}} ends on {{trial_end}}. Choose a plan to keep billing, collections and reports running.</p><p><a href="{{link}}">Choose a plan</a></p>'),
(NULL,'TRIAL_ENDING','SMS',NULL,
 'Hi {{first_name}}, your PropertyPro trial for {{org_name}} ends on {{trial_end}}. Choose a plan to keep things running: {{link}}'),
(NULL,'TRIAL_EXPIRED','EMAIL','Your PropertyPro trial has ended',
 '<p>Hi {{first_name}},</p><p>Your trial for {{org_name}} has ended and the account is now read-only. Your data is safe. Choose a plan to pick up where you left off.</p><p><a href="{{link}}">Choose a plan</a></p>')
ON CONFLICT DO NOTHING;
