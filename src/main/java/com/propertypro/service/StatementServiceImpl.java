package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.BillingDtos.StatementLine;
import com.propertypro.dto.BillingDtos.StatementResponse;
import com.propertypro.entity.Tenant;
import com.propertypro.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StatementServiceImpl implements StatementService {

    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    private static final String LEDGER = """
            SELECT issue_date AS d, 1 AS ord, 'INVOICE' AS type, invoice_no AS ref,
                   CASE source WHEN 'OPENING' THEN 'Balance brought forward'
                               WHEN 'PENALTY' THEN 'Late payment penalty'
                               WHEN 'MOVE_IN' THEN 'Move-in invoice'
                               ELSE 'Invoice' END AS description,
                   total AS charged, 0::numeric AS paid, created_at AS ts
              FROM invoices WHERE tenant_id = :t AND status NOT IN ('DRAFT','VOID')
            UNION ALL
            SELECT issue_date, 1, 'WHT', invoice_no, 'Withholding tax · certificate ' || coalesce(wht_certificate_no, ''),
                   0, withholding_tax_amount, created_at
              FROM invoices WHERE tenant_id = :t AND status NOT IN ('DRAFT','VOID') AND withholding_tax_amount > 0
            UNION ALL
            SELECT paid_at::date, 2, 'PAYMENT', coalesce(reference, receipt_no),
                   CASE method WHEN 'MPESA' THEN 'M-Pesa payment' ELSE initcap(method) || ' payment' END,
                   0, amount, paid_at
              FROM payments WHERE tenant_id = :t AND status <> 'REVERSED'
            UNION ALL
            SELECT issue_date, 3, 'CREDIT_NOTE', credit_note_no, 'Credit note · ' || reason, 0, amount, created_at
              FROM credit_notes WHERE tenant_id = :t AND status = 'ISSUED'
            """;

    private record Entry(LocalDate date, String type, String ref, String description, BigDecimal charged, BigDecimal paid) {}

    @Override
    @Transactional(readOnly = true)
    public StatementResponse statement(UUID tenantId, LocalDate from, LocalDate to) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusMonths(3).withDayOfMonth(1) : from;

        BigDecimal opening = jdbc.sql("SELECT coalesce(sum(charged - paid), 0) FROM (" + LEDGER + ") x WHERE d < :from")
                .param("t", tenantId).param("from", start)
                .query(BigDecimal.class).single();

        List<Entry> entries = jdbc.sql("SELECT * FROM (" + LEDGER + ") x WHERE d BETWEEN :from AND :to ORDER BY d, ord, ts")
                .param("t", tenantId).param("from", start).param("to", end)
                .query((rs, n) -> new Entry(rs.getObject("d", LocalDate.class), rs.getString("type"), rs.getString("ref"),
                        rs.getString("description"), rs.getBigDecimal("charged"), rs.getBigDecimal("paid")))
                .list();

        BigDecimal running = opening;
        List<StatementLine> lines = new ArrayList<>();
        for (Entry e : entries) {
            running = running.add(e.charged()).subtract(e.paid());
            lines.add(new StatementLine(e.date(), e.type(), e.ref(), e.description(), e.charged(), e.paid(), running));
        }
        return new StatementResponse(tenant.getId(), tenant.getName(), start, end, opening, running, lines);
    }
}
