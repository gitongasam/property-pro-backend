-- =====================================================================
-- V9: Reference data - plans, permissions, system roles, default
--     message templates, and the per-organisation defaults function
-- =====================================================================

-- Seeding system rows (organization_id NULL) needs the RLS bypass
SELECT set_config('app.bypass_rls', 'on', true);

-- ---------------------------------------------------------------------
-- Plans (prices are the starting proposal from the design doc)
-- ---------------------------------------------------------------------
INSERT INTO plans (code, name, description, monthly_price, annual_price, unit_limit, user_limit,
                   extra_unit_price, sms_quota_monthly, sort_order, is_public, features) VALUES
('STARTER', 'Starter', 'Individual landlords, up to 30 units', 1500, 15000, 30, 2, NULL, 100, 1, true,
 '{"MPESA_C2B":true,"STK_PUSH":false,"TENANT_PORTAL":false,"OWNER_PORTAL":false,"MAINTENANCE":true,
   "MAINTENANCE_VENDORS":false,"COMMERCIAL":false,"ETIMS":false,"CUSTOM_ROLES":false,"AUDIT_LOG":false,
   "BRANDING":false,"WHITE_LABEL":false,"API_ACCESS":false,"MFA":false}'),
('GROWTH', 'Growth', 'Growing managers, up to 150 units', 5000, 50000, 150, 5, 30, 500, 2, true,
 '{"MPESA_C2B":true,"STK_PUSH":true,"TENANT_PORTAL":true,"OWNER_PORTAL":true,"MAINTENANCE":true,
   "MAINTENANCE_VENDORS":true,"COMMERCIAL":false,"ETIMS":false,"CUSTOM_ROLES":false,"AUDIT_LOG":false,
   "BRANDING":false,"WHITE_LABEL":false,"API_ACCESS":false,"MFA":false}'),
('PROFESSIONAL', 'Professional', 'Management firms and commercial portfolios, up to 600 units', 15000, 150000, 600, 20, 25, 2000, 3, true,
 '{"MPESA_C2B":true,"STK_PUSH":true,"TENANT_PORTAL":true,"OWNER_PORTAL":true,"MAINTENANCE":true,
   "MAINTENANCE_VENDORS":true,"COMMERCIAL":true,"ETIMS":true,"CUSTOM_ROLES":true,"AUDIT_LOG":true,
   "BRANDING":true,"WHITE_LABEL":false,"API_ACCESS":false,"MFA":true}'),
('ENTERPRISE', 'Enterprise', 'Large agencies; custom pricing', 0, 0, NULL, NULL, NULL, 10000, 4, false,
 '{"MPESA_C2B":true,"STK_PUSH":true,"TENANT_PORTAL":true,"OWNER_PORTAL":true,"MAINTENANCE":true,
   "MAINTENANCE_VENDORS":true,"COMMERCIAL":true,"ETIMS":true,"CUSTOM_ROLES":true,"AUDIT_LOG":true,
   "BRANDING":true,"WHITE_LABEL":true,"API_ACCESS":true,"MFA":true}');

