-- =====================================================================
-- V15: Phone confirmation codes and the lease SMS
--
-- A new account confirms its mobile number with a 6-digit SMS code before
-- it can use the app (onboarding included). One open code per user; only a
-- hash of it is stored. Accounts that existed before this migration are
-- treated as confirmed so nobody already using the app is locked out.
-- =====================================================================

CREATE TABLE phone_verification_codes (
    user_id             uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    phone               varchar(20) NOT NULL,
    code_hash           varchar(64) NOT NULL,              -- SHA-256 of user id + code
    expires_at          timestamptz NOT NULL,
    attempts            smallint NOT NULL DEFAULT 0,       -- wrong guesses against this code
    sends_in_window     smallint NOT NULL DEFAULT 0,       -- codes sent since window_started_at
    window_started_at   timestamptz NOT NULL DEFAULT now(),
    last_sent_at        timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now()
);

UPDATE users SET phone_verified_at = now()
 WHERE phone_verified_at IS NULL AND phone IS NOT NULL AND deleted_at IS NULL;

-- Sent to the tenant when a lease is activated, and again on request
INSERT INTO message_templates (organization_id, code, channel, subject, body) VALUES
(NULL,'LEASE_ACTIVATED','SMS',NULL,
 'Dear {{tenant_name}}, your lease {{lease_no}} for {{unit}}, {{property}} starts {{start_date}}{{end_date}}. Rent KES {{rent}} {{frequency}}, billed on day {{billing_day}} and due within {{due_days}} days. Deposit KES {{deposit}}. Pay via Paybill {{paybill}}, Acc {{payment_code}}. {{org_name}}')
ON CONFLICT DO NOTHING;
