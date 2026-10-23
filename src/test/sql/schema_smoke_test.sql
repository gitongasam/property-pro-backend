-- =====================================================================
-- Schema smoke test. Run as the APP role (not the owner):
--   psql -U propertypro_app -d propertypro -v ON_ERROR_STOP=1 -f schema_smoke_test.sql
-- Everything runs in one transaction and is rolled back at the end.
-- Each check raises an error if the database does not behave as designed.
-- =====================================================================
BEGIN;

-- ---------------------------------------------------------------------
-- Setup: two organisations (A and B), each with owner, property, unit,
-- tenant and an active lease. Done with the bypass, like the sign-up service.
-- ---------------------------------------------------------------------
SELECT set_config('app.bypass_rls', 'on', true);

INSERT INTO organizations (id, name, slug, status) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', 'Acacia Properties', 'acacia', 'ACTIVE'),
 ('bbbbbbbb-0000-0000-0000-000000000001', 'Baobab Realty',     'baobab', 'ACTIVE');

SELECT create_organization_defaults('aaaaaaaa-0000-0000-0000-000000000001');
SELECT create_organization_defaults('bbbbbbbb-0000-0000-0000-000000000001');

INSERT INTO owners (id, organization_id, name, management_fee_value) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 'Jane Wanjiku', 8),
 ('bbbbbbbb-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000001', 'Peter Otieno', 10);

INSERT INTO properties (id, organization_id, owner_id, code, name, property_type, county, town, estate) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000002',
  'KLM', 'Kilimani Heights', 'RESIDENTIAL', 'Nairobi', 'Nairobi', 'Kilimani'),
 ('bbbbbbbb-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002',
  'EMB', 'Embu Plaza', 'COMMERCIAL', 'Embu', 'Embu', 'CBD');

INSERT INTO units (id, organization_id, property_id, unit_no, unit_type, market_rent, deposit_amount, payment_code, status) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000003',
  'B4', 'TWO_BR', 25000, 25000, 'KLM-B4', 'OCCUPIED'),
 ('aaaaaaaa-0000-0000-0000-000000000014', 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000003',
  'B5', 'TWO_BR', 25000, 25000, 'KLM-B5', 'VACANT'),
 ('bbbbbbbb-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000003',
  'Shop 1', 'SHOP', 40000, 80000, 'EMB-S1', 'OCCUPIED');

INSERT INTO tenants (id, organization_id, name, phone, account_code) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000001', 'Mary Njeri', '+254700000001', 'T0001'),
 ('bbbbbbbb-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000001', 'Mwangi Hardware Ltd', '+254700000002', 'T0001');

INSERT INTO leases (id, organization_id, lease_no, unit_id, tenant_id, status, start_date, end_date, rent_amount, deposit_amount) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000006', 'aaaaaaaa-0000-0000-0000-000000000001', 'LSE-2026-000001',
  'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000005', 'ACTIVE', '2026-01-01', '2026-12-31', 25000, 25000),
 ('bbbbbbbb-0000-0000-0000-000000000006', 'bbbbbbbb-0000-0000-0000-000000000001', 'LSE-2026-000001',
  'bbbbbbbb-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000005', 'ACTIVE', '2026-01-01', NULL, 40000, 80000);

-- Leave bypass mode: from here on we act as a normal request for org A
SELECT set_config('app.bypass_rls', 'off', true);
SELECT set_config('app.current_org',  'aaaaaaaa-0000-0000-0000-000000000001', true);
SELECT set_config('app.current_user', 'aaaaaaaa-0000-0000-0000-0000000000ff', true);

DO $$
DECLARE
    n int;
    v_inv_no varchar;
    v_inv uuid;
    v_pay uuid;
    v_rent_type uuid;