-- ---------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, module, description) VALUES
('PROPERTY_VIEW','PORTFOLIO','View properties'),
('PROPERTY_MANAGE','PORTFOLIO','Create, edit and archive properties'),
('UNIT_VIEW','PORTFOLIO','View units'),
('UNIT_MANAGE','PORTFOLIO','Create and edit units'),
('OWNER_VIEW','PORTFOLIO','View landlords/owners'),
('OWNER_MANAGE','PORTFOLIO','Create and edit landlords/owners'),
('TENANT_VIEW','LEASING','View tenants'),
('TENANT_MANAGE','LEASING','Create and edit tenants'),
('LEASE_VIEW','LEASING','View leases'),
('LEASE_MANAGE','LEASING','Create, activate and renew leases'),
('LEASE_TERMINATE','LEASING','Terminate leases and process move-out'),
('INVOICE_VIEW','BILLING','View invoices and statements'),
('INVOICE_CREATE','BILLING','Create and issue invoices'),
('INVOICE_VOID','BILLING','Void invoices'),
('CREDIT_NOTE_CREATE','BILLING','Issue credit notes'),
('METER_READING_RECORD','BILLING','Record meter readings'),
('CHARGE_TYPE_MANAGE','BILLING','Manage charge types'),
('PAYMENT_VIEW','PAYMENTS','View payments'),
('PAYMENT_RECORD','PAYMENTS','Record manual payments'),
('PAYMENT_RECONCILE','PAYMENTS','Match and allocate payments'),
('PAYMENT_REVERSE','PAYMENTS','Reverse payments'),
('PAYMENT_CHANNEL_MANAGE','PAYMENTS','Manage Paybill/bank settings'),
('EXPENSE_VIEW','EXPENSES','View expenses'),
('EXPENSE_CREATE','EXPENSES','Record expenses'),
('EXPENSE_APPROVE','EXPENSES','Approve or reject expenses'),
('STATEMENT_VIEW','STATEMENTS','View owner statements'),
('STATEMENT_GENERATE','STATEMENTS','Generate owner statements'),
('STATEMENT_APPROVE','STATEMENTS','Approve and send owner statements'),
('REMITTANCE_RECORD','STATEMENTS','Record remittances to owners'),
('MAINTENANCE_VIEW','MAINTENANCE','View maintenance requests'),
('MAINTENANCE_CREATE','MAINTENANCE','Raise maintenance requests'),
('MAINTENANCE_ASSIGN','MAINTENANCE','Assign requests to staff or vendors'),
('MAINTENANCE_UPDATE','MAINTENANCE','Update request progress'),
('VENDOR_MANAGE','MAINTENANCE','Manage vendors'),
('REPORT_VIEW','REPORTS','View reports and dashboard'),
('REPORT_EXPORT','REPORTS','Export reports'),
('NOTIFICATION_SEND','COMMS','Send SMS/email to tenants and owners'),
('TEMPLATE_MANAGE','COMMS','Edit message templates'),
('DOCUMENT_VIEW','DOCUMENTS','View documents'),
('DOCUMENT_UPLOAD','DOCUMENTS','Upload documents'),
('USER_MANAGE','SETTINGS','Invite and manage staff users'),
('ROLE_MANAGE','SETTINGS','Manage custom roles'),
('ORG_SETTINGS_MANAGE','SETTINGS','Edit organisation settings'),
('SUBSCRIPTION_MANAGE','SETTINGS','Change plan and pay subscription'),
('AUDIT_VIEW','SETTINGS','View audit log'),
('PORTAL_TENANT','PORTAL','Tenant self-service portal'),
('PORTAL_OWNER','PORTAL','Owner/landlord portal'),
('PLATFORM_ADMIN','PLATFORM','Super admin: manage all organisations and plans');

-- ---------------------------------------------------------------------
-- System roles (organization_id NULL, shared by all organisations)
-- ---------------------------------------------------------------------
INSERT INTO roles (organization_id, code, name, description, is_system) VALUES
(NULL,'SUPER_ADMIN','Super Admin','Platform operator', true),
(NULL,'ORG_OWNER','Organisation Owner','Full access to the organisation, including billing', true),
(NULL,'PROPERTY_MANAGER','Property Manager','Day-to-day management of assigned properties', true),
(NULL,'ACCOUNTANT','Accountant','Payments, reconciliation, expenses and owner statements', true),
(NULL,'CARETAKER','Caretaker / Agent','Field work: readings, inspections, maintenance', true),
(NULL,'OWNER','Landlord / Owner','Owner portal access', true),
(NULL,'TENANT','Tenant','Tenant portal access', true);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r, permissions p
 WHERE r.organization_id IS NULL AND (
       (r.code = 'SUPER_ADMIN' AND p.code = 'PLATFORM_ADMIN')
    OR (r.code = 'ORG_OWNER'   AND p.module NOT IN ('PORTAL','PLATFORM'))
    OR (r.code = 'PROPERTY_MANAGER' AND (
            p.module IN ('PORTFOLIO','LEASING','MAINTENANCE','DOCUMENTS')
         OR p.code IN ('INVOICE_VIEW','INVOICE_CREATE','METER_READING_RECORD','PAYMENT_VIEW',
                       'EXPENSE_VIEW','EXPENSE_CREATE','REPORT_VIEW','NOTIFICATION_SEND','STATEMENT_VIEW')))
    OR (r.code = 'ACCOUNTANT' AND (
            p.module IN ('BILLING','PAYMENTS','EXPENSES','STATEMENTS','REPORTS')
         OR p.code IN ('PROPERTY_VIEW','UNIT_VIEW','OWNER_VIEW','TENANT_VIEW','LEASE_VIEW',
                       'DOCUMENT_VIEW','DOCUMENT_UPLOAD','NOTIFICATION_SEND','AUDIT_VIEW')))
    OR (r.code = 'CARETAKER' AND p.code IN ('PROPERTY_VIEW','UNIT_VIEW','TENANT_VIEW','METER_READING_RECORD',
                                            'MAINTENANCE_VIEW','MAINTENANCE_CREATE','MAINTENANCE_UPDATE',
                                            'DOCUMENT_UPLOAD'))
    OR (r.code = 'OWNER'  AND p.code = 'PORTAL_OWNER')
    OR (r.code = 'TENANT' AND p.code = 'PORTAL_TENANT')
 );

