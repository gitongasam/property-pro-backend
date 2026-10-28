package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.MeterDtos.BulkLine;
import com.propertypro.dto.MeterDtos.BulkReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingResponse;
import com.propertypro.dto.MeterDtos.ReadingUpdate;
import com.propertypro.dto.MeterDtos.SheetRow;
import com.propertypro.dto.MeterDtos.Utility;
import com.propertypro.dto.PageResponse;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeterReadingServiceImpl implements MeterReadingService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final BigDecimal UNUSUAL_FACTOR = BigDecimal.valueOf(2);

    /** Same mapping as BillingServiceImpl.addMeteredLines: any other metered charge counts as water. */
    private static final String CHARGE_UTILITY =
            "(CASE ct.code WHEN 'ELECTRICITY' THEN 'ELECTRICITY' WHEN 'GAS' THEN 'GAS' ELSE 'WATER' END)";

    /** A live lease on the unit bills this utility by meter, for readings after the tenant moved in. */
    private static final String BILLABLE = """
            EXISTS (SELECT 1 FROM leases l
                      JOIN lease_charges lc ON lc.lease_id = l.id AND lc.active AND lc.is_metered
                      JOIN charge_types ct ON ct.id = lc.charge_type_id
                     WHERE l.unit_id = m.unit_id AND l.status IN ('ACTIVE','NOTICE_GIVEN')
                       AND m.reading_date > COALESCE(l.move_in_date, l.start_date)
                       AND %s = m.utility)""".formatted(CHARGE_UTILITY);

    private static final String SELECT = """
            SELECT m.id, m.unit_id, u.unit_no, u.property_id, p.name AS property_name, m.utility, m.reading_date,
                   m.previous_reading, m.current_reading, m.consumption, m.billed, m.created_at,
                   (SELECT avg(h.consumption) FROM (
                        SELECT consumption FROM meter_readings h
                         WHERE h.unit_id = m.unit_id AND h.utility = m.utility
                           AND h.reading_date < m.reading_date AND h.consumption > 0
                         ORDER BY h.reading_date DESC LIMIT 3) h) AS avg_consumption,
                   (SELECT i.invoice_no FROM invoice_lines il JOIN invoices i ON i.id = il.invoice_id
                     WHERE il.meter_reading_id = m.id AND i.status <> 'VOID' LIMIT 1) AS invoice_no,
                   NULLIF(trim(concat_ws(' ', ru.first_name, ru.last_name)), '') AS read_by,
                   %s AS billable
              FROM meter_readings m
              JOIN units u ON u.id = m.unit_id
              JOIN properties p ON p.id = u.property_id
              LEFT JOIN users ru ON ru.id = m.read_by
            """.formatted(BILLABLE);

    private final JdbcClient jdbc;
    private final PlanGate planGate;

    private record UnitRef(UUID id, String unitNo, UUID propertyId) {}

    private record Last(UUID id, LocalDate date, BigDecimal current, boolean billed) {}

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReadingResponse> list(UUID propertyId, UUID unitId, Utility utility, Boolean billed,
                                              LocalDate from, LocalDate to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE u.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (propertyId != null) { where.append(" AND u.property_id = ?"); params.add(propertyId); }
        if (unitId != null)     { where.append(" AND m.unit_id = ?");     params.add(unitId); }
        if (utility != null)    { where.append(" AND m.utility = ?");     params.add(utility.name()); }
        if (billed != null)     { where.append(" AND m.billed = ?");      params.add(billed); }
        if (from != null)       { where.append(" AND m.reading_date >= ?"); params.add(from); }
        if (to != null)         { where.append(" AND m.reading_date <= ?"); params.add(to); }

        long total = jdbc.sql("""
                        SELECT count(*) FROM meter_readings m
                          JOIN units u ON u.id = m.unit_id""" + where)
                .params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<ReadingResponse> rows = jdbc.sql(SELECT + where + " ORDER BY m.reading_date DESC, u.unit_no, m.utility LIMIT ? OFFSET ?")
                .params(pageParams).query(this::map).list();
        int totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(rows, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public ReadingResponse get(UUID id) {
        return jdbc.sql(SELECT + " WHERE m.id = ?").param(id).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Meter reading"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SheetRow> sheet(UUID propertyId, Utility utility) {
        boolean exists = jdbc.sql("SELECT count(*) > 0 FROM properties WHERE id = ? AND deleted_at IS NULL")
                .param(propertyId).query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("Property");
        return jdbc.sql("""
                        SELECT u.id, u.unit_no, t.name AS tenant_name, rate.rate_per_unit,
                               last.reading_date, last.current_reading, last.consumption
                          FROM units u
                          LEFT JOIN leases l ON l.unit_id = u.id AND l.status IN ('ACTIVE','NOTICE_GIVEN')
                          LEFT JOIN tenants t ON t.id = l.tenant_id
                          LEFT JOIN LATERAL (
                               SELECT lc.rate_per_unit FROM lease_charges lc
                                 JOIN charge_types ct ON ct.id = lc.charge_type_id
                                WHERE lc.lease_id = l.id AND lc.active AND lc.is_metered AND %s = ?
                                LIMIT 1) rate ON true
                          LEFT JOIN LATERAL (
                               SELECT reading_date, current_reading, consumption FROM meter_readings m
                                WHERE m.unit_id = u.id AND m.utility = ?
                                ORDER BY reading_date DESC LIMIT 1) last ON true
                         WHERE u.property_id = ? AND u.deleted_at IS NULL
                         ORDER BY u.unit_no""".formatted(CHARGE_UTILITY))
                .param(utility.name()).param(utility.name()).param(propertyId)
                .query((rs, n) -> new SheetRow(rs.getObject("id", UUID.class), rs.getString("unit_no"),
                        rs.getString("tenant_name"), rs.getBigDecimal("rate_per_unit") != null,
                        rs.getBigDecimal("rate_per_unit"), rs.getObject("reading_date", LocalDate.class),
                        rs.getBigDecimal("current_reading"), rs.getBigDecimal("consumption")))
                .list();
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public ReadingResponse record(ReadingRequest req) {
        planGate.requireWritable();
        UUID id = insert(lockUnit(req.unitId()), req.utility(), dateOrToday(req.readingDate()),
                req.currentReading(), req.previousReading());
        return get(id);
    }

    @Override
    @Transactional
    public List<ReadingResponse> recordBulk(BulkReadingRequest req) {
        planGate.requireWritable();
        LocalDate date = dateOrToday(req.readingDate());
        Set<UUID> seen = new HashSet<>();
        List<UUID> ids = new ArrayList<>();
        for (BulkLine line : req.readings()) {
            if (!seen.add(line.unitId())) {
                throw ApiException.badRequest("DUPLICATE_UNIT", "A unit appears twice in this round");
            }
            UnitRef unit = lockUnit(line.unitId());
            try {
                ids.add(insert(unit, req.utility(), date, line.currentReading(), line.previousReading()));
            } catch (ApiException e) {
                // All or nothing: the transaction rolls back; say which unit failed
                throw new ApiException(e.getStatus(), e.getCode(), "Unit " + unit.unitNo() + ": " + e.getMessage());
            }
        }
        log.info("Recorded {} {} readings for {}", ids.size(), req.utility(), date);
        return ids.stream().map(this::get).toList();
    }

    @Override
    @Transactional
    public ReadingResponse update(UUID id, ReadingUpdate req) {
        planGate.requireWritable();
        Last reading = editable(id);
        BigDecimal previous = req.previousReading() != null ? req.previousReading()
                : jdbc.sql("SELECT previous_reading FROM meter_readings WHERE id = ?").param(id)
                        .query(BigDecimal.class).single();
        if (req.currentReading().compareTo(previous) < 0) {
            throw ApiException.badRequest("READING_BELOW_PREVIOUS", "The reading " + plain(req.currentReading())
                    + " is lower than the previous reading " + plain(previous));
        }
        jdbc.sql("UPDATE meter_readings SET current_reading = ?, previous_reading = ? WHERE id = ?")
                .param(req.currentReading()).param(previous).param(reading.id()).update();
        return get(id);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        planGate.requireWritable();
        Last reading = editable(id);
        boolean onInvoice = jdbc.sql("SELECT count(*) > 0 FROM invoice_lines WHERE meter_reading_id = ?")
                .param(reading.id()).query(Boolean.class).single();
        if (onInvoice) {
            throw ApiException.conflict("READING_ON_INVOICE",
                    "This reading is on an invoice (voided or not); correct it instead of deleting it");
        }
        jdbc.sql("DELETE FROM meter_readings WHERE id = ?").param(reading.id()).update();
    }

    // ------------------------------------------------------------------ helpers

    /** Locks the unit so two readings of one meter cannot be chained onto the same previous reading. */
    private UnitRef lockUnit(UUID unitId) {
        return jdbc.sql("SELECT id, unit_no, property_id FROM units WHERE id = ? AND deleted_at IS NULL FOR UPDATE")
                .param(unitId)
                .query((rs, n) -> new UnitRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Unit"));
    }

    private UUID insert(UnitRef unit, Utility utility, LocalDate date, BigDecimal current, BigDecimal previousGiven) {
        if (date.isAfter(LocalDate.now(NAIROBI))) {
            throw ApiException.badRequest("READING_IN_FUTURE", "The reading date cannot be in the future");
        }
        Last last = last(unit.id(), utility);
        if (last != null && !date.isAfter(last.date())) {
            throw date.isEqual(last.date())
                    ? ApiException.conflict("READING_EXISTS", "This meter already has a reading on " + date.format(DAY))
                    : ApiException.badRequest("READING_OUT_OF_ORDER",
                            "This meter was last read on " + last.date().format(DAY) + "; a new reading must be later");
        }
        // First reading of a meter: it is the opening reading, nothing is consumed yet
        BigDecimal previous = previousGiven != null ? previousGiven : last != null ? last.current() : current;
        if (current.compareTo(previous) < 0) {
            throw ApiException.badRequest("READING_BELOW_PREVIOUS", "The reading " + plain(current)
                    + " is lower than the previous reading " + plain(previous)
                    + ". If the meter was replaced, give the new meter's starting figure as previousReading.");
        }
        return jdbc.sql("""
                        INSERT INTO meter_readings (organization_id, unit_id, utility, reading_date,
                                                    previous_reading, current_reading, read_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .param(TenantContext.requireOrganizationId()).param(unit.id()).param(utility.name()).param(date)
                .param(previous).param(current).param(TenantContext.userId().orElse(null))
                .query(UUID.class).single();
    }

    private Last last(UUID unitId, Utility utility) {
        return jdbc.sql("""
                        SELECT id, reading_date, current_reading, billed FROM meter_readings
                         WHERE unit_id = ? AND utility = ? ORDER BY reading_date DESC LIMIT 1""")
                .param(unitId).param(utility.name())
                .query((rs, n) -> new Last(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getBigDecimal(3), rs.getBoolean(4)))
                .optional().orElse(null);
    }

    /** The reading, if it is the meter's latest and not billed; later readings are chained onto it. */
    private Last editable(UUID id) {
        record Meter(UUID unitId, String utility) {}
        Meter meter = jdbc.sql("SELECT unit_id, utility FROM meter_readings WHERE id = ?").param(id)
                .query((rs, n) -> new Meter(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional().orElseThrow(() -> ApiException.notFound("Meter reading"));
        lockUnit(meter.unitId());
        Last latest = last(meter.unitId(), Utility.valueOf(meter.utility()));
        if (latest == null || !latest.id().equals(id)) {
            throw ApiException.conflict("READING_NOT_LATEST",
                    "Only the latest reading of a meter can be changed; later readings are based on this one");
        }
        if (latest.billed()) {
            throw ApiException.conflict("READING_BILLED",
                    "This reading is already on an invoice; void the invoice first to change it");
        }
        return latest;
    }

    private LocalDate dateOrToday(LocalDate date) {
        return date == null ? LocalDate.now(NAIROBI) : date;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private ReadingResponse map(ResultSet rs, int n) throws SQLException {
        BigDecimal consumption = rs.getBigDecimal("consumption");
        BigDecimal avg = rs.getBigDecimal("avg_consumption");
        if (avg != null) avg = avg.setScale(2, java.math.RoundingMode.HALF_UP);
        boolean unusual = avg != null && avg.signum() > 0 && consumption.compareTo(avg.multiply(UNUSUAL_FACTOR)) > 0;
        return new ReadingResponse(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getString("unit_no"), rs.getObject("property_id", UUID.class), rs.getString("property_name"),
                rs.getString("utility"), rs.getObject("reading_date", LocalDate.class),
                rs.getBigDecimal("previous_reading"), rs.getBigDecimal("current_reading"), consumption, avg, unusual,
                rs.getBoolean("billable"), rs.getBoolean("billed"), rs.getString("invoice_no"), rs.getString("read_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