BEGIN
    -- 1. Tenant isolation on reads ------------------------------------
    SELECT count(*) INTO n FROM units;
    ASSERT n = 2, format('1a: org A should see its 2 units, saw %s', n);
    SELECT count(*) INTO n FROM tenants WHERE id = 'bbbbbbbb-0000-0000-0000-000000000005';
    ASSERT n = 0, '1b: org A must not see org B tenant by id';
    SELECT count(*) INTO n FROM organizations;
    ASSERT n = 1, '1c: org A must see only its own organisation row';
    SELECT count(*) INTO n FROM roles;
    ASSERT n = 7, format('1d: system roles visible to every org, saw %s', n);
    RAISE NOTICE 'PASS 1: reads are isolated per organisation';

    -- 2. Tenant isolation on writes -----------------------------------
    BEGIN
        INSERT INTO tenants (organization_id, name, phone, account_code)
        VALUES ('bbbbbbbb-0000-0000-0000-000000000001', 'Intruder', '+254711111111', 'X1');
        RAISE EXCEPTION '2a: insert into another org should have failed';
    EXCEPTION WHEN insufficient_privilege THEN NULL;   -- RLS WITH CHECK violation
    END;
    UPDATE units SET market_rent = 1 WHERE id = 'bbbbbbbb-0000-0000-0000-000000000004';
    GET DIAGNOSTICS n = ROW_COUNT;
    ASSERT n = 0, '2b: update of another org''s unit must touch 0 rows';
    RAISE NOTICE 'PASS 2: writes into another organisation are refused';

    -- 3. Composite foreign keys: cannot link to another org's row ------
    BEGIN
        INSERT INTO units (organization_id, property_id, unit_no, unit_type, payment_code)
        VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000003', 'X', 'SHOP', 'X-1');
        RAISE EXCEPTION '3: unit pointing at org B property should have failed';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS 3: cross-organisation references are impossible';

    -- 4. No overlapping live leases on one unit ------------------------
    BEGIN
        INSERT INTO leases (organization_id, lease_no, unit_id, tenant_id, status, start_date, rent_amount)
        VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'LSE-2026-000002',
                'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000005',
                'ACTIVE', '2026-06-01', 26000);
        RAISE EXCEPTION '4: overlapping active lease should have failed';
    EXCEPTION WHEN exclusion_violation THEN NULL;
    END;
    -- A lease starting after the current one ends is fine
    INSERT INTO leases (organization_id, lease_no, unit_id, tenant_id, status, start_date, rent_amount)
    VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'LSE-2026-000003',
            'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000005',
            'ACTIVE', '2027-01-01', 26000);
    RAISE NOTICE 'PASS 4: a unit cannot have two overlapping active leases';

    -- 5. Document numbering --------------------------------------------
    v_inv_no := next_document_number(app_current_org(), 'INVOICE', '2026-11-01');
    ASSERT v_inv_no = 'INV-2026-000001', format('5a: got %s', v_inv_no);
    ASSERT next_document_number(app_current_org(), 'INVOICE', '2026-11-01') = 'INV-2026-000002', '5b';
    ASSERT next_document_number(app_current_org(), 'RECEIPT', '2026-11-01') = 'RCT-2026-000001', '5c';
    RAISE NOTICE 'PASS 5: per-organisation document numbers (%)', v_inv_no;

    -- 6. Invoice, payment, allocation keep balances in step ------------
    SELECT id INTO v_rent_type FROM charge_types WHERE code = 'RENT';
    INSERT INTO invoices (organization_id, invoice_no, tenant_id, lease_id, unit_id, property_id, source,
                          period_start, period_end, issue_date, due_date, subtotal, vat_amount, total, status)
    VALUES (app_current_org(), v_inv_no, 'aaaaaaaa-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000006',
            'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000003', 'SCHEDULED',
            '2026-09-01', '2026-09-30', '2026-09-01', '2026-09-05', 25000, 0, 25000, 'ISSUED')
    RETURNING id INTO v_inv;
    INSERT INTO invoice_lines (organization_id, invoice_id, charge_type_id, description, unit_price, amount)
    VALUES (app_current_org(), v_inv, v_rent_type, 'Rent September 2026', 25000, 25000);

    -- Same lease, same period, scheduled twice -> refused
    BEGIN
        INSERT INTO invoices (organization_id, invoice_no, tenant_id, lease_id, source, period_start, due_date, total, subtotal, status)
        VALUES (app_current_org(), 'INV-DUP', 'aaaaaaaa-0000-0000-0000-000000000005',
                'aaaaaaaa-0000-0000-0000-000000000006', 'SCHEDULED', '2026-09-01', '2026-09-05', 25000, 25000, 'ISSUED');
        RAISE EXCEPTION '6a: duplicate scheduled invoice should have failed';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    INSERT INTO payments (organization_id, receipt_no, tenant_id, lease_id, unit_id, property_id, method, source,
                          reference, amount, paid_at, status)
    VALUES (app_current_org(), 'RCT-2026-000001', 'aaaaaaaa-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000006',
            'aaaaaaaa-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000003', 'MPESA', 'MPESA_C2B',
            'SJK3ABC123', 20000, '2026-09-03 10:15+03', 'UNALLOCATED')
    RETURNING id INTO v_pay;

    INSERT INTO payment_allocations (organization_id, payment_id, invoice_id, amount)
    VALUES (app_current_org(), v_pay, v_inv, 20000);

    PERFORM 1 FROM invoices WHERE id = v_inv AND amount_paid = 20000 AND balance = 5000 AND status = 'PARTIALLY_PAID';
    ASSERT FOUND, '6b: invoice should be PARTIALLY_PAID with 5,000 balance';
    PERFORM 1 FROM payments WHERE id = v_pay AND amount_allocated = 20000 AND unallocated = 0 AND status = 'ALLOCATED';
    ASSERT FOUND, '6c: payment should be fully ALLOCATED';

    -- Allocating more than the payment holds is refused
    BEGIN
        INSERT INTO payment_allocations (organization_id, payment_id, invoice_id, amount)
        VALUES (app_current_org(), v_pay, v_inv, 1000);
        RAISE EXCEPTION '6d: over-allocation should have failed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    -- Same M-Pesa receipt recorded twice is refused
    BEGIN
        INSERT INTO payments (organization_id, receipt_no, tenant_id, method, reference, amount, paid_at, status)
        VALUES (app_current_org(), 'RCT-2026-000099', 'aaaaaaaa-0000-0000-0000-000000000005', 'MPESA', 'sjk3abc123',
                20000, now(), 'UNALLOCATED');
        RAISE EXCEPTION '6e: duplicate M-Pesa reference should have failed';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    RAISE NOTICE 'PASS 6: allocations update invoice and payment balances; duplicates refused';

    -- 7. Reporting views respect RLS ------------------------------------
    PERFORM 1 FROM v_arrears_ageing WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000005' AND total_outstanding = 5000;
    ASSERT FOUND, '7a: arrears view should show 5,000 for Mary Njeri';
    PERFORM 1 FROM v_tenant_balances WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000005' AND net_balance = 5000;
    ASSERT FOUND, '7b: tenant balance should be 5,000';
    SELECT count(*) INTO n FROM v_property_occupancy;
    ASSERT n = 1, '7c: org A sees only its own property in the occupancy view';
    PERFORM 1 FROM v_property_occupancy WHERE occupancy_pct = 50.0 AND vacancy_loss = 25000;
    ASSERT FOUND, '7d: Kilimani Heights should be 50% occupied with 25,000 vacancy loss';
    RAISE NOTICE 'PASS 7: reporting views return only this organisation''s figures';

    -- 8. Audit log ----------------------------------------------------
    SELECT count(*) INTO n FROM audit_logs WHERE table_name = 'invoices' AND record_id = v_inv;
    ASSERT n >= 2, format('8a: invoice insert + payment update should be audited, found %s', n);
    PERFORM 1 FROM audit_logs WHERE table_name = 'invoices' AND record_id = v_inv AND action = 'UPDATE'
                                AND 'amount_paid' = ANY(changed_fields) AND user_id = app_current_user();
    ASSERT FOUND, '8b: audit row should record changed fields and the user';
    BEGIN
        DELETE FROM audit_logs;
        RAISE EXCEPTION '8c: app must not be able to delete audit logs';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    RAISE NOTICE 'PASS 8: money changes are audited; audit log is append-only';
END $$;

-- 9. Org B sees only its own data, including in views
SELECT set_config('app.current_org', 'bbbbbbbb-0000-0000-0000-000000000001', true);
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM invoices;
    ASSERT n = 0, '9a: org B must not see org A invoices';
    SELECT count(*) INTO n FROM v_arrears_ageing;
    ASSERT n = 0, '9b: org B arrears view must be empty';
    SELECT count(*) INTO n FROM units;
    ASSERT n = 1, '9c: org B sees its 1 unit';
    RAISE NOTICE 'PASS 9: switching organisation switches the data';
END $$;

-- 10. No context set at all (a forgotten filter) -> sees nothing
SELECT set_config('app.current_org', '', true);
DO $$
DECLARE n int;
BEGIN
    SELECT count(*) INTO n FROM units;
    ASSERT n = 0, '10: with no organisation set, nothing is visible';
    RAISE NOTICE 'PASS 10: no organisation context = no data (fail closed)';
END $$;

ROLLBACK;
