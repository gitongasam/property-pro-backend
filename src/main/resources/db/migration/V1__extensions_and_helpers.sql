-- =====================================================================
-- V1: Extensions and helper functions
-- PropertyPro SaaS - PostgreSQL 16
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive email
CREATE EXTENSION IF NOT EXISTS btree_gist; -- exclusion constraint on leases (no overlapping leases per unit)

-- ---------------------------------------------------------------------
-- Request context, set by the Spring Boot app at the start of every
-- transaction:
--   SET LOCAL app.current_org  = '<organization uuid>';
--   SET LOCAL app.current_user = '<user uuid>';
--   SET LOCAL app.bypass_rls   = 'on';   -- ONLY for super admin, login,
--                                         -- webhooks and scheduled jobs
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION app_current_org() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.current_org', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app_current_user() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.current_user', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app_bypass_rls() RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(current_setting('app.bypass_rls', true), '') = 'on'
$$;

-- Keeps updated_at current on every UPDATE
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
