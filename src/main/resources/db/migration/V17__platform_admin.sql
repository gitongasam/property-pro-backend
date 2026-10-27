-- =====================================================================
-- V17: Platform admin (super admin console)
--
-- Platform users (user_type PLATFORM, organization_id NULL) manage every
-- organisation through /api/v1/admin/**. Three permission levels:
--   PLATFORM_ADMIN    everything (suspend, plans, data)
--   PLATFORM_BILLING  subscriptions and trials
--   PLATFORM_SUPPORT  read-only views
-- Every admin command is written to admin_actions with the reason, which the
-- row-level audit_logs cannot capture.
-- =====================================================================

SELECT set_config('app.bypass_rls', 'on', true);

INSERT INTO permissions (code, module, description) VALUES
('PLATFORM_BILLING','PLATFORM','Platform: manage subscriptions, trials and plan changes'),
('PLATFORM_SUPPORT','PLATFORM','Platform: read-only view of all organisations');

INSERT INTO roles (organization_id, code, name, description, is_system) VALUES
(NULL,'PLATFORM_BILLING_AGENT','Billing Agent','Platform staff: subscriptions and trials', true),
(NULL,'PLATFORM_SUPPORT_AGENT','Support Agent','Platform staff: read-only support', true);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r, permissions p
 WHERE r.organization_id IS NULL AND (
       (r.code = 'SUPER_ADMIN'            AND p.module = 'PLATFORM')
    OR (r.code = 'PLATFORM_BILLING_AGENT' AND p.code IN ('PLATFORM_BILLING','PLATFORM_SUPPORT'))
    OR (r.code = 'PLATFORM_SUPPORT_AGENT' AND p.code = 'PLATFORM_SUPPORT'))
ON CONFLICT DO NOTHING;

-- Why an organisation was suspended (shown to the customer and to admins)
ALTER TABLE organizations
    ADD COLUMN suspended_at     timestamptz,
    ADD COLUMN suspended_reason text;

CREATE TABLE admin_actions (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_user_id       uuid        NOT NULL REFERENCES users(id),
    organization_id     uuid        REFERENCES organizations(id),
    action              varchar(40) NOT NULL,      -- SUSPEND, REACTIVATE, EXTEND_TRIAL, ...
    reason              text,
    payload             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_actions_org_time ON admin_actions (organization_id, created_at DESC);

-- Platform-only: organisations never see admin_actions, even their own rows
ALTER TABLE admin_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_actions FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON admin_actions
    USING (app_bypass_rls()) WITH CHECK (app_bypass_rls());

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT, INSERT ON admin_actions TO propertypro_app;
        REVOKE UPDATE, DELETE ON admin_actions FROM propertypro_app;   -- append-only
    END IF;
END $$;
