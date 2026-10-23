package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.common.Money;
import com.propertypro.dto.OperationsDtos.GenerateResult;
import com.propertypro.dto.OperationsDtos.OwnerStatementResponse;
import com.propertypro.dto.OperationsDtos.RemittanceRequest;
import com.propertypro.dto.OperationsDtos.StatementLineResponse;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OwnerStatementServiceImpl implements OwnerStatementService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final DocumentNumbers numbers;
    private final NotificationService notifications;
    private final PlanGate planGate;

    private record OwnerRow(UUID id, String name, String feeType, BigDecimal feeValue, String email) {}

    private record PropertyCollection(UUID propertyId, String propertyName, String feeType, BigDecimal feeValue,
                                      BigDecimal collected) {}

    private record ExpenseRow(UUID id, UUID propertyId, String propertyName, String description, BigDecimal total) {}

    @Override
    @Transactional
    public GenerateResult generate(YearMonth period) {
        planGate.requireWritable();
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        BigDecimal feeVatRate = jdbc.sql("""
                        SELECT coalesce((settings ->> 'management_fee_vat_rate')::numeric, 16)
                          FROM organizations WHERE id = ?""")
                .param(TenantContext.requireOrganizationId()).query(BigDecimal.class).single();

        List<OwnerRow> owners = jdbc.sql("""
                        SELECT id, name, management_fee_type, management_fee_value, email
                          FROM owners WHERE deleted_at IS NULL ORDER BY name""")
                .query((rs, n) -> new OwnerRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("management_fee_type"), rs.getBigDecimal("management_fee_value"), rs.getString("email")))
                .list();

        int generated = 0, skipped = 0;
        for (OwnerRow owner : owners) {
            String existing = jdbc.sql("SELECT status FROM owner_statements WHERE owner_id = ? AND period_start = ?")
                    .param(owner.id()).param(start).query(String.class).optional().orElse(null);
            if (existing != null && !"DRAFT".equals(existing)) {
                skipped++;
                continue;
            }
            if (existing != null) {
                jdbc.sql("DELETE FROM owner_statements WHERE owner_id = ? AND period_start = ? AND status = 'DRAFT'")
                        .param(owner.id()).param(start).update();
            }
            buildStatement(owner, start, end, feeVatRate);
            generated++;
        }
        return new GenerateResult(period.toString(), generated, skipped);
    }

    private void buildStatement(OwnerRow owner, LocalDate start, LocalDate end, BigDecimal feeVatRate) {
        BigDecimal opening = jdbc.sql("""
                        SELECT coalesce(net_payable - amount_remitted, 0) FROM owner_statements
                         WHERE owner_id = ? AND period_start < ? AND status <> 'DRAFT'
                         ORDER BY period_start DESC LIMIT 1""")
                .param(owner.id()).param(start).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);

        // Owner's share of each allocation: allocation × (owner-income lines / invoice total)
        List<PropertyCollection> collections = jdbc.sql("""
                        SELECT pr.id, pr.name, pr.management_fee_type, pr.management_fee_value,
                               coalesce(sum(pa.amount * oi.owner_amount / NULLIF(i.total, 0)), 0) AS collected
                          FROM properties pr
                          LEFT JOIN invoices i ON i.property_id = pr.id AND i.status <> 'VOID'
                          LEFT JOIN LATERAL (
                                SELECT coalesce(sum(il.amount + il.vat_amount) FILTER (WHERE ct.owner_income), 0) AS owner_amount
                                  FROM invoice_lines il JOIN charge_types ct ON ct.id = il.charge_type_id
                                 WHERE il.invoice_id = i.id) oi ON true
                          LEFT JOIN payment_allocations pa ON pa.invoice_id = i.id
                          LEFT JOIN payments p ON p.id = pa.payment_id
                         WHERE pr.owner_id = ? AND pr.deleted_at IS NULL
                           AND (pa.id IS NULL OR (
                                (p.id IS NOT NULL AND p.status <> 'REVERSED' AND p.paid_at >= ?::date AND p.paid_at < (?::date + 1))
                             OR (pa.credit_note_id IS NOT NULL AND pa.allocated_at >= ?::date AND pa.allocated_at < (?::date + 1))))
                         GROUP BY pr.id, pr.name, pr.management_fee_type, pr.management_fee_value
                         ORDER BY pr.name""")
                .param(owner.id()).param(start).param(end).param(start).param(end)
                .query((rs, n) -> new PropertyCollection(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("management_fee_type"), rs.getBigDecimal("management_fee_value"),
                        Money.of(rs.getBigDecimal("collected"))))
                .list();

        List<ExpenseRow> expenseRows = jdbc.sql("""
                        SELECT e.id, pr.id AS property_id, pr.name, e.expense_no || ' · ' || e.description AS description,
                               e.amount + e.vat_amount AS total
                          FROM expenses e JOIN properties pr ON pr.id = e.property_id
                         WHERE pr.owner_id = ? AND e.charge_to = 'OWNER' AND e.status IN ('APPROVED','PAID')
                           AND e.owner_statement_id IS NULL AND e.expense_date <= ?
                         ORDER BY e.expense_date""")
                .param(owner.id()).param(end)
                .query((rs, n) -> new ExpenseRow(rs.getObject("id", UUID.class), rs.getObject("property_id", UUID.class),
                        rs.getString("name"), rs.getString("description"), rs.getBigDecimal("total")))
                .list();

        BigDecimal totalCollected = collections.stream().map(PropertyCollection::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = expenseRows.stream().map(ExpenseRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Fee: a property's own rate wins; otherwise the owner's. A fixed owner fee is charged once.
        BigDecimal fee = BigDecimal.ZERO;
        boolean ownerFixedCharged = false;
        for (PropertyCollection pc : collections) {
            String type = pc.feeType() != null ? pc.feeType() : owner.feeType();
            BigDecimal value = pc.feeType() != null ? pc.feeValue() : owner.feeValue();
            if ("PERCENT".equals(type)) {
                fee = fee.add(Money.percent(pc.collected(), value));
            } else if ("FIXED".equals(type)) {
                if (pc.feeType() != null) {
                    fee = fee.add(Money.of(value));
                } else if (!ownerFixedCharged) {
                    fee = fee.add(Money.of(value));
                    ownerFixedCharged = true;
                }
            }
        }
        fee = Money.of(fee);
        BigDecimal feeVat = Money.percent(fee, feeVatRate);

        String no = numbers.next(DocumentNumbers.Type.STATEMENT, start);
        UUID statementId = jdbc.sql("""
                        INSERT INTO owner_statements (organization_id, statement_no, owner_id, period_start, period_end,
                               opening_balance, collections, expenses, management_fee, management_fee_vat)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .param(TenantContext.requireOrganizationId()).param(no).param(owner.id()).param(start).param(end)
                .param(Money.of(opening)).param(Money.of(totalCollected)).param(Money.of(totalExpenses)).param(fee).param(feeVat)
                .query(UUID.class).single();

        for (PropertyCollection pc : collections) {
            line(statementId, "COLLECTION", pc.propertyId(), "Rent and charges collected · " + pc.propertyName(),
                    pc.collected(), null, null);
        }
        for (ExpenseRow e : expenseRows) {
            line(statementId, "EXPENSE", e.propertyId(), e.description(), e.total().negate(), "EXPENSE", e.id());
        }
        if (fee.signum() > 0) {
            line(statementId, "FEE", null, "Management fee", fee.negate(), null, null);
        }
        if (feeVat.signum() > 0) {
            line(statementId, "FEE", null, "VAT on management fee (" + feeVatRate.stripTrailingZeros().toPlainString() + "%)",
                    feeVat.negate(), null, null);
        }
    }

    private void line(UUID statementId, String type, UUID propertyId, String description, BigDecimal amount,
                      String sourceType, UUID sourceId) {
        jdbc.sql("""
                        INSERT INTO owner_statement_lines (organization_id, statement_id, line_type, property_id, description,
                               amount, source_type, source_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .param(TenantContext.requireOrganizationId()).param(statementId).param(type).param(propertyId)
                .param(description.length() > 255 ? description.substring(0, 255) : description)
                .param(amount).param(sourceType).param(sourceId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnerStatementResponse> list(YearMonth period) {
        return jdbc.sql(SELECT + " WHERE (?::date IS NULL OR s.period_start = ?::date) ORDER BY s.period_start DESC, o.name")
                .param(period == null ? null : period.atDay(1)).param(period == null ? null : period.atDay(1))
                .query((rs, n) -> map(rs, List.of()))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerStatementResponse get(UUID id) {
        List<StatementLineResponse> lines = jdbc.sql("""
                        SELECT l.line_type, pr.name, l.description, l.amount
                          FROM owner_statement_lines l LEFT JOIN properties pr ON pr.id = l.property_id
                         WHERE l.statement_id = ?
                         ORDER BY CASE l.line_type WHEN 'COLLECTION' THEN 1 WHEN 'EXPENSE' THEN 2 WHEN 'FEE' THEN 3 ELSE 4 END""")
                .param(id)
                .query((rs, n) -> new StatementLineResponse(rs.getString("line_type"), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("amount")))
                .list();
        return jdbc.sql(SELECT + " WHERE s.id = ?").param(id)
                .query((rs, n) -> map(rs, lines)).optional()
                .orElseThrow(() -> ApiException.notFound("Owner statement"));
    }

    @Override
    @Transactional
    public OwnerStatementResponse approve(UUID id) {
        planGate.requireWritable();
        int n = jdbc.sql("""
                        UPDATE owner_statements SET status = 'APPROVED', approved_by = ?, approved_at = now()
                         WHERE id = ? AND status = 'DRAFT'""")
                .param(TenantContext.userId().orElse(null)).param(id).update();
        if (n == 0) {
            throw ApiException.conflict("NOT_DRAFT", "Only a draft statement can be approved");
        }
        jdbc.sql("""
                        UPDATE expenses SET owner_statement_id = ?
                         WHERE id IN (SELECT source_id FROM owner_statement_lines
                                       WHERE statement_id = ? AND source_type = 'EXPENSE')""")
                .param(id).param(id).update();
        return get(id);
    }

    /** Emails the statement to the owner (approved statements only). */
    @Override
    @Transactional
    public OwnerStatementResponse send(UUID id) {
        planGate.requireWritable();
        OwnerStatementResponse s = get(id);
        if ("DRAFT".equals(s.status())) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the statement before sending it");
        }
        String email = jdbc.sql("SELECT email FROM owners WHERE id = ?").param(s.ownerId()).query(String.class).optional().orElse(null);
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("NO_OWNER_EMAIL", "Add an email address for " + s.ownerName() + " first");
        }
        notifications.queue(Channel.EMAIL, Recipient.OWNER, s.ownerId(), email, "OWNER_STATEMENT", Map.of(
                "owner_name", s.ownerName(), "period", s.periodStart().format(MONTH),
                "net_payable", s.netPayable().toPlainString(), "org_name", ""));
        jdbc.sql("UPDATE owner_statements SET sent_at = now(), status = CASE WHEN status = 'APPROVED' THEN 'SENT' ELSE status END WHERE id = ?")
                .param(id).update();
        return get(id);
    }

    @Override
    @Transactional
    public OwnerStatementResponse recordRemittance(UUID id, RemittanceRequest req) {
        planGate.requireWritable();
        OwnerStatementResponse s = get(id);
        if ("DRAFT".equals(s.status())) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the statement before recording a remittance");
        }
        String method = req.method().toUpperCase(Locale.ROOT);
        jdbc.sql("""
                        INSERT INTO owner_remittances (organization_id, owner_id, statement_id, amount, method, reference, paid_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .param(TenantContext.requireOrganizationId()).param(s.ownerId()).param(id).param(Money.of(req.amount()))
                .param(method).param(req.reference())
                .param(req.paidAt() == null ? OffsetDateTime.now() : req.paidAt())
                .param(TenantContext.userId().orElse(null))
                .update();
        jdbc.sql("""
                        UPDATE owner_statements
                           SET amount_remitted = amount_remitted + ?,
                               status = CASE WHEN amount_remitted + ? >= net_payable THEN 'PAID' ELSE status END
                         WHERE id = ?""")
                .param(Money.of(req.amount())).param(Money.of(req.amount())).param(id).update();
        return get(id);
    }

    private static final String SELECT = """
            SELECT s.id, s.statement_no, s.owner_id, o.name AS owner_name, s.period_start, s.period_end, s.opening_balance,
                   s.collections, s.expenses, s.management_fee, s.management_fee_vat, s.adjustments, s.net_payable,
                   s.amount_remitted, s.status, s.approved_at, s.sent_at
              FROM owner_statements s JOIN owners o ON o.id = s.owner_id""";

    private static OwnerStatementResponse map(java.sql.ResultSet rs, List<StatementLineResponse> lines) throws java.sql.SQLException {
        return new OwnerStatementResponse(rs.getObject("id", UUID.class), rs.getString("statement_no"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getBigDecimal("opening_balance"), rs.getBigDecimal("collections"), rs.getBigDecimal("expenses"),
                rs.getBigDecimal("management_fee"), rs.getBigDecimal("management_fee_vat"), rs.getBigDecimal("adjustments"),
                rs.getBigDecimal("net_payable"), rs.getBigDecimal("amount_remitted"), rs.getString("status"),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getObject("sent_at", OffsetDateTime.class), lines);
    }
}
