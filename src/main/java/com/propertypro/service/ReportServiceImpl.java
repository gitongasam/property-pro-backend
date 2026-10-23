package com.propertypro.service;

import com.propertypro.dto.ReportDtos.ArrearsReport;
import com.propertypro.dto.ReportDtos.ArrearsRow;
import com.propertypro.dto.ReportDtos.CollectionRow;
import com.propertypro.dto.ReportDtos.Dashboard;
import com.propertypro.dto.ReportDtos.Kpis;
import com.propertypro.dto.ReportDtos.MonthPoint;
import com.propertypro.dto.ReportDtos.OccupancyRow;
import com.propertypro.dto.ReportDtos.RecentPayment;
import com.propertypro.dto.ReportDtos.RentRollRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final JdbcClient jdbc;

    @Override
    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        YearMonth month = YearMonth.now(NAIROBI);
        LocalDate start = month.atDay(1), next = month.plusMonths(1).atDay(1), today = LocalDate.now(NAIROBI);

        Kpis kpis = jdbc.sql("""
                        SELECT
                          (SELECT coalesce(sum(total), 0) FROM invoices
                            WHERE status NOT IN ('DRAFT','VOID') AND source <> 'OPENING'
                              AND issue_date >= :start AND issue_date < :next) AS billed,
                          (SELECT coalesce(sum(amount), 0) FROM payments
                            WHERE status <> 'REVERSED' AND paid_at >= :start AND paid_at < :next) AS collected,
                          (SELECT coalesce(sum(total_outstanding - coalesce(not_yet_due, 0)), 0) FROM v_arrears_ageing) AS arrears,
                          (SELECT coalesce(sum(days_over_90), 0) FROM v_arrears_ageing) AS over90,
                          (SELECT count(DISTINCT tenant_id) FROM v_arrears_ageing WHERE total_outstanding > coalesce(not_yet_due, 0)) AS in_arrears,
                          (SELECT coalesce(sum(total_units), 0) FROM v_property_occupancy) AS units,
                          (SELECT coalesce(sum(occupied_units), 0) FROM v_property_occupancy) AS occupied,
                          (SELECT coalesce(sum(vacant_units), 0) FROM v_property_occupancy) AS vacant,
                          (SELECT count(*) FROM payments WHERE status = 'UNMATCHED') AS unmatched,
                          (SELECT coalesce(sum(amount), 0) FROM payments WHERE status = 'UNMATCHED') AS unmatched_amount,
                          (SELECT count(*) FROM maintenance_requests WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS','ON_HOLD')) AS open_mnt,
                          (SELECT count(*) FROM maintenance_requests WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS','ON_HOLD') AND priority = 'URGENT') AS urgent_mnt,
                          (SELECT count(*) FROM leases WHERE status IN ('ACTIVE','NOTICE_GIVEN') AND deleted_at IS NULL
                              AND end_date BETWEEN :today AND :today + 60) AS ending
                        """)
                .param("start", start).param("next", next).param("today", today)
                .query((rs, n) -> {
                    BigDecimal billed = rs.getBigDecimal("billed");
                    BigDecimal collected = rs.getBigDecimal("collected");
                    long units = rs.getLong("units"), occupied = rs.getLong("occupied");
                    return new Kpis(billed, collected, pct(collected, billed),
                            rs.getBigDecimal("arrears"), rs.getBigDecimal("over90"), rs.getLong("in_arrears"),
                            units, occupied, rs.getLong("vacant"),
                            units == 0 ? BigDecimal.ZERO : pct(BigDecimal.valueOf(occupied), BigDecimal.valueOf(units)),
                            rs.getLong("unmatched"), rs.getBigDecimal("unmatched_amount"),
                            rs.getLong("open_mnt"), rs.getLong("urgent_mnt"), rs.getLong("ending"));
                })
                .single();

        List<MonthPoint> months = jdbc.sql("""
                        WITH m AS (SELECT generate_series(date_trunc('month', :start::date) - interval '11 months',
                                                          date_trunc('month', :start::date), interval '1 month')::date AS month)
                        SELECT to_char(m.month, 'Mon YYYY') AS label,
                               (SELECT coalesce(sum(total), 0) FROM invoices
                                 WHERE status NOT IN ('DRAFT','VOID') AND source <> 'OPENING'
                                   AND issue_date >= m.month AND issue_date < m.month + interval '1 month') AS billed,
                               (SELECT coalesce(sum(amount), 0) FROM payments
                                 WHERE status <> 'REVERSED' AND paid_at >= m.month AND paid_at < m.month + interval '1 month') AS collected
                          FROM m ORDER BY m.month""")
                .param("start", start)
                .query((rs, n) -> new MonthPoint(rs.getString("label"), rs.getBigDecimal("billed"), rs.getBigDecimal("collected")))
                .list();

        List<RecentPayment> recent = jdbc.sql("""
                        SELECT p.id, coalesce(t.name, p.payer_name, 'Unknown') AS tenant, u.unit_no, p.amount, p.status,
                               p.reference, p.paid_at
                          FROM payments p LEFT JOIN tenants t ON t.id = p.tenant_id LEFT JOIN units u ON u.id = p.unit_id
                         ORDER BY p.paid_at DESC LIMIT 8""")
                .query((rs, n) -> new RecentPayment(rs.getObject("id", UUID.class), rs.getString("tenant"),
                        rs.getString("unit_no"), rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getString("reference"), rs.getObject("paid_at", OffsetDateTime.class)))
                .list();

        return new Dashboard(kpis, months, recent);
    }

    @Override
    @Transactional(readOnly = true)
    public ArrearsReport arrears(UUID propertyId) {
        List<ArrearsRow> rows = jdbc.sql("""
                        SELECT a.tenant_id, a.tenant_name, a.property_id, p.name AS property_name, u.unit_no,
                               coalesce(a.not_yet_due,0) AS nyd, coalesce(a.days_1_30,0) AS d30, coalesce(a.days_31_60,0) AS d60,
                               coalesce(a.days_61_90,0) AS d90, coalesce(a.days_over_90,0) AS d90p, a.total_outstanding, a.oldest_due_date
                          FROM v_arrears_ageing a
                          LEFT JOIN properties p ON p.id = a.property_id
                          LEFT JOIN units u ON u.id = a.unit_id
                         WHERE (:property::uuid IS NULL OR a.property_id = :property::uuid)
                         ORDER BY a.total_outstanding DESC""")
                .param("property", propertyId)
                .query((rs, n) -> new ArrearsRow(rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"),
                        rs.getObject("property_id", UUID.class), rs.getString("property_name"), rs.getString("unit_no"),
                        rs.getBigDecimal("nyd"), rs.getBigDecimal("d30"), rs.getBigDecimal("d60"), rs.getBigDecimal("d90"),
                        rs.getBigDecimal("d90p"), rs.getBigDecimal("total_outstanding"), rs.getObject("oldest_due_date", LocalDate.class)))
                .list();
        BigDecimal nyd = sum(rows, ArrearsRow::notYetDue), d30 = sum(rows, ArrearsRow::days1to30),
                d60 = sum(rows, ArrearsRow::days31to60), d90 = sum(rows, ArrearsRow::days61to90),
                d90p = sum(rows, ArrearsRow::over90);
        return new ArrearsReport(nyd, d30, d60, d90, d90p, nyd.add(d30).add(d60).add(d90).add(d90p), rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OccupancyRow> occupancy() {
        return jdbc.sql("SELECT * FROM v_property_occupancy ORDER BY property_name")
                .query((rs, n) -> new OccupancyRow(rs.getObject("property_id", UUID.class), rs.getString("property_name"),
                        rs.getLong("total_units"), rs.getLong("occupied_units"), rs.getLong("vacant_units"),
                        rs.getBigDecimal("occupancy_pct"), rs.getBigDecimal("potential_monthly_rent"),
                        rs.getBigDecimal("actual_monthly_rent"), rs.getBigDecimal("vacancy_loss")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RentRollRow> rentRoll(UUID propertyId) {
        return jdbc.sql("""
                        SELECT * FROM v_rent_roll WHERE (:property::uuid IS NULL OR property_id = :property::uuid)
                         ORDER BY property_name, unit_no""")
                .param("property", propertyId)
                .query((rs, n) -> new RentRollRow(rs.getObject("lease_id", UUID.class), rs.getString("lease_no"),
                        rs.getString("property_name"), rs.getString("unit_no"), rs.getString("payment_code"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"), rs.getString("tenant_phone"),
                        rs.getString("lease_status"), rs.getBoolean("is_future"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getBigDecimal("rent_amount"), rs.getString("billing_frequency"),
                        rs.getObject("next_escalation_date", LocalDate.class), rs.getBigDecimal("balance")))
                .list();
    }

    /** Billed (by invoice date) against collected (allocated from payments made in the period), per property. */
    @Override
    @Transactional(readOnly = true)
    public List<CollectionRow> collections(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT pr.id, pr.name,
                               (SELECT coalesce(sum(i.total), 0) FROM invoices i
                                 WHERE i.property_id = pr.id AND i.status NOT IN ('DRAFT','VOID') AND i.source <> 'OPENING'
                                   AND i.issue_date BETWEEN :from AND :to) AS billed,
                               (SELECT coalesce(sum(p.amount), 0) FROM payments p
                                 WHERE p.property_id = pr.id AND p.status <> 'REVERSED'
                                   AND p.paid_at >= :from AND p.paid_at < :to + 1) AS collected
                          FROM properties pr WHERE pr.deleted_at IS NULL ORDER BY pr.name""")
                .param("from", from).param("to", to)
                .query((rs, n) -> new CollectionRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBigDecimal("billed"), rs.getBigDecimal("collected"),
                        pct(rs.getBigDecimal("collected"), rs.getBigDecimal("billed"))))
                .list();
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, java.math.RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal sum(List<T> rows, java.util.function.Function<T, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
