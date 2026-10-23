-- =====================================================================
-- V11: Bank Paybills (KCB 522522 and similar)
--
-- Tenants pay the bank's shared Paybill with the landlord's bank account
-- plus their unit code as the account (e.g. 1234567890#KLM-B4). The bank
-- sends PropertyPro an Instant Payment Notification (IPN) for each credit.
-- =====================================================================

ALTER TABLE payment_channels DROP CONSTRAINT payment_channels_channel_type_check;
ALTER TABLE payment_channels ADD CONSTRAINT payment_channels_channel_type_check
    CHECK (channel_type IN ('MPESA_PAYBILL','MPESA_TILL','BANK_ACCOUNT','BANK_PAYBILL'));

-- Which bank's notification API the channel uses (only KCB for now)
ALTER TABLE payment_channels ADD COLUMN bank_code varchar(10) CHECK (bank_code IN ('KCB'));

-- A bank Paybill (522522) is shared by every KCB customer, so only an
-- organisation's OWN Paybill / Till must be unique on the platform
DROP INDEX ux_payment_channels_shortcode;
CREATE UNIQUE INDEX ux_payment_channels_shortcode
    ON payment_channels (channel_type, shortcode)
    WHERE active AND shortcode IS NOT NULL AND channel_type IN ('MPESA_PAYBILL','MPESA_TILL');

ALTER TABLE payments DROP CONSTRAINT payments_source_check;
ALTER TABLE payments ADD CONSTRAINT payments_source_check
    CHECK (source IN ('MPESA_C2B','MPESA_STK','MANUAL','BANK_IMPORT','BANK_IPN'));

-- ---------------------------------------------------------------------
-- Raw bank notifications, kept for audit and reprocessing
-- ---------------------------------------------------------------------
CREATE TABLE bank_transactions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    payment_channel_id      uuid NOT NULL REFERENCES payment_channels(id),
    bank_code               varchar(10) NOT NULL,
    transaction_reference   varchar(60) NOT NULL,       -- the bank's reference for the credit
    request_id              varchar(60),
    channel_code            varchar(30),                -- how the payer paid (M-Pesa, branch, transfer...)
    trans_time              timestamptz,
    amount                  numeric(14,2),
    currency                varchar(3),
    customer_reference      varchar(100),               -- the account the payer typed
    payer_name              varchar(150),
    payer_phone             varchar(64),
    narration               text,
    processing_status       varchar(12) NOT NULL DEFAULT 'RECEIVED'
                            CHECK (processing_status IN ('RECEIVED','MATCHED','UNMATCHED','ERROR')),
    result_desc             text,
    payment_id              uuid REFERENCES payments(id),
    raw_payload             jsonb NOT NULL,
    received_at             timestamptz NOT NULL DEFAULT now(),
    processed_at            timestamptz
);
-- The bank may retry a notification: the same credit is never processed twice
CREATE UNIQUE INDEX ux_bank_tx_reference ON bank_transactions (payment_channel_id, transaction_reference);
CREATE INDEX ix_bank_tx_org_status       ON bank_transactions (organization_id, processing_status);

ALTER TABLE bank_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_transactions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_transactions
    USING      (app_bypass_rls() OR organization_id = app_current_org())
    WITH CHECK (app_bypass_rls() OR organization_id = app_current_org());

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT, INSERT, UPDATE ON bank_transactions TO propertypro_app;
    END IF;
END $$;
