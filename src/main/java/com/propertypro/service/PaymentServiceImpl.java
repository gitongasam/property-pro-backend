package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.common.Money;
import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PaymentDtos.ManualPaymentRequest;
import com.propertypro.dto.PaymentDtos.PaymentResponse;
import com.propertypro.dto.PaymentDtos.Suggestion;
import com.propertypro.entity.Lease;
import com.propertypro.entity.Payment;
import com.propertypro.entity.Tenant;
import com.propertypro.entity.Unit;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.repository.PaymentRepository;
import com.propertypro.repository.TenantRepository;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository payments;
    private final TenantRepository tenants;
    private final LeaseRepository leases;
    private final AllocationService allocations;
    private final DocumentNumbers numbers;
    private final NotificationService notifications;
    private final PlanGate planGate;
    private final JdbcClient jdbc;
    private final EntityManager em;

    @Override
    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(Payment.Status status, UUID tenantId, Payment.Method method,
                                              LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Payment> byStatus = (r, q, cb) -> cb.equal(r.get("status"), status);
        Specification<Payment> byTenant = (r, q, cb) -> cb.equal(r.get("tenant").get("id"), tenantId);
        Specification<Payment> byMethod = (r, q, cb) -> cb.equal(r.get("method"), method);
        Specification<Payment> fromDate = (r, q, cb) -> cb.greaterThanOrEqualTo(r.<OffsetDateTime>get("paidAt"),
                from == null ? null : from.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(3)));
        Specification<Payment> toDate = (r, q, cb) -> cb.lessThan(r.<OffsetDateTime>get("paidAt"),
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(3)));
        var spec = Specification.allOf(
                status == null ? null : byStatus,
                tenantId == null ? null : byTenant,
                method == null ? null : byMethod,
                from == null ? null : fromDate,
                to == null ? null : toDate);
        Page<Payment> page = payments.findAll(spec, pageable);
        Map<UUID, String> typed = accountsTyped(page.getContent().stream().map(Payment::getId).toList());
        return PageResponse.of(page, p -> toResponse(p, typed.get(p.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        Payment p = find(id);
        return toResponse(p, accountsTyped(List.of(id)).get(id));
    }

    @Override
    // ------------------------------------------------------------------ writes

    /** Cash, cheque, bank transfer or an M-Pesa payment staff enter by hand. */
    @Transactional
    public PaymentResponse recordManual(ManualPaymentRequest req) {
        planGate.requireWritable();
        Tenant tenant = tenants.findById(req.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        Payment p = new Payment();
        p.setReceiptNo(numbers.next(DocumentNumbers.Type.RECEIPT));
        p.setMethod(req.method());
        p.setSource(Payment.Source.MANUAL);
        p.setReference(blankToNull(req.reference()));
        p.setAmount(Money.of(req.amount()));
        p.setPaidAt(req.paidAt() == null ? OffsetDateTime.now() : req.paidAt());
        p.setPayerName(req.payerName() == null ? tenant.getName() : req.payerName());
        p.setNotes(req.notes());
        attachTenant(p, tenant);
        return settle(payments.saveAndFlush(p), true);
    }

    /** Creates the payment for an incoming M-Pesa transaction and matches it if it can. */
    @Override
    @Transactional
    public Payment recordMpesa(UUID channelId, Payment.Source source, String receipt, BigDecimal amount,
                               OffsetDateTime paidAt, String payerName, String payerPhone, String billRef,
                               UUID knownTenantId) {
        return recordIncoming(Payment.Method.MPESA, source, channelId, receipt, amount, paidAt, payerName, payerPhone,
                billRef, knownTenantId);
    }

    @Override
    @Transactional
    public Payment recordBank(UUID channelId, Payment.Source source, String bankReference, BigDecimal amount,
                              OffsetDateTime paidAt, String payerName, String payerPhone, String accountTyped) {
        // "1234567890#KLM-B4": the part before '#' is the landlord's bank account, the rest is the unit code
        String billRef = accountTyped == null ? null : accountTyped.substring(accountTyped.lastIndexOf('#') + 1);
        return recordIncoming(Payment.Method.BANK, source, channelId, bankReference, amount, paidAt,
                payerName, payerPhone, billRef, null);
    }

    private Payment recordIncoming(Payment.Method method, Payment.Source source, UUID channelId, String reference,
                                   BigDecimal amount, OffsetDateTime paidAt, String payerName, String payerPhone,
                                   String billRef, UUID knownTenantId) {
        Payment p = new Payment();
        p.setReceiptNo(numbers.next(DocumentNumbers.Type.RECEIPT));
        p.setMethod(method);
        p.setSource(source);
        p.setReference(reference);
        p.setAmount(Money.of(amount));
        p.setPaidAt(paidAt);
        p.setPayerName(payerName);
        p.setPayerPhone(payerPhone);
        p.setPaymentChannelId(channelId);

        Tenant tenant = knownTenantId != null ? tenants.findById(knownTenantId).orElse(null) : matchByAccount(billRef, p);
        if (tenant != null) {
            attachTenant(p, tenant);
        } else {
            p.setStatus(Payment.Status.UNMATCHED);
        }
        Payment saved = payments.saveAndFlush(p);
        if (tenant != null) {
            settle(saved, true);
        }
        return saved;
    }

    /** Staff tell us who an unmatched payment belongs to. */
    @Override
    @Transactional
    public PaymentResponse match(UUID paymentId, UUID tenantId) {
        planGate.requireWritable();
        Payment p = find(paymentId);
        if (p.getStatus() != Payment.Status.UNMATCHED) {
            throw ApiException.conflict("ALREADY_MATCHED", "This payment is already matched to a tenant");
        }
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        attachTenant(p, tenant);
        payments.flush();
        jdbc.sql("UPDATE mpesa_transactions SET processing_status = 'MATCHED', processed_at = now() WHERE payment_id = ?")
                .param(paymentId).update();
        jdbc.sql("UPDATE bank_transactions SET processing_status = 'MATCHED', processed_at = now() WHERE payment_id = ?")
                .param(paymentId).update();
        return settle(p, true);
    }

    /** Undo a payment (bounced cheque, wrong entry): its allocations are removed and invoices reopen. */
    @Override
    @Transactional
    public PaymentResponse reverse(UUID paymentId, String reason) {
        planGate.requireWritable();
        Payment p = find(paymentId);
        if (p.getStatus() == Payment.Status.REVERSED) {
            throw ApiException.conflict("ALREADY_REVERSED", "This payment is already reversed");
        }
        jdbc.sql("DELETE FROM payment_allocations WHERE payment_id = ?").param(paymentId).update();
        em.refresh(p);
        p.setStatus(Payment.Status.REVERSED);
        p.setReversedAt(OffsetDateTime.now());
        p.setReversalReason(reason);
        return toResponse(p, null);
    }

    /** Likely owners of an unmatched payment, best first. */
    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID paymentId) {
        Payment p = find(paymentId);
        String typed = accountsTyped(List.of(paymentId)).getOrDefault(paymentId, "");
        String needle = typed == null ? "" : typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String phoneDigits = p.getPayerPhone() == null ? "" : p.getPayerPhone().replaceAll("\\D", "");
        String last9 = phoneDigits.length() >= 9 && !p.getPayerPhone().contains("*") ? phoneDigits.substring(phoneDigits.length() - 9) : null;
        String firstName = p.getPayerName() == null ? "" : p.getPayerName().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);

        record Row(UUID tenantId, String name, String unit, String unitNo, String paymentCode, String phone,
                   String accountCode, BigDecimal owes, boolean paidFromPhoneBefore) {}
        List<Row> rows = jdbc.sql("""
                        SELECT t.id, t.name, p.name || ' · ' || u.unit_no AS unit, u.unit_no, u.payment_code, t.phone,
                               t.account_code, coalesce(b.net_balance, 0) AS owes,
                               EXISTS (SELECT 1 FROM payments x WHERE x.tenant_id = t.id AND x.payer_phone = :payer
                                        AND x.status <> 'REVERSED' AND :payer <> '') AS paid_before
                          FROM tenants t
                          JOIN leases l ON l.tenant_id = t.id AND l.status IN ('ACTIVE','NOTICE_GIVEN') AND l.deleted_at IS NULL
                          JOIN units u ON u.id = l.unit_id
                          JOIN properties p ON p.id = u.property_id
                          LEFT JOIN v_tenant_balances b ON b.tenant_id = t.id
                         WHERE t.deleted_at IS NULL""")
                .param("payer", p.getPayerPhone() == null ? "" : p.getPayerPhone())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                        rs.getString("unit_no"), rs.getString("payment_code"), rs.getString("phone"),
                        rs.getString("account_code"), rs.getBigDecimal("owes"), rs.getBoolean("paid_before")))
                .list();

        List<Suggestion> out = new ArrayList<>();
        for (Row r : rows) {
            int score = 0;
            List<String> why = new ArrayList<>();
            String unitNo = r.unitNo().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            String code = r.paymentCode().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (!needle.isEmpty() && (needle.equals(unitNo) || code.endsWith(needle) || needle.endsWith(unitNo))) {
                score += 40; why.add("account typed looks like " + r.paymentCode());
            }
            if (r.paidFromPhoneBefore()) { score += 35; why.add("paid from this number before"); }
            if (last9 != null && r.phone() != null && r.phone().endsWith(last9)) { score += 35; why.add("phone matches"); }
            if (r.owes().compareTo(p.getAmount()) == 0) { score += 25; why.add("owes exactly this amount"); }
            if (!firstName.isEmpty() && r.name().toLowerCase(Locale.ROOT).contains(firstName)) { score += 15; why.add("name matches"); }
            if (!needle.isEmpty() && r.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").contains(needle)) {
                score += 20; why.add("account typed matches name");
            }
            if (score > 0) {
                out.add(new Suggestion(r.tenantId(), r.name(), r.unit(), r.owes(), String.join(" · ", why), score));
            }
        }
        out.sort(Comparator.comparingInt(Suggestion::score).reversed());
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    @Override
    public Payment find(UUID id) {
        return payments.findWithDetails(id).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    // ------------------------------------------------------------------ helpers

    /** Account number typed on M-Pesa → unit payment code → tenant on today's lease; else tenant account code. */
    private Tenant matchByAccount(String billRef, Payment p) {
        if (billRef == null || billRef.isBlank()) return null;
        String code = billRef.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        List<UUID> unitIds = jdbc.sql("""
                        SELECT id FROM units WHERE deleted_at IS NULL
                           AND regexp_replace(upper(payment_code), '[^A-Z0-9]', '', 'g') = regexp_replace(?, '[^A-Z0-9]', '', 'g')""")
                .param(code).query(UUID.class).list();
        if (unitIds.size() == 1) {
            List<Lease> current = leases.findCurrentForUnit(unitIds.getFirst(), Lease.LIVE, LocalDate.now());
            if (!current.isEmpty()) {
                return current.getFirst().getTenant();
            }
        }
        return tenants.findByAccountCode(code).orElse(null);
    }

    private void attachTenant(Payment p, Tenant tenant) {
        p.setTenant(tenant);
        List<Lease> live = leases.findByTenantAndStatus(tenant.getId(), Lease.LIVE);
        if (!live.isEmpty()) {
            Lease lease = live.getFirst();
            Unit unit = lease.getUnit();
            p.setLease(lease);
            p.setUnit(unit);
            p.setProperty(unit.getProperty());
        }
        p.setStatus(Payment.Status.UNALLOCATED);
    }

    /** Allocates to open invoices, refreshes balances, sends the receipt SMS. */
    private PaymentResponse settle(Payment p, boolean sendReceipt) {
        allocations.allocatePayment(p.getId());
        em.refresh(p);
        if (sendReceipt && p.getTenant() != null) {
            Tenant t = p.getTenant();
            BigDecimal balance = jdbc.sql("SELECT coalesce(net_balance, 0) FROM v_tenant_balances WHERE tenant_id = ?")
                    .param(t.getId()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", p.getAmount().toPlainString());
            vars.put("tenant_name", t.getName());
            vars.put("unit", p.getUnit() == null ? "" : p.getUnit().getUnitNo());
            vars.put("reference", p.getReference() == null ? p.getReceiptNo() : p.getReference());
            vars.put("receipt_no", p.getReceiptNo());
            vars.put("balance", balance.max(BigDecimal.ZERO).toPlainString());
            notifications.queue(Channel.SMS, Recipient.TENANT, t.getId(), t.getPhone(), "PAYMENT_RECEIPT", vars);
        }
        return toResponse(p, null);
    }

    private Map<UUID, String> accountsTyped(List<UUID> paymentIds) {
        Map<UUID, String> out = new HashMap<>();
        if (paymentIds.isEmpty()) return out;
        jdbc.sql("""
                        SELECT payment_id, bill_ref_number FROM mpesa_transactions WHERE payment_id IN (:ids)
                        UNION ALL
                        SELECT payment_id, customer_reference FROM bank_transactions WHERE payment_id IN (:ids)""")
                .paramSource(new MapSqlParameterSource("ids", paymentIds))
                .query((rs, n) -> out.put(rs.getObject("payment_id", UUID.class), rs.getString("bill_ref_number")))
                .list();
        return out;
    }

    static PaymentResponse toResponse(Payment p, String accountTyped) {
        var t = p.getTenant();
        var u = p.getUnit();
        return new PaymentResponse(p.getId(), p.getReceiptNo(), p.getMethod().name(), p.getSource().name(), p.getReference(),
                p.getAmount(), p.getAmountAllocated(), p.getUnallocated(), p.getStatus().name(),
                t == null ? null : t.getId(), t == null ? null : t.getName(),
                u == null ? null : u.getId(), u == null ? null : u.getUnitNo(),
                p.getProperty() == null ? null : p.getProperty().getName(),
                p.getPayerName(), p.getPayerPhone(), accountTyped, p.getPaidAt(), p.getNotes());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