-- ---------------------------------------------------------------------
-- Default message templates (an organisation can override any by code)
-- ---------------------------------------------------------------------
INSERT INTO message_templates (organization_id, code, channel, subject, body) VALUES
(NULL,'INVOICE_ISSUED','SMS',NULL,
 'Dear {{tenant_name}}, your {{period}} invoice {{invoice_no}} of KES {{amount}} for {{unit}} is due on {{due_date}}. Pay via Paybill {{paybill}}, Acc {{payment_code}}.'),
(NULL,'RENT_REMINDER','SMS',NULL,
 'Hi {{tenant_name}}, a reminder that KES {{balance}} for {{unit}} is due on {{due_date}}. Paybill {{paybill}}, Acc {{payment_code}}. Thank you.'),
(NULL,'PAYMENT_RECEIPT','SMS',NULL,
 'Received KES {{amount}} from {{tenant_name}} for {{unit}}, ref {{reference}}. Receipt {{receipt_no}}. Balance KES {{balance}}. Thank you.'),
(NULL,'ARREARS_NOTICE','SMS',NULL,
 'Dear {{tenant_name}}, your account for {{unit}} is in arrears of KES {{balance}}. Kindly clear by {{deadline}} to avoid penalties. Paybill {{paybill}}, Acc {{payment_code}}.'),
(NULL,'LEASE_EXPIRY','SMS',NULL,
 'Dear {{tenant_name}}, your lease for {{unit}} ends on {{end_date}}. Please contact {{org_name}} on {{org_phone}} about renewal.'),
(NULL,'MAINTENANCE_UPDATE','SMS',NULL,
 'Update on request {{ticket_no}} ({{title}}): {{status}}. {{org_name}}'),
(NULL,'OTP_CODE','SMS',NULL,
 'Your {{org_name}} verification code is {{code}}. It expires in 5 minutes. Do not share it.'),
(NULL,'INVOICE_ISSUED','EMAIL','Invoice {{invoice_no}} for {{unit}}',
 '<p>Dear {{tenant_name}},</p><p>Please find attached invoice {{invoice_no}} of KES {{amount}}, due on {{due_date}}.</p><p>Pay via M-Pesa Paybill {{paybill}}, account {{payment_code}}.</p><p>{{org_name}}</p>'),
(NULL,'OWNER_STATEMENT','EMAIL','Your statement for {{period}}',
 '<p>Dear {{owner_name}},</p><p>Attached is your statement for {{period}}. Net payable: KES {{net_payable}}.</p><p>{{org_name}}</p>');

-- ---------------------------------------------------------------------
-- Called by the sign-up service right after an organisation is created:
--   SELECT create_organization_defaults('<org uuid>');
-- Creates the standard charge types. VAT rate defaults to 16% but only
-- applies when the lease has vat_applicable = true.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_organization_defaults(p_org uuid) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO charge_types (organization_id, code, name, category, is_taxable, vat_rate, owner_income, allocation_priority) VALUES
    (p_org, 'DEPOSIT',        'Security deposit', 'DEPOSIT', false,  0, false,  5),
    (p_org, 'RENT',           'Rent',             'RENT',    true,  16, true,  10),
    (p_org, 'SERVICE_CHARGE', 'Service charge',   'SERVICE', true,  16, true,  20),
    (p_org, 'WATER',          'Water',            'UTILITY', false,  0, true,  30),
    (p_org, 'ELECTRICITY',    'Electricity',      'UTILITY', false,  0, true,  31),
    (p_org, 'GARBAGE',        'Garbage collection','SERVICE',false,  0, true,  32),
    (p_org, 'PARKING',        'Parking',          'OTHER',   true,  16, true,  40),
    (p_org, 'OTHER',          'Other charges',    'OTHER',   false,  0, true,  60),
    (p_org, 'PENALTY',        'Late payment penalty','PENALTY',false, 0, true,  90)
    ON CONFLICT (organization_id, code) DO NOTHING;
$$;
