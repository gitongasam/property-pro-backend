-- =====================================================================
-- V18: Paid-subscription expiry messages and password reset
--
-- SubscriptionJobs moves an ACTIVE subscription to PAST_DUE when its period
-- ends (grace period, still writable) and it turns read-only when the grace
-- ends; the owner is told at each step and reminded before renewal.
-- Password reset: one-time links (only the SHA-256 is stored), 1 hour.
-- =====================================================================

SELECT set_config('app.bypass_rls', 'on', true);

CREATE TABLE password_reset_tokens (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      varchar(64) NOT NULL UNIQUE,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    requested_ip    inet,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_reset_user_time ON password_reset_tokens (user_id, created_at DESC);

-- Only the auth service (system context) touches reset tokens
ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY system_only ON password_reset_tokens
    USING (app_bypass_rls()) WITH CHECK (app_bypass_rls());

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_tokens TO propertypro_app;
    END IF;
END $$;

-- Variables: first_name, org_name, plan_name, amount, period_end, grace_end, link
INSERT INTO message_templates (organization_id, code, channel, subject, body) VALUES
(NULL,'SUBSCRIPTION_RENEWAL_DUE','EMAIL','Your PropertyPro {{plan_name}} plan renews on {{period_end}}',
 '<p>Hi {{first_name}},</p><p>Your {{plan_name}} subscription for {{org_name}} runs until {{period_end}}. Renew now (KES {{amount}}) to keep billing, collections and reports running without a break.</p><p><a href="{{link}}">Renew subscription</a></p>'),
(NULL,'SUBSCRIPTION_RENEWAL_DUE','SMS',NULL,
 'Hi {{first_name}}, your PropertyPro {{plan_name}} plan for {{org_name}} ends on {{period_end}}. Renew (KES {{amount}}) to avoid interruption: {{link}}'),
(NULL,'SUBSCRIPTION_PAST_DUE','EMAIL','Your PropertyPro subscription has expired: renew by {{grace_end}}',
 '<p>Hi {{first_name}},</p><p>Your {{plan_name}} subscription for {{org_name}} ended on {{period_end}}. Everything keeps working until {{grace_end}}; after that the account becomes read-only until you renew.</p><p><a href="{{link}}">Renew now (KES {{amount}})</a></p>'),
(NULL,'SUBSCRIPTION_PAST_DUE','SMS',NULL,
 'Hi {{first_name}}, your PropertyPro subscription for {{org_name}} has expired. Renew by {{grace_end}} to avoid read-only mode: {{link}}'),
(NULL,'SUBSCRIPTION_LAPSED','EMAIL','Your PropertyPro account is now read-only',
 '<p>Hi {{first_name}},</p><p>The subscription for {{org_name}} was not renewed, so the account is now read-only: automatic billing, penalties and reminders have stopped. Your data is safe. Renew to pick up where you left off.</p><p><a href="{{link}}">Renew now (KES {{amount}})</a></p>'),
(NULL,'SUBSCRIPTION_LAPSED','SMS',NULL,
 'Hi {{first_name}}, your PropertyPro account for {{org_name}} is now read-only and automatic rent billing has stopped. Renew to continue: {{link}}')
ON CONFLICT DO NOTHING;
