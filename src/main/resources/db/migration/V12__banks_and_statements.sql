-- =====================================================================
-- V12: Banks as reference data, and bank statement uploads
--
-- Each bank (or Sacco) row says which Paybill tenants pay through, what
-- they type as the account, and how PropertyPro learns about payments:
--   API       : the bank sends a payment notification (needs a connector)
--   STATEMENT : the landlord uploads the bank statement (CSV)
-- A bank can be switched from STATEMENT to API once its connector exists.
-- =====================================================================

CREATE TABLE banks (
    code                varchar(20) PRIMARY KEY,
    name                varchar(100) NOT NULL,
    paybill             varchar(10),                -- NULL: each customer has their own (most Saccos)
    -- What tenants type as the account. {account} = the landlord's bank account,
    -- {code} = the unit's payment code. Confirm the format with each bank.
    account_format      varchar(40) NOT NULL DEFAULT '{account}#{code}',
    integration         varchar(10) NOT NULL DEFAULT 'STATEMENT' CHECK (integration IN ('API','STATEMENT')),
    sort_order          integer NOT NULL DEFAULT 100,
    active              boolean NOT NULL DEFAULT true
);

-- Paybill numbers as published by the banks; check them before go-live
INSERT INTO banks (code, name, paybill, integration, sort_order) VALUES
('KCB',      'KCB Bank',                 '522522', 'API',       10),
('EQUITY',   'Equity Bank',              '247247', 'STATEMENT', 20),
('COOP',     'Co-operative Bank',        '400200', 'STATEMENT', 30),
('NCBA',     'NCBA Bank',                '880100', 'STATEMENT', 40),
('ABSA',     'Absa Bank Kenya',          '303030', 'STATEMENT', 50),
('STANBIC',  'Stanbic Bank',             '600100', 'STATEMENT', 60),
('IM',       'I&M Bank',                 '542542', 'STATEMENT', 70),
('DTB',      'Diamond Trust Bank (DTB)', '516600', 'STATEMENT', 80),
('FAMILY',   'Family Bank',              '222111', 'STATEMENT', 90),
('KINGDOM',  'Kingdom Bank',             '529901', 'STATEMENT', 100),
('OTHER',    'Other bank or Sacco',      NULL,     'STATEMENT', 999);

-- Read-only for the app; changed only through migrations
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT ON banks TO propertypro_app;
    END IF;
END $$;

-- Channels point at a bank row instead of a fixed list
ALTER TABLE payment_channels DROP CONSTRAINT payment_channels_bank_code_check;
ALTER TABLE payment_channels ALTER COLUMN bank_code TYPE varchar(20);
ALTER TABLE payment_channels ADD CONSTRAINT fk_payment_channels_bank FOREIGN KEY (bank_code) REFERENCES banks(code);

-- ---------------------------------------------------------------------
-- bank_transactions now also holds statement lines
-- ---------------------------------------------------------------------
ALTER TABLE bank_transactions ALTER COLUMN bank_code TYPE varchar(20);
ALTER TABLE bank_transactions ADD COLUMN source varchar(10) NOT NULL DEFAULT 'IPN'
    CHECK (source IN ('IPN','STATEMENT'));
ALTER TABLE bank_transactions ADD COLUMN balance_after numeric(14,2);   -- running balance on the statement line
ALTER TABLE bank_transactions DROP CONSTRAINT bank_transactions_processing_status_check;
ALTER TABLE bank_transactions ADD CONSTRAINT bank_transactions_processing_status_check
    CHECK (processing_status IN ('RECEIVED','MATCHED','UNMATCHED','DUPLICATE','ERROR'));
