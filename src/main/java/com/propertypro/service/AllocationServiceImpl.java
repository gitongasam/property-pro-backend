package com.propertypro.service;

import com.propertypro.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AllocationServiceImpl implements AllocationService {

    private final JdbcClient jdbc;
    private final EntityManager em;

    private record OpenInvoice(UUID id, BigDecimal balance) {}

    /** Allocates whatever is unallocated on this payment. Returns the amount allocated now. */
    @Override
    @Transactional
    public BigDecimal allocatePayment(UUID paymentId) {
        em.flush();
        var row = jdbc.sql("""
                        SELECT tenant_id, unallocated FROM payments
                         WHERE id = ? AND status IN ('UNALLOCATED','PARTIAL') FOR UPDATE""")
                .param(paymentId)
                .query((rs, n) -> new Object[]{rs.getObject("tenant_id", UUID.class), rs.getBigDecimal("unallocated")})
                .optional();
        if (row.isEmpty() || row.get()[0] == null) {
            return BigDecimal.ZERO;
        }
        UUID tenantId = (UUID) row.get()[0];
        BigDecimal remaining = (BigDecimal) row.get()[1];
        return allocate(tenantId, null, remaining, paymentId, null);
    }

    /** Allocates a credit note, to one invoice if given, otherwise to the oldest open invoices. */
    @Override
    @Transactional
    public BigDecimal allocateCreditNote(UUID creditNoteId, UUID tenantId, UUID invoiceId, BigDecimal amount) {
        em.flush();
        return allocate(tenantId, invoiceId, amount, null, creditNoteId);
    }

    /** After new invoices are raised, uses any money the tenant already paid in advance. */
    @Override
    @Transactional
    public BigDecimal applyTenantCredit(UUID tenantId) {
        em.flush();
        List<UUID> payments = jdbc.sql("""
                        SELECT id FROM payments
                         WHERE tenant_id = ? AND status IN ('UNALLOCATED','PARTIAL')
                         ORDER BY paid_at""")
                .param(tenantId).query(UUID.class).list();
        BigDecimal total = BigDecimal.ZERO;
        for (UUID p : payments) {
            total = total.add(allocatePayment(p));
        }
        return total;
    }

    private BigDecimal allocate(UUID tenantId, UUID onlyInvoice, BigDecimal amount, UUID paymentId, UUID creditNoteId) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        List<OpenInvoice> open = jdbc.sql("""
                        SELECT id, balance FROM invoices
                         WHERE tenant_id = ? AND status IN ('ISSUED','PARTIALLY_PAID') AND balance > 0
                           AND (?::uuid IS NULL OR id = ?::uuid)
                         ORDER BY due_date, issue_date, invoice_no
                         FOR UPDATE""")
                .param(tenantId).param(onlyInvoice).param(onlyInvoice)
                .query((rs, n) -> new OpenInvoice(rs.getObject("id", UUID.class), rs.getBigDecimal("balance")))
                .list();

        BigDecimal remaining = amount;
        BigDecimal allocated = BigDecimal.ZERO;
        for (OpenInvoice inv : open) {
            if (remaining.signum() <= 0) break;
            BigDecimal portion = remaining.min(inv.balance());
            jdbc.sql("""
                            INSERT INTO payment_allocations (organization_id, payment_id, credit_note_id, invoice_id, amount, allocated_by)
                            VALUES (?, ?, ?, ?, ?, ?)""")
                    .param(TenantContext.requireOrganizationId())
                    .param(paymentId).param(creditNoteId).param(inv.id()).param(portion)
                    .param(TenantContext.userId().orElse(null))
                    .update();
            remaining = remaining.subtract(portion);
            allocated = allocated.add(portion);
        }
        return allocated;
    }
}
