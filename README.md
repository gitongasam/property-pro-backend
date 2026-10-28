# PropertyPro — Backend (Spring Boot 3.5, Java 21, PostgreSQL 16)

Multi-tenant property management API for Kenyan landlords, agencies and management firms.

| Module | What it does |
| --- | --- |
| **Tenancy** | Every request runs as one organisation, enforced twice: Hibernate `@TenantId` + PostgreSQL Row-Level Security |
| **Auth & team** | Sign-up (organisation + 14-day trial + owner), login, JWT + rotating refresh tokens, staff invitations by email link, roles, disable/enable |
| **Onboarding** | Set-up checklist (property → units → tenants → M-Pesa → team), business profile, skip steps, CSV imports of units and sitting tenants with opening balances, trial reminder emails |
| **Portfolio** | Owners (landlords), properties, units (single, bulk, CSV), occupancy |
| **Leasing** | Tenants (ID numbers encrypted), leases (draft → active → notice → terminated), extra charges, charge types |
| **Billing** | Move-in invoices with proration, scheduled monthly billing (escalations, metered water), penalties, one-off invoices, credit notes, withholding tax, void, tenant statements |
| **Payments** | Manual receipts, M-Pesa C2B (Paybill/Till) and STK push, bank Paybills (KCB, Equity, Co-op, DTB, Family, Kingdom, Saccos...) by bank notification or statement upload, automatic matching by account number, reconciliation suggestions, oldest-first allocation, reversals, SMS receipts |
| **Operations** | Expenses (approve/pay), maintenance board with history and tenant SMS, owner statements (collections − expenses − fee − VAT on fee) and remittances |
| **Reports** | Dashboard KPIs and 12-month chart, arrears ageing, occupancy and vacancy loss, rent roll, collections by property |
| **Settings** | Organisation profile, KRA PIN, defaults (VAT on fees, billing day, due days, reminders), payment channels |
| **Notifications** | Template-based SMS/email queue, sent every 30 s by a dispatcher (logging gateways until Africa's Talking / SMTP are plugged in) |

## Run it locally (Windows, no Docker)

You need Java 21, Maven 3.9+ and PostgreSQL 16 installed.

**1. Database and accounts** — in pgAdmin or `psql -U postgres`:

```sql
CREATE ROLE propertypro_app LOGIN PASSWORD 'change-me-app' NOBYPASSRLS;
CREATE DATABASE propertypro OWNER postgres;
\c propertypro
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

Flyway runs as `postgres` (your `DB_OWNER_USER`); the app runs as `propertypro_app`, which cannot bypass
Row-Level Security. Never point `DB_APP_USER` at `postgres` — isolation between organisations would be off.

**2. Start**

```powershell
$env:DB_OWNER_USER = "postgres"
$env:DB_OWNER_PASSWORD = "your-postgres-password"
$env:DB_APP_PASSWORD = "change-me-app"
mvn spring-boot:run          # Flyway migrates V1..V10 on startup; API on http://localhost:8080
```

- API docs: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- `mvn package -DskipTests` builds without Docker. `mvn test` runs every test; the integration tests need a
  database: Docker (Testcontainers starts one), or without Docker a separate local database whose name ends in
  `_test` (it is **wiped** at the start of every run):
  ```powershell
  # once: CREATE DATABASE propertypros_test;   (as postgres)
  $env:TEST_DB_URL = "jdbc:postgresql://localhost:5432/propertypros_test"
  mvn test
  ```
  `TEST_DB_OWNER_USER`/`TEST_DB_OWNER_PASSWORD` (default postgres/password) and `TEST_DB_APP_USER`/`TEST_DB_APP_PASSWORD`
  (default propertypro_app/change-me-app) override the accounts. Tests never send real SMS or email.

If you are upgrading a database created from the first backend zip, nothing to do: Flyway applies only V10.

## Configuration (environment variables)

| Variable | Default (dev only) | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/propertypro` | Database |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `propertypro_app` / `change-me-app` | Runtime account (no RLS bypass) |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `propertypro_owner` / `change-me-owner` | Flyway migrations only (locally: `postgres`) |
| `JWT_SECRET` | a dev-only value | Base64, at least 32 bytes: `openssl rand -base64 48` |
| `CRYPTO_KEY` | a dev-only value | Base64, exactly 32 bytes: `openssl rand -base64 32`. Encrypts ID numbers and Daraja secrets. **Never change it once data exists** |
| `KCB_IPN_PUBLIC_KEY` | empty (KCB notifications refused) | KCB's public key from the Buni portal (PEM or base64); verifies bank Paybill 522522 payment notifications |
| `KCB_VERIFY_SIGNATURE` | `true` | `false` only to post test KCB notifications locally |
| `CORS_ORIGINS` | `http://localhost:5173` | React dev server; comma-separated |
| `FRONTEND_URL` | `http://localhost:5173` | Used in invitation and reminder links |
| `PUBLIC_API_URL` | `http://localhost:8080` | Public HTTPS address Safaricom calls back (M-Pesa). Use an ngrok URL when testing locally |
| `JOBS_ENABLED` | `true` | Scheduled jobs and the notification dispatcher. Set `false` on all but one instance |

**Change every default before deploying.**

## Scheduled jobs (Africa/Nairobi)

| Time | Job |
| --- | --- |
| 05:30 | Billing: invoices every lease whose next billing date has come (catches up missed periods, applies escalations) |
| 06:00 | Late-payment penalties, per each lease's rule and grace days |
| 09:00 | Rent reminders before the due date |
| 10:00 | Trial nudges: day 1 no property, day 3 no M-Pesa, 4 days before the trial ends, and when it has ended |
| every 30 s | Send queued SMS and emails |

An ended trial is **read-only**, not locked: users can still see everything and are asked to choose a plan.

## How tenant isolation works

```
Request ─► JwtAuthenticationFilter ─► TenantContext (orgId, userId)
                                         │
             ┌───────────────────────────┴──────────────────────────┐
             ▼                                                      ▼
  TenantIdentifierResolver                              TenantAwareDataSource
  Hibernate adds organization_id = ?                    every connection checkout runs
  to every query on @TenantId entities                  set_config('app.current_org', …)
  and fills it on insert                                → PostgreSQL RLS policies (V7, V10)
```

- No organisation in context → both layers return **no rows** (fail closed).
- `SystemExecutor.asSystem(...)` is the only way to bypass isolation: sign-up, login, token refresh, accepting
  invitations, M-Pesa webhooks (looked up by the channel's secret token) and the jobs, which then switch to
  `asOrganization(...)` for each organisation. Search for `asSystem(` to review every use.

## API (v1)

All lists are paged: `?page=0&size=20&sort=name,asc` → `{content, page, size, totalElements, totalPages}`.

**Auth, team and organisation**

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/api/v1/auth/signup`, `/login`, `/refresh`, `/logout` | public |
| POST | `/api/v1/auth/accept-invite` `{token, password}` | public (invitation link) |
| GET | `/api/v1/auth/me` | signed in |
| GET | `/api/v1/plans` | public |
| GET | `/api/v1/users`, `/api/v1/users/roles` | USER_MANAGE |
| POST | `/api/v1/users/invite`, `/users/{id}/resend-invite` | USER_MANAGE (plan user limit applies) |
| PUT | `/api/v1/users/{id}/role` | USER_MANAGE |
| PATCH | `/api/v1/users/{id}/disable`, `/enable` | USER_MANAGE |
| GET / PUT | `/api/v1/organization` | signed in / ORG_SETTINGS_MANAGE |

**Onboarding**

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/api/v1/onboarding` — steps with DONE/SKIPPED/TODO, percent, next step, trial days left | signed in |
| PUT | `/api/v1/onboarding/profile` `{businessType, unitsRange, portfolioFocus}` | ORG_SETTINGS_MANAGE |
| POST / DELETE | `/api/v1/onboarding/steps/{TENANTS\|MPESA\|TEAM}/skip` | ORG_SETTINGS_MANAGE |
| POST | `/api/v1/onboarding/complete` | ORG_SETTINGS_MANAGE |
| GET | `/api/v1/onboarding/templates/{units\|tenants}.csv` | signed in |
| POST | `/api/v1/properties/{id}/units/import?dryRun=` (multipart `file`) | UNIT_MANAGE |
| POST | `/api/v1/onboarding/import/tenants?propertyId=&dryRun=` (multipart `file`) | TENANT_MANAGE + LEASE_MANAGE |

Imports check every row first. If any row is wrong, **nothing is saved** and you get
`errors: [{row, column, message}]` to show next to the file. `dryRun=true` is the "Check file" button.
A sitting tenant's "owed today" becomes an opening-balance invoice (`INV-…`, "Balance brought forward"),
and regular billing starts on the next billing day — no move-in invoice, no double billing.

**Portfolio and leasing**

| Method | Path | Permission |
| --- | --- | --- |
| GET/POST, GET/PUT/DELETE `{id}` | `/api/v1/owners` | OWNER_VIEW / OWNER_MANAGE |
| GET/POST, GET/PUT/DELETE `{id}`, PATCH `{id}/status` | `/api/v1/properties` | PROPERTY_VIEW / PROPERTY_MANAGE |
| GET/POST, POST `/bulk` | `/api/v1/properties/{id}/units` | UNIT_VIEW / UNIT_MANAGE |
| GET/PUT/DELETE, PATCH `/status` | `/api/v1/units/{id}` | UNIT_VIEW / UNIT_MANAGE |
| GET/POST, GET/PUT/DELETE `{id}` | `/api/v1/tenants` | TENANT_VIEW / TENANT_MANAGE |
| GET/POST, GET/PUT `{id}` | `/api/v1/leases` | LEASE_VIEW / LEASE_MANAGE |
| POST | `/api/v1/leases/{id}/activate`, `/notice` | LEASE_MANAGE |
| POST | `/api/v1/leases/{id}/terminate` | LEASE_TERMINATE |
| GET | `/api/v1/charge-types` | LEASE_VIEW or INVOICE_VIEW |

**Billing and payments**

| Method | Path | Permission |
| --- | --- | --- |
| GET, GET `{id}`, POST | `/api/v1/invoices` | INVOICE_VIEW / INVOICE_CREATE |
| POST | `/api/v1/invoices/{id}/void` | INVOICE_VOID |
| POST | `/api/v1/invoices/{id}/withholding-tax` | PAYMENT_RECORD |
| POST | `/api/v1/invoices/run-billing` | INVOICE_CREATE |
| POST | `/api/v1/credit-notes` | CREDIT_NOTE_CREATE |
| GET, GET `{id}`, GET `/sheet?propertyId=&utility=` | `/api/v1/meter-readings` | METER_READING_RECORD or INVOICE_VIEW |
| POST, POST `/bulk`, PUT/DELETE `{id}` (latest unbilled only) | `/api/v1/meter-readings` | METER_READING_RECORD |
| GET | `/api/v1/tenants/{id}/statement?from=&to=` | INVOICE_VIEW |
| GET, GET `{id}`, POST | `/api/v1/payments` | PAYMENT_VIEW / PAYMENT_RECORD |
| GET `{id}/suggestions`, POST `{id}/match` | `/api/v1/payments` | PAYMENT_RECONCILE |
| POST | `/api/v1/payments/{id}/reverse` | PAYMENT_REVERSE |
| POST | `/api/v1/payments/stk-push` | PAYMENT_RECORD (plan feature STK_PUSH) |
| GET/POST, PUT/DELETE `{id}`, POST `{id}/register-urls` | `/api/v1/payment-channels` | PAYMENT_CHANNEL_MANAGE |
| POST | `/api/v1/webhooks/mpesa/{token}/validation`, `/confirmation`, `/stk` | Safaricom (secret token in URL) |

**Operations and reports**

| Method | Path | Permission |
| --- | --- | --- |
| GET/POST, PUT `{id}` | `/api/v1/expenses` | EXPENSE_VIEW / EXPENSE_CREATE |
| POST | `/api/v1/expenses/{id}/approve`, `/reject`, `/paid` | EXPENSE_APPROVE |
| GET/POST | `/api/v1/maintenance-requests` (`?open=true` for the board) | MAINTENANCE_VIEW / MAINTENANCE_CREATE |
| PATCH | `/api/v1/maintenance-requests/{id}/assign`, `/status` | MAINTENANCE_ASSIGN / MAINTENANCE_UPDATE |
| GET, GET `{id}` | `/api/v1/owner-statements?period=2026-10` | STATEMENT_VIEW |
| POST | `/api/v1/owner-statements/generate?period=2026-10` | STATEMENT_GENERATE |
| POST | `/api/v1/owner-statements/{id}/approve`, `/send` | STATEMENT_APPROVE |
| POST | `/api/v1/owner-statements/{id}/remittances` | REMITTANCE_RECORD |
| GET | `/api/v1/reports/dashboard`, `/arrears`, `/occupancy`, `/rent-roll`, `/collections?from=&to=` | REPORT_VIEW |

Errors always look like:

```json
{ "timestamp": "...", "status": 409, "code": "UNIT_NO_TAKEN",
  "message": "These unit numbers already exist in Kilimani Heights: B1", "path": "/api/v1/..." }
```

The frontend should switch on `code`. Plan refusals use HTTP 402 (`FEATURE_NOT_IN_PLAN`, `UNIT_LIMIT_REACHED`,
`USER_LIMIT_REACHED`, `SUBSCRIPTION_READ_ONLY`) → show an upgrade prompt.

### Try the onboarding flow

```bash
# 1. Sign up -> copy accessToken into TOKEN
curl -s localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' -d '{
  "organizationName":"Acacia Properties","firstName":"Mary","lastName":"Njeri",
  "email":"mary@acacia.co.ke","phone":"+254712345678","password":"Str0ng-pass","county":"Nairobi"}'

# 2. Wizard screen 1
curl -s -X PUT localhost:8080/api/v1/onboarding/profile -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"businessType":"FIRM","unitsRange":"31-150","portfolioFocus":"MIXED"}'

# 3. Property, then units from the template
curl -s localhost:8080/api/v1/properties -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"KLM","name":"Kilimani Heights","propertyType":"RESIDENTIAL","county":"Nairobi","estate":"Kilimani"}'
curl -s -o units.csv localhost:8080/api/v1/onboarding/templates/units.csv -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/v1/properties/<id>/units/import -H "Authorization: Bearer $TOKEN" -F file=@units.csv

# 4. Sitting tenants with balances (check first, then import)
curl -s -o tenants.csv localhost:8080/api/v1/onboarding/templates/tenants.csv -H "Authorization: Bearer $TOKEN"
curl -s "localhost:8080/api/v1/onboarding/import/tenants?propertyId=<id>&dryRun=true" -H "Authorization: Bearer $TOKEN" -F file=@tenants.csv
curl -s "localhost:8080/api/v1/onboarding/import/tenants?propertyId=<id>" -H "Authorization: Bearer $TOKEN" -F file=@tenants.csv

# 5. Invite an accountant (the link is in the response and in the logged email)
curl -s localhost:8080/api/v1/users/invite -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"Brian","email":"brian@acacia.co.ke","roleCode":"ACCOUNTANT"}'

# 6. Checklist and dashboard
curl -s localhost:8080/api/v1/onboarding -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/v1/reports/dashboard -H "Authorization: Bearer $TOKEN"
```

### M-Pesa (Daraja) set-up

1. Create a payment channel (`POST /api/v1/payment-channels`) with the shortcode, consumer key/secret and
   passkey from the Daraja portal; secrets are encrypted at rest.
2. `POST /api/v1/payment-channels/{id}/register-urls` registers
   `PUBLIC_API_URL/api/v1/webhooks/mpesa/<token>/confirmation` with Safaricom.
3. Tenants pay to the Paybill with the **unit's payment code** (e.g. `KLM-B1`) or their account code
   (`T00012`) as the account number; the payment is matched and allocated to the oldest invoice, and an SMS
   receipt is queued. Anything that cannot be matched appears on the Payments screen as UNMATCHED with suggestions.

### Bank Paybill set-up (KCB, Equity, Co-op, DTB, Family, Kingdom, Saccos...)

1. `GET /api/v1/banks` lists the banks, their Paybill and how payments arrive: `automaticNotifications: true`
   means the bank sends PropertyPro a notification (KCB today); every other bank works by statement upload.
2. Create a channel with `channelType: BANK_PAYBILL`, the `bankCode` (`KCB`, `EQUITY`, ..., or `OTHER` with
   `bankName` and `shortcode` for a Sacco) and the landlord's `bankAccountNo`. The bank's Paybill is filled in.
3. Tenants pay the bank's Paybill with the account in the bank's format, by default
   `<bank account>#<unit payment code>` (e.g. 522522 / `1234567890#KLM-B4`). Invoices and reminders say so.
   **Confirm the format with each bank**; it is `banks.account_format` and changes by migration.
4. Payments arrive by:
   - **Notification** (banks with a connector): give the bank the channel's `bankNotificationUrl`
     (`PUBLIC_API_URL/api/v1/webhooks/bank/<token>/notification`) and set `KCB_IPN_PUBLIC_KEY`.
   - **Statement upload** (any bank): `POST /api/v1/payment-channels/{id}/statement` with the statement CSV
     (`dryRun=true` to preview). The unit code is found in the narration; uploading overlapping statements
     never records a payment twice.

Adding a bank connector: implement `integration/BankNotificationAdapter` (verify, parse, acknowledge) as a
`@Component` and set the bank's `integration` to `API` in a migration. Storing, de-duplicating, matching and
receipting are shared (`BankPaymentServiceImpl`).

## Project layout

```
com.propertypro
├── controller/     REST controllers, one per resource (AuthController, PropertyController, InvoiceController...)
├── dto/            request/response records, grouped per area (AuthDtos, PortfolioDtos, BillingDtos...) + PageResponse
├── entity/         JPA entities + TenantEntity/TenantRow base classes
├── repository/     Spring Data repositories; spec/ holds reusable list filters (PortfolioSpecs)
├── service/        service interfaces + their *Impl classes (AuthService / AuthServiceImpl, PlanGate / PlanGateImpl...)
├── scheduler/      scheduled jobs: billing, trial reminders, notification dispatcher
├── integration/    external systems: Daraja (M-Pesa) client, SMS/email gateways
├── security/       JWT filter and service, AuthenticatedUser, CryptoService
├── common/         ApiException, ApiError, GlobalExceptionHandler, Money, CsvReader, DocumentNumbers
├── tenancy/        TenantContext, TenantAwareDataSource, TenantIdentifierResolver, SystemExecutor
└── config/         SecurityConfig, AppProperties, SchedulingConfig
```

## Notes

- `spring.jpa.hibernate.ddl-auto` is `none`: Flyway owns the schema. Add `V11__...sql` for the next change.
- Access tokens last 15 minutes; role changes revoke the person's sessions so new permissions apply at once.
- SMS and email are logged, not sent, until you add beans implementing `SmsGateway` (e.g. Africa's Talking)
  and `EmailGateway`; the logging defaults step aside automatically.
- Next: PDF invoices/receipts/statements, tenant & owner portals (phone OTP), subscription billing for PropertyPro itself.
