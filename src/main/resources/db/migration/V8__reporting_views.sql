-- =====================================================================
-- V8: Reporting views
-- security_invoker = true makes each view run with the caller's
-- permissions, so Row-Level Security still applies through the view.
-- =====================================================================

-- Per-tenant balance: what is owed, and any unallocated money on account
CREATE VIEW v_tenant_balances WITH (security_invoker = true) AS
SELECT t.organization_id,
       t.id                                                   AS tenant_id,
       t.name                                                 AS tenant_name,
       t.phone,
       COALESCE(inv.total_invoiced, 0)                        AS total_invoiced,
       COALESCE(inv.total_paid, 0)                            AS total_paid,
       COALESCE(inv.outstanding, 0)                           AS outstanding,
       COALESCE(pay.unallocated, 0)                           AS unallocated_payments,
       COALESCE(inv.outstanding, 0) - COALESCE(pay.unallocated, 0) AS net_balance
  FROM tenants t
  LEFT JOIN LATERAL (
        SELECT sum(i.total)        AS total_invoiced,
               sum(i.amount_paid)  AS total_paid,
               sum(i.balance)      AS outstanding
          FROM invoices i
         WHERE i.organization_id = t.organization_id
           AND i.tenant_id = t.id
           AND i.status NOT IN ('DRAFT','VOID')
       ) inv ON true
  LEFT JOIN LATERAL (
        SELECT sum(p.unallocated) AS unallocated
          FROM payments p
         WHERE p.organization_id = t.organization_id
           AND p.tenant_id = t.id
           AND p.status IN ('UNALLOCATED','PARTIAL')
       ) pay ON true
 WHERE t.deleted_at IS NULL;

-- Arrears ageing per tenant and unit (buckets by days past due date)
CREATE VIEW v_arrears_ageing WITH (security_invoker = true) AS
SELECT i.organization_id,
       i.property_id,
       i.unit_id,
       i.tenant_id,
       t.name                                                                     AS tenant_name,
       sum(i.balance) FILTER (WHERE current_date <= i.due_date)                   AS not_yet_due,
       sum(i.balance) FILTER (WHERE current_date - i.due_date BETWEEN 1  AND 30)  AS days_1_30,
       sum(i.balance) FILTER (WHERE current_date - i.due_date BETWEEN 31 AND 60)  AS days_31_60,
       sum(i.balance) FILTER (WHERE current_date - i.due_date BETWEEN 61 AND 90)  AS days_61_90,
       sum(i.balance) FILTER (WHERE current_date - i.due_date > 90)               AS days_over_90,
       sum(i.balance)                                                             AS total_outstanding,
       min(i.due_date)                                                            AS oldest_due_date
  FROM invoices i
  JOIN tenants t ON t.organization_id = i.organization_id AND t.id = i.tenant_id
 WHERE i.status IN ('ISSUED','PARTIALLY_PAID')
   AND i.balance > 0
 GROUP BY i.organization_id, i.property_id, i.unit_id, i.tenant_id, t.name;

-- Occupancy and rent roll per property
CREATE VIEW v_property_occupancy WITH (security_invoker = true) AS
SELECT p.organization_id,
       p.id                                                           AS property_id,
       p.name                                                         AS property_name,
       count(u.id)                                                    AS total_units,
       count(u.id) FILTER (WHERE u.status = 'OCCUPIED')               AS occupied_units,
       count(u.id) FILTER (WHERE u.status = 'VACANT')                 AS vacant_units,
       round(100.0 * count(u.id) FILTER (WHERE u.status = 'OCCUPIED')
             / NULLIF(count(u.id) FILTER (WHERE u.status <> 'UNAVAILABLE'), 0), 1) AS occupancy_pct,
       COALESCE(sum(u.market_rent), 0)                                AS potential_monthly_rent,
       COALESCE(sum(cl.monthly_rent), 0)                              AS actual_monthly_rent,
       COALESCE(sum(u.market_rent) FILTER (WHERE u.status = 'VACANT'), 0) AS vacancy_loss
  FROM properties p
  LEFT JOIN units u
         ON u.organization_id = p.organization_id AND u.property_id = p.id AND u.deleted_at IS NULL
  -- The lease running today (a signed future lease on the same unit is ignored),
  -- with rent converted to a monthly figure
  LEFT JOIN LATERAL (
        SELECT l.rent_amount / CASE l.billing_frequency
                                   WHEN 'MONTHLY' THEN 1 WHEN 'QUARTERLY' THEN 3
                                   WHEN 'SEMI_ANNUAL' THEN 6 ELSE 12 END AS monthly_rent
          FROM leases l
         WHERE l.organization_id = u.organization_id AND l.unit_id = u.id
           AND l.status IN ('ACTIVE','NOTICE_GIVEN') AND l.deleted_at IS NULL
           AND l.start_date <= current_date
         ORDER BY l.start_date DESC
         LIMIT 1
       ) cl ON true
 WHERE p.deleted_at IS NULL
 GROUP BY p.organization_id, p.id, p.name;

-- Rent roll: every live lease with its tenant, unit and balance
CREATE VIEW v_rent_roll WITH (security_invoker = true) AS
SELECT l.organization_id,
       p.id               AS property_id,
       p.name             AS property_name,
       u.id               AS unit_id,
       u.unit_no,
       u.payment_code,
       t.id               AS tenant_id,
       t.name             AS tenant_name,
       t.phone            AS tenant_phone,
       l.id               AS lease_id,
       l.lease_no,
       l.status           AS lease_status,
       (l.start_date > current_date) AS is_future,   -- signed but not started yet
       l.start_date,
       l.end_date,
       l.rent_amount,
       l.billing_frequency,
       l.next_escalation_date,
       COALESCE((SELECT sum(i.balance) FROM invoices i
                  WHERE i.organization_id = l.organization_id AND i.lease_id = l.id
                    AND i.status IN ('ISSUED','PARTIALLY_PAID')), 0) AS balance
  FROM leases l
  JOIN units u      ON u.organization_id = l.organization_id AND u.id = l.unit_id
  JOIN properties p ON p.organization_id = u.organization_id AND p.id = u.property_id
  JOIN tenants t    ON t.organization_id = l.organization_id AND t.id = l.tenant_id
 WHERE l.status IN ('ACTIVE','NOTICE_GIVEN')
   AND l.deleted_at IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'propertypro_app') THEN
        GRANT SELECT ON v_tenant_balances, v_arrears_ageing, v_property_occupancy, v_rent_roll TO propertypro_app;
    END IF;
END $$;
