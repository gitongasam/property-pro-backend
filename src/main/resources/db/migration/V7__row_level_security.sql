-- =====================================================================
-- V7: Row-Level Security (tenant isolation) and grants for the app role
--
-- Layer 1: the app adds organization_id to every query (Hibernate filter)
-- Layer 2: these policies make PostgreSQL refuse any row from another
--          organisation even if layer 1 has a bug.
--
-- FORCE ROW LEVEL SECURITY means even the table owner is filtered, so
-- connecting with the wrong account does not silently bypass isolation.
-- =====================================================================

-- 1. Every table with an organization_id column: own org only
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.table_name
          FROM information_schema.columns c
          JOIN information_schema.tables tb
            ON tb.table_name = c.table_name AND tb.table_schema = c.table_schema
         WHERE c.table_schema = 'public'
           AND c.column_name  = 'organization_id'
           AND tb.table_type  = 'BASE TABLE'
           AND c.table_name NOT IN ('roles','message_templates')   -- handled below (shared system rows)
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$
            CREATE POLICY tenant_isolation ON %I
                USING      (app_bypass_rls() OR organization_id = app_current_org())
                WITH CHECK (app_bypass_rls() OR organization_id = app_current_org())
        $p$, t);
    END LOOP;
END $$;

-- 2. organizations: an org sees only itself
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organizations
    USING      (app_bypass_rls() OR id = app_current_org())
    WITH CHECK (app_bypass_rls() OR id = app_current_org());

-- 3. roles and message_templates: shared system rows (organization_id NULL)
--    are readable by everyone; orgs can only write their own rows
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['roles','message_templates']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$
            CREATE POLICY shared_read ON %I FOR SELECT
                USING (app_bypass_rls() OR organization_id IS NULL OR organization_id = app_current_org())
        $p$, t);
        EXECUTE format($p$
            CREATE POLICY own_write ON %I FOR ALL
                USING      (app_bypass_rls() OR organization_id = app_current_org())
                WITH CHECK (app_bypass_rls() OR organization_id = app_current_org())
        $p$, t);
    END LOOP;
END $$;

-- 4. role_permissions has no organization_id: visible when its role is visible
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY via_role_read ON role_permissions FOR SELECT
    USING (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id));
CREATE POLICY via_role_write ON role_permissions FOR ALL
    USING      (app_bypass_rls() OR EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id AND r.organization_id = app_current_org()))
    WITH CHECK (app_bypass_rls() OR EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id AND r.organization_id = app_current_org()));

-- 5. refresh_tokens: only through the auth service (bypass) or own org's users
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY via_user ON refresh_tokens
    USING      (app_bypass_rls() OR EXISTS (SELECT 1 FROM users u WHERE u.id = user_id))
    WITH CHECK (app_bypass_rls() OR EXISTS (SELECT 1 FROM users u WHERE u.id = user_id));

-- plans and permissions stay readable by all (no RLS); only migrations change them.

-- ---------------------------------------------------------------------
-- Grants for the application role (created outside Flyway, see
-- docker/init/00-roles.sql). The app role must NOT own the tables and
-- must NOT have BYPASSRLS.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT USAGE ON SCHEMA public TO propertypro_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO propertypro_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO propertypro_app;
        -- Functions are executable by PUBLIC by default; nothing to grant

        -- Reference data is changed only by migrations
        REVOKE INSERT, UPDATE, DELETE ON plans, permissions FROM propertypro_app;
        -- Audit log is append-only
        REVOKE UPDATE, DELETE ON audit_logs FROM propertypro_app;
        -- Flyway's history table is not the app's business
        IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
            REVOKE ALL ON flyway_schema_history FROM propertypro_app;
        END IF;

        -- Tables added by future migrations get the same grants
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO propertypro_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT USAGE, SELECT ON SEQUENCES TO propertypro_app;
    END IF;
END $$;
