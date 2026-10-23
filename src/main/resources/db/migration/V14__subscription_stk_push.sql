-- =====================================================================
-- V14: Subscription payments by KCB Buni STK Push
--
-- An upgrade sends a "Pay KES x" prompt to the owner's phone. The row is
-- PENDING until KCB calls back with the result; on success the plan in
-- plan_id/billing_cycle becomes the organisation's live subscription.
-- subscription_payments already has tenant isolation (V7).
-- =====================================================================

ALTER TABLE subscription_payments
    ADD COLUMN plan_id              uuid REFERENCES plans(id),
    ADD COLUMN billing_cycle        varchar(10) CHECK (billing_cycle IN ('MONTHLY','ANNUAL')),
    ADD COLUMN phone                varchar(20),
    ADD COLUMN checkout_request_id  varchar(100),
    ADD COLUMN merchant_request_id  varchar(100),
    ADD COLUMN result_code          integer,
    ADD COLUMN result_desc          text,
    ADD COLUMN requested_by         uuid REFERENCES users(id);

CREATE UNIQUE INDEX ux_subscription_payments_checkout
    ON subscription_payments (checkout_request_id) WHERE checkout_request_id IS NOT NULL;
