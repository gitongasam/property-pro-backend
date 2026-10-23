-- Runs once when the Postgres container is first created.
-- Two accounts:
--   propertypro_owner : owns the schema, used ONLY by Flyway migrations
--   propertypro_app   : used by the Spring Boot app at runtime
--                       (not the owner, no BYPASSRLS, so RLS always applies)
-- Change these passwords; in production take them from a secret manager.

CREATE ROLE propertypro_owner LOGIN PASSWORD 'change-me-owner';
CREATE ROLE propertypro_app   LOGIN PASSWORD 'change-me-app' NOSUPERUSER NOBYPASSRLS;

CREATE DATABASE propertypro OWNER propertypro_owner;
\connect propertypro
ALTER SCHEMA public OWNER TO propertypro_owner;
GRANT CONNECT ON DATABASE propertypro TO propertypro_app;
