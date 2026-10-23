package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.Area;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.common.Money;
import com.propertypro.dto.BillingDtos.CreditNoteRequest;
import com.propertypro.dto.BillingDtos.CreditNoteResponse;
import com.propertypro.dto.BillingDtos.InvoiceResponse;
import com.propertypro.dto.BillingDtos.OneOffInvoiceRequest;
import com.propertypro.dto.BillingDtos.OneOffLine;
import com.propertypro.dto.BillingDtos.PayTo;
import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.BillingDtos.WithholdingTaxRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.ChargeType;
import com.propertypro.entity.Invoice;
import com.propertypro.entity.InvoiceLine;
import com.propertypro.entity.Lease;
import com.propertypro.entity.LeaseCharge;
import com.propertypro.entity.Tenant;
import com.propertypro.repository.ChargeTypeRepository;
import com.propertypro.repository.InvoiceRepository;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.repository.TenantRepository;
import com.propertypro.security.CryptoService;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final InvoiceRepository invoices;
    private final ChargeTypeRepository chargeTypes;
    private final TenantRepository tenants;
    private final LeaseRepository leases;
    private final DocumentNumbers numbers;
    private final AllocationService allocations;
    private final NotificationService notifications;
    private final PlanGate planGate;
    private final CryptoService crypto;
    private final JdbcClient jdbc;
    private final EntityManager em;

    @Override
    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> list(Invoice.Status status, boolean overdueOnly, UUID tenantId,
                                              UUID propertyId, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Invoice> byStatus = (r, q, cb) -> cb.equal(r.get("status"), status);
        Specification<Invoice> overdue = (r, q, cb) -> cb.and(
                r.get("status").in(InvoiceRepository.OPEN), cb.lessThan(r.<LocalDate>get("dueDate"), LocalDate.now()));
        Specification<Invoice> byTenant = (r, q, cb) -> cb.equal(r.get("tenant").get("id"), tenantId);
        Specification<Invoice> byProperty = (r, q, cb) -> cb.equal(r.get("property").get("id"), propertyId);
        Specification<Invoice> fromDate = (r, q, cb) -> cb.greaterThanOrEqualTo(r.<LocalDate>get("issueDate"), from);
        Specification<Invoice> toDate = (r, q, cb) -> cb.lessThanOrEqualTo(r.<LocalDate>get("issueDate"), to);
        var spec = Specification.allOf(
                status == null ? null : byStatus,
                overdueOnly ? overdue : null,
                tenantId == null ? null : byTenant,
                propertyId == null ? null : byProperty,
                from == null ? null : fromDate,
                to == null ? null : toDate);
        return PageResponse.of(invoices.findAll(spec, pageable), i -> InvoiceResponse.from(i, false));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID id) {
        return InvoiceResponse.from(find(id), true);
    }

    @Override
    public Invoice find(UUID id) {
        return invoices.findWithLines(id).orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    @Override
    // ------------------------------------------------------------ move-in invoice

    /** Deposit plus the first (possibly part) period; called when a lease is activated. */
    @Transactional
    public Invoice createMoveInInvoice(Lease lease, LocalDate from, LocalDate to, BigDecimal firstPeriodFactor) {
        Invoice inv = newInvoice(lease.getTenant(), lease, Invoice.Source.MOVE_IN, LocalDate.now());
        inv.setPeriodStart(from);
        inv.setPeriodEnd(to);
        LocalDate due = from.isAfter(LocalDate.now()) ? from : LocalDate.now();
        inv.setDueDate(due);

        if (Money.isPositive(lease.getDepositAmount())) {
            addLine(inv, type(ChargeType.DEPOSIT), "Security deposit", BigDecimal.ONE, lease.getDepositAmount(), false);
        }
        String period = from.format(DAY) + " – " + to.format(DAY);
        BigDecimal rent = lease.getRentAmount().multiply(firstPeriodFactor).setScale(2, RoundingMode.HALF_UP);
        addLine(inv, type(ChargeType.RENT), "Rent " + period + perSqft(lease, lease.getRentPerSqft()), BigDecimal.ONE,
                rent, lease.isVatApplicable());
        for (LeaseCharge c : lease.getCharges()) {
            if (c.isActive() && !c.isMetered() && Money.isPositive(c.getAmount())) {
                BigDecimal amount = c.getAmount().multiply(firstPeriodFactor).setScale(2, RoundingMode.HALF_UP);
                addLine(inv, c.getChargeType(), c.getChargeType().getName() + " " + period + perSqft(lease, c.getRatePerSqft()),
                        BigDecimal.ONE, amount, lease.isVatApplicable());
            }
        }
        return issue(inv);
    }

    @Override
    // ------------------------------------------------------------ scheduled billing

    /**
     * Raises every invoice this lease is due up to {@code runDate} (catches up
     * missed periods). Safe to run twice: the database refuses a second
     * scheduled invoice for the same lease and period.
     */
    @Transactional
    public List<Invoice> billLease(UUID leaseId, LocalDate runDate) {
        Lease lease = leases.findWithDetails(leaseId).orElseThrow(() -> ApiException.notFound("Lease"));
        List<Invoice> created = new java.util.ArrayList<>();
        int guard = 0;
        while (lease.isLive() && lease.getNextBillingDate() != null && !lease.getNextBillingDate().isAfter(runDate)
                && guard++ < 24) {
            LocalDate start = lease.getNextBillingDate();
            if (lease.getEndDate() != null && start.isAfter(lease.getEndDate())) {
                lease.setNextBillingDate(null);
                break;
            }
            LocalDate end = start.plusMonths(lease.getBillingFrequency().months).minusDays(1);
            if (lease.getEndDate() != null && end.isAfter(lease.getEndDate())) {
                end = lease.getEndDate();
            }
            applyEscalationIfDue(lease, start);

            Invoice inv = newInvoice(lease.getTenant(), lease, Invoice.Source.SCHEDULED, start);
            inv.setPeriodStart(start);
            inv.setPeriodEnd(end);
            inv.setDueDate(start.plusDays(lease.getDueDays()));
            String label = lease.getBillingFrequency() == Lease.Frequency.MONTHLY
                    ? start.format(PERIOD) : start.format(DAY) + " – " + end.format(DAY);

            addLine(inv, type(ChargeType.RENT), "Rent " + label + perSqft(lease, lease.getRentPerSqft()), BigDecimal.ONE,
                    lease.getRentAmount(), lease.isVatApplicable());
            for (LeaseCharge c : lease.getCharges()) {
                if (!c.isActive() || (c.getEndDate() != null && c.getEndDate().isBefore(start))) continue;
                if (c.isMetered()) {
                    addMeteredLines(inv, lease, c, start);
                } else if (Money.isPositive(c.getAmount())) {
                    addLine(inv, c.getChargeType(), c.getChargeType().getName() + " " + label + perSqft(lease, c.getRatePerSqft()),
                            BigDecimal.ONE, c.getAmount(), lease.isVatApplicable());
                }
            }
            created.add(issue(inv));
            lease.setNextBillingDate(start.plusMonths(lease.getBillingFrequency().months));
        }
        return created;
    }

    private void applyEscalationIfDue(Lease lease, LocalDate periodStart) {
        LocalDate due = lease.getNextEscalationDate();
        if (lease.getEscalationType() == Lease.Adjust.NONE || due == null || due.isAfter(periodStart)) {
            return;
        }
        BigDecimal old = lease.getRentAmount();
        BigDecimal updated = lease.getEscalationType() == Lease.Adjust.PERCENT
                ? old.add(Money.percent(old, lease.getEscalationValue()))
                : old.add(lease.getEscalationValue());
        lease.setRentAmount(Money.of(updated));
        if (lease.getRentPerSqft() != null) {
            lease.setRentPerSqft(Area.rate(lease.getRentAmount(), lease.getAreaSqft(), lease.getBillingFrequency().months));
        }
        lease.setNextEscalationDate(due.plusMonths(lease.getEscalationIntervalMonths() == null ? 12 : lease.getEscalationIntervalMonths()));
        jdbc.sql("""
                        INSERT INTO lease_rent_history (organization_id, lease_id, effective_date, old_rent, new_rent, reason, created_by)
                        VALUES (?, ?, ?, ?, ?, 'ESCALATION', ?)""")
                .param(lease.getOrganizationId()).param(lease.getId()).param(due).param(old).param(lease.getRentAmount())
                .param(com.propertypro.tenancy.TenantContext.userId().orElse(null))
                .update();
        log.info("Lease {} rent escalated from {} to {}", lease.getLeaseNo(), old, lease.getRentAmount());
    }

    /** " (1,200 sq ft @ KES 85/sq ft)" on lines charged by area, empty otherwise. */
    private static String perSqft(Lease lease, BigDecimal rate) {
        return rate == null || lease.getAreaSqft() == null ? "" : " (" + Area.describe(lease.getAreaSqft(), rate) + ")";
    }

    /** Water / electricity: every unbilled reading for the unit up to the period start. */
    private void addMeteredLines(Invoice inv, Lease lease, LeaseCharge charge, LocalDate upTo) {
        String utility = switch (charge.getChargeType().getCode()) {
            case "ELECTRICITY" -> "ELECTRICITY";
            case "GAS" -> "GAS";
            default -> "WATER";
        };
        record Reading(UUID id, LocalDate date, BigDecimal consumption) {}
        List<Reading> readings = jdbc.sql("""
                        SELECT id, reading_date, consumption FROM meter_readings
                         WHERE unit_id = ? AND utility = ? AND NOT billed AND reading_date <= ?
                         ORDER BY reading_date FOR UPDATE""")
                .param(lease.getUnit().getId()).param(utility).param(upTo)
                .query((rs, n) -> new Reading(rs.getObject("id", UUID.class), rs.getObject("reading_date", LocalDate.class),
                        rs.getBigDecimal("consumption")))
                .list();
        for (Reading r : readings) {
            if (r.consumption().signum() <= 0) continue;
            InvoiceLine line = addLine(inv, charge.getChargeType(),
                    charge.getChargeType().getName() + " " + r.consumption().stripTrailingZeros().toPlainString()
                            + " units, read " + r.date().format(DAY),
                    r.consumption(), charge.getRatePerUnit(), lease.isVatApplicable());
            line.setMeterReadingId(r.id());
            jdbc.sql("UPDATE meter_readings SET billed = true WHERE id = ?").param(r.id()).update();
        }
    }

    @Override
    // ---------------------------------------------------------------- one-off

    @Transactional
    public InvoiceResponse createOneOff(OneOffInvoiceRequest req) {
        planGate.requireWritable();
        Tenant tenant = tenants.findById(req.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        Lease lease = null;
        if (req.leaseId() != null) {
            lease = leases.findWithDetails(req.leaseId()).orElseThrow(() -> ApiException.notFound("Lease"));
            if (!lease.getTenant().getId().equals(tenant.getId())) {
                throw ApiException.badRequest("LEASE_MISMATCH", "This lease belongs to another tenant");
            }
        }
        Invoice inv = newInvoice(tenant, lease, Invoice.Source.MANUAL, LocalDate.now());
        inv.setDueDate(req.dueDate() == null ? LocalDate.now().plusDays(7) : req.dueDate());
        inv.setNotes(req.notes());
        boolean vat = lease != null && lease.isVatApplicable();
        for (OneOffLine l : req.lines()) {
            BigDecimal qty = l.quantity() == null ? BigDecimal.ONE : l.quantity();
            addLine(inv, type(l.chargeTypeCode().toUpperCase(Locale.ROOT)), l.description(), qty, l.unitPrice(), vat);
        }
        return InvoiceResponse.from(issue(inv), true);
    }

    /** What a tenant already owed when the organisation started using PropertyPro. */
    @Override
    @Transactional
    public Invoice createOpeningBalance(Tenant tenant, Lease lease, BigDecimal amount, LocalDate asOf) {
        Invoice inv = newInvoice(tenant, lease, Invoice.Source.OPENING, asOf);
        inv.setDueDate(asOf);
        addLine(inv, type(ChargeType.OPENING_BALANCE), "Balance brought forward as at " + asOf.format(DAY),
                BigDecimal.ONE, amount, false);
        return issue(inv, false);
    }

    @Override
    // -------------------------------------------------------------- penalties

    /** One late-payment penalty per overdue invoice, per the lease's rule. Returns penalties raised. */
    @Transactional
    public int applyPenalties(LocalDate today) {
        int raised = 0;
        for (Invoice overdue : invoices.findPenaltyCandidates(today)) {
            Lease lease = overdue.getLease();
            if (lease.getPenaltyType() == Lease.Adjust.NONE
                    || !overdue.getDueDate().plusDays(lease.getPenaltyGraceDays()).isBefore(today)
                    || overdue.getBalance() == null || overdue.getBalance().signum() <= 0) {
                continue;
            }
            BigDecimal amount = lease.getPenaltyType() == Lease.Adjust.PERCENT
                    ? Money.percent(overdue.getBalance(), lease.getPenaltyValue())
                    : Money.of(lease.getPenaltyValue());
            if (amount.signum() <= 0) continue;

            Invoice penalty = newInvoice(overdue.getTenant(), lease, Invoice.Source.PENALTY, today);
            penalty.setDueDate(today.plusDays(lease.getDueDays()));
            addLine(penalty, type(ChargeType.PENALTY), "Late payment penalty on " + overdue.getInvoiceNo(),
                    BigDecimal.ONE, amount, false);
            issue(penalty);
            overdue.setPenaltyAppliedAt(OffsetDateTime.now());
            raised++;
        }
        return raised;
    }

    @Override
    // ------------------------------------------------------- void, WHT, credit notes

    @Transactional
    public InvoiceResponse voidInvoice(UUID id, String reason) {
        planGate.requireWritable();
        Invoice inv = find(id);
        if (inv.getStatus() == Invoice.Status.VOID) {
            throw ApiException.conflict("ALREADY_VOID", "This invoice is already void");
        }
        if (inv.getAmountPaid() != null && inv.getAmountPaid().signum() > 0) {
            throw ApiException.conflict("INVOICE_HAS_PAYMENTS",
                    "Payments are allocated to this invoice; issue a credit note instead");
        }
        inv.setStatus(Invoice.Status.VOID);
        inv.setVoidedAt(OffsetDateTime.now());
        inv.setVoidReason(reason);
        jdbc.sql("UPDATE meter_readings SET billed = false WHERE id IN (SELECT meter_reading_id FROM invoice_lines WHERE invoice_id = ? AND meter_reading_id IS NOT NULL)")
                .param(id).update();
        return InvoiceResponse.from(inv, true);
    }

    /** A commercial tenant paid net of withholding tax and sent the certificate. */
    @Override
    @Transactional
    public InvoiceResponse recordWithholdingTax(UUID id, WithholdingTaxRequest req) {
        planGate.requireWritable();
        Invoice inv = find(id);
        if (inv.getBalance() == null || req.amount().compareTo(inv.getBalance()) > 0) {
            throw ApiException.badRequest("WHT_TOO_HIGH", "Withholding tax cannot be more than the balance");
        }
        inv.setWithholdingTaxAmount(Money.of(req.amount()));
        inv.setWhtCertificateNo(req.certificateNo());
        invoices.flush();
        jdbc.sql("""
                        UPDATE invoices SET status = CASE WHEN balance = 0 THEN 'PAID'
                                                          WHEN amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE status END
                         WHERE id = ?""").param(id).update();
        em.refresh(inv);
        return InvoiceResponse.from(inv, true);
    }

    @Override
    @Transactional
    public CreditNoteResponse createCreditNote(CreditNoteRequest req) {
        planGate.requireWritable();
        Tenant tenant = tenants.findById(req.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        if (req.invoiceId() != null) {
            Invoice inv = find(req.invoiceId());
            if (!inv.getTenant().getId().equals(tenant.getId())) {
                throw ApiException.badRequest("INVOICE_MISMATCH", "This invoice belongs to another tenant");
            }
        }
        String no = numbers.next(DocumentNumbers.Type.CREDIT_NOTE);
        UUID id = jdbc.sql("""
                        INSERT INTO credit_notes (organization_id, credit_note_no, tenant_id, invoice_id, amount, reason, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .param(tenant.getOrganizationId()).param(no).param(tenant.getId()).param(req.invoiceId())
                .param(Money.of(req.amount())).param(req.reason())
                .param(com.propertypro.tenancy.TenantContext.userId().orElse(null))
                .query(UUID.class).single();
        BigDecimal allocated = allocations.allocateCreditNote(id, tenant.getId(), req.invoiceId(), Money.of(req.amount()));
        return new CreditNoteResponse(id, no, tenant.getId(), req.invoiceId(), Money.of(req.amount()), allocated,
                req.reason(), LocalDate.now());
    }

    // ------------------------------------------------------------------ helpers

    private Invoice newInvoice(Tenant tenant, Lease lease, Invoice.Source source, LocalDate issueDate) {
        Invoice inv = new Invoice();
        inv.setTenant(tenant);
        inv.setLease(lease);
        if (lease != null) {
            inv.setUnit(lease.getUnit());
            inv.setProperty(lease.getUnit().getProperty());
        }
        inv.setSource(source);
        inv.setIssueDate(issueDate);
        inv.setStatus(Invoice.Status.ISSUED);
        return inv;
    }

    private InvoiceLine addLine(Invoice inv, ChargeType type, String description, BigDecimal qty,
                                BigDecimal unitPrice, boolean vatApplies) {
        InvoiceLine line = new InvoiceLine();
        line.setChargeType(type);
        line.setDescription(description.length() > 255 ? description.substring(0, 255) : description);
        line.setQuantity(qty);
        line.setUnitPrice(Money.of(unitPrice));
        line.setAmount(Money.of(qty.multiply(unitPrice)));
        if (vatApplies && type.isTaxable() && type.getVatRate().signum() > 0) {
            line.setVatRate(type.getVatRate());
            line.setVatAmount(Money.percent(line.getAmount(), type.getVatRate()));
        }
        inv.addLine(line);
        return line;
    }

    private Invoice issue(Invoice inv) {
        return issue(inv, true);
    }

    /** Numbers and saves the invoice, uses any credit the tenant has, and queues the SMS. */
    private Invoice issue(Invoice inv, boolean notify) {
        inv.recalculate();
        inv.setInvoiceNo(numbers.next(DocumentNumbers.Type.INVOICE, inv.getIssueDate()));
        Invoice saved = invoices.saveAndFlush(inv);
        allocations.applyTenantCredit(saved.getTenant().getId());
        em.refresh(saved);
        if (notify) {
            notifyTenant(saved);
        }
        return saved;
    }

    @Override
    @Transactional
    public SmsQueued sendToTenant(UUID invoiceId) {
        Invoice inv = find(invoiceId);
        if (inv.getStatus() == Invoice.Status.VOID) {
            throw ApiException.conflict("INVOICE_VOID", "This invoice is void; there is nothing to send");
        }
        if (inv.getTenant().getPhone() == null) {
            throw ApiException.badRequest("TENANT_NO_PHONE", inv.getTenant().getName() + " has no phone number");
        }
        notifyTenant(inv);
        return new SmsQueued(inv.getTenant().getPhone(), "Invoice " + inv.getInvoiceNo() + " is on its way by SMS");
    }

    /** Queues the INVOICE_ISSUED SMS (and email when the tenant has one) and stamps the invoice as sent. */
    private void notifyTenant(Invoice inv) {
        Tenant t = inv.getTenant();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("tenant_name", t.getName());
        vars.put("invoice_no", inv.getInvoiceNo());
        vars.put("amount", inv.getTotal().toPlainString());
        vars.put("period", inv.getPeriodStart() == null ? "" : inv.getPeriodStart().format(PERIOD));
        vars.put("unit", inv.getUnit() == null ? "" : inv.getUnit().getUnitNo());
        vars.put("due_date", inv.getDueDate().format(DAY));
        PayTo payTo = payTo(inv);
        vars.put("payment_code", payTo.account());
        vars.put("paybill", payTo.paybill());
        notifications.queue(Channel.SMS, Recipient.TENANT, t.getId(), t.getPhone(), "INVOICE_ISSUED", vars);
        if (t.getEmail() != null) {
            notifications.queue(Channel.EMAIL, Recipient.TENANT, t.getId(), t.getEmail(), "INVOICE_ISSUED", vars);
        }
        inv.setSentAt(OffsetDateTime.now());
    }

    @Override
    public PayTo payTo(Invoice inv) {
        UUID propertyId = inv.getProperty() == null ? null : inv.getProperty().getId();
        String code = inv.getUnit() == null ? inv.getTenant().getAccountCode() : inv.getUnit().getPaymentCode();
        return payTo(propertyId, code);
    }

    @Override
    public PayTo payTo(Lease lease) {
        return payTo(lease.getUnit().getProperty().getId(), lease.getUnit().getPaymentCode());
    }

    private PayTo payTo(UUID propertyId, String code) {
        record Row(String shortcode, byte[] bankAccount, String accountFormat) {}
        return jdbc.sql("""
                        SELECT c.shortcode, c.bank_account_no_enc, b.account_format
                          FROM payment_channels c LEFT JOIN banks b ON b.code = c.bank_code
                         WHERE c.active AND c.channel_type IN ('MPESA_PAYBILL','MPESA_TILL','BANK_PAYBILL')
                           AND (c.property_id IS NULL OR c.property_id = ?::uuid)
                         ORDER BY c.property_id NULLS LAST, c.created_at LIMIT 1""")
                .param(propertyId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getBytes(2), rs.getString(3)))
                .optional()
                // On a bank Paybill the account is in the bank's format, e.g. {account}#{code} -> 1234567890#KLM-B4
                .map(r -> r.accountFormat() == null
                        ? new PayTo(r.shortcode(), code)
                        : new PayTo(r.shortcode(), r.accountFormat()
                                .replace("{account}", crypto.decrypt(r.bankAccount())).replace("{code}", code)))
                .orElse(new PayTo("", code));
    }

    private ChargeType type(String code) {
        return chargeTypes.findByCode(code)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_CHARGE", "Charge type " + code + " is not set up"));
    }
}
