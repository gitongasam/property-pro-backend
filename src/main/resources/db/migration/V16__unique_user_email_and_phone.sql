-- =====================================================================
-- V16: One account per email address and per phone number, platform-wide
--
-- Until now they were unique only inside one organisation, so the same
-- person could sign up again and again. Deleted users (deleted_at set) do
-- not count, so a removed account's email/phone can be used again. email
-- is citext, so Jane@x.com and jane@x.com are the same address.
--
-- Duplicates must be resolved by hand first; this migration refuses to run
-- and lists them rather than picking which account keeps the address.
-- =====================================================================

DO $$
DECLARE dupes text;
BEGIN
    SELECT string_agg(format('%s %s (%s accounts)', kind, value, n), '; ') INTO dupes
      FROM (SELECT 'email' AS kind, email::text AS value, count(*) AS n FROM users
             WHERE deleted_at IS NULL AND email IS NOT NULL GROUP BY email HAVING count(*) > 1
            UNION ALL
            SELECT 'phone', phone, count(*) FROM users
             WHERE deleted_at IS NULL AND phone IS NOT NULL GROUP BY phone HAVING count(*) > 1) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot make user email/phone unique; resolve these duplicates first: %', dupes;
    END IF;
END $$;

DROP INDEX ux_users_org_email;
DROP INDEX ux_users_org_phone;

CREATE UNIQUE INDEX ux_users_email ON users (email) WHERE deleted_at IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE deleted_at IS NULL AND phone IS NOT NULL;
