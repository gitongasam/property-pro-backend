package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.CsvReader;
import com.propertypro.dto.ImportDtos.ImportResult;
import com.propertypro.dto.ImportDtos.RowError;
import com.propertypro.dto.LeasingDtos.LeaseRequest;
import com.propertypro.dto.LeasingDtos.TenantRequest;
import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.entity.Lease;
import com.propertypro.entity.Tenant;
import com.propertypro.entity.Unit;
import com.propertypro.repository.TenantRepository;
import com.propertypro.security.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Set<Unit.Type> COMMERCIAL = Set.of(Unit.Type.SHOP, Unit.Type.OFFICE, Unit.Type.WAREHOUSE, Unit.Type.STALL);
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"));

    private record UnitRef(UUID id, String unitNo, String status, UUID propertyId, String unitType, BigDecimal deposit) {}

    private record TenantRow(int row, UnitRef unit, String name, String phone, String email, String idNumber,
                             BigDecimal rent, BigDecimal deposit, LocalDate start, BigDecimal owed) {}

    private final UnitService unitService;
    private final TenantService tenantService;
    private final TenantRepository tenants;
    private final LeaseService leaseService;
    private final BillingService billing;
    private final PlanGate planGate;
    private final JdbcClient jdbc;

    @Override
    // ------------------------------------------------------------------ units

    @Transactional
    public ImportResult importUnits(UUID propertyId, MultipartFile file, boolean dryRun) {
        planGate.requireWritable();
        List<Map<String, String>> rows = read(file);
        List<RowError> errors = new ArrayList<>();
        List<UnitRequest> requests = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;   // row 1 is the header
            Map<String, String> r = rows.get(i);
            int before = errors.size();
            String unitNo = pick(r, "unitno", "unit", "unitnumber", "houseno", "house", "door");
            if (unitNo.isEmpty()) {
                errors.add(new RowError(rowNo, "unit no", "Unit number is missing"));
            } else if (!seen.add(unitNo.toUpperCase(Locale.ROOT))) {
                errors.add(new RowError(rowNo, "unit no", "Unit " + unitNo + " appears more than once"));
            }
            Unit.Type type = unitType(pick(r, "type", "unittype"), rowNo, errors);
            BigDecimal rentRate = money(pick(r, "rentpersqft", "ratepersqft"), rowNo, "rent per sq ft", errors, false);
            BigDecimal serviceRate = money(pick(r, "servicechargepersqft", "servicepersqft"), rowNo,
                    "service charge per sq ft", errors, false);
            BigDecimal rent = money(pick(r, "rent", "marketrent", "monthlyrent"), rowNo, "rent", errors, rentRate == null);
            BigDecimal deposit = money(pick(r, "deposit", "depositamount"), rowNo, "deposit", errors, false);
            BigDecimal service = money(pick(r, "servicecharge", "service"), rowNo, "service charge", errors, false);
            BigDecimal size = money(pick(r, "sizesqft", "size", "sqft", "area"), rowNo, "size", errors, false);
            if ((rentRate != null || serviceRate != null) && (size == null || size.signum() <= 0)) {
                errors.add(new RowError(rowNo, "size", "Give the size in sq ft to charge per sq ft"));
            }
            Short beds = smallInt(pick(r, "bedrooms", "beds"), rowNo, "bedrooms", errors);
            Short baths = smallInt(pick(r, "bathrooms", "baths"), rowNo, "bathrooms", errors);
            String code = pick(r, "paymentcode", "accountno", "account");
            if (!code.isEmpty() && !code.matches("^[A-Za-z0-9-]{2,30}$")) {
                errors.add(new RowError(rowNo, "payment code", "Use 2 to 30 letters, digits or dashes"));
            }
            if (errors.size() == before) {
                requests.add(new UnitRequest(unitNo, blank(pick(r, "block", "wing")), blank(pick(r, "floor", "level")),
                        type, beds, baths, size != null && size.signum() > 0 ? size : null, rent, deposit, service,
                        rentRate, serviceRate, blank(code), blank(pick(r, "watermeter", "watermeterno")),
                        blank(pick(r, "electricitymeter", "electricitymeterno", "tokenmeter")), blank(pick(r, "notes"))));
            }
        }
        if (rows.isEmpty()) {
            errors.add(new RowError(1, null, "The file has no rows under the header"));
        }
        if (!errors.isEmpty() || dryRun) {
            return new ImportResult(dryRun, rows.size(), 0, errors, BigDecimal.ZERO, List.of());
        }
        // createAll checks the plan's unit limit, unit numbers already used and payment code clashes
        var created = unitService.createAll(propertyId, requests);
        return new ImportResult(false, rows.size(), created.size(), List.of(), BigDecimal.ZERO,
                created.stream().map(u -> u.unitNo()).toList());
    }

    @Override
    // ------------------------------------------------------------------ sitting tenants

    @Transactional
    public ImportResult importTenants(UUID propertyId, MultipartFile file, boolean dryRun) {
        planGate.requireWritable();
        List<Map<String, String>> rows = read(file);
        LocalDate today = LocalDate.now(NAIROBI);
        List<RowError> errors = new ArrayList<>();
        List<TenantRow> valid = new ArrayList<>();
        Set<UUID> unitsSeen = new HashSet<>();
        Set<String> idsSeen = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            Map<String, String> r = rows.get(i);
            int before = errors.size();

            String unitKey = pick(r, "unit", "unitno", "unitnumber", "houseno", "house", "paymentcode", "accountno");
            UnitRef unit = null;
            if (unitKey.isEmpty()) {
                errors.add(new RowError(rowNo, "unit", "Unit is missing"));
            } else {
                unit = findUnit(propertyId, unitKey).orElse(null);
                if (unit == null) {
                    errors.add(new RowError(rowNo, "unit", "No unit " + unitKey + (propertyId == null ? "" : " in this property")
                            + ". Add the units first."));
                } else if (!unitsSeen.add(unit.id())) {
                    errors.add(new RowError(rowNo, "unit", "Unit " + unit.unitNo() + " appears more than once"));
                } else if ("OCCUPIED".equals(unit.status())) {
                    errors.add(new RowError(rowNo, "unit", "Unit " + unit.unitNo() + " already has a tenant"));
                } else if ("UNAVAILABLE".equals(unit.status())) {
                    errors.add(new RowError(rowNo, "unit", "Unit " + unit.unitNo() + " is marked unavailable"));
                }
            }

            String name = pick(r, "tenantname", "name", "tenant", "fullname");
            if (name.isEmpty()) errors.add(new RowError(rowNo, "tenant name", "Name is missing"));
            else if (name.length() > 150) errors.add(new RowError(rowNo, "tenant name", "Name is too long"));

            String phone = kenyanPhone(pick(r, "phone", "phonenumber", "mobile", "tel", "telephone"));
            if (phone == null) {
                errors.add(new RowError(rowNo, "phone", "Use a Kenyan mobile number like 0712345678"));
            }
            String email = pick(r, "email", "emailaddress");
            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                errors.add(new RowError(rowNo, "email", "This email address does not look right"));
            }
            String idNumber = pick(r, "idnumber", "idno", "nationalid", "id", "passport");
            if (!idNumber.isEmpty()) {
                String hash = CryptoService.sha256(idNumber);
                if (!idsSeen.add(idNumber.toUpperCase(Locale.ROOT).replace(" ", ""))) {
                    errors.add(new RowError(rowNo, "id number", "This ID number appears more than once"));
                } else if (hash != null && tenants.existsByIdNumberHash(hash)) {
                    errors.add(new RowError(rowNo, "id number", "A tenant with this ID number already exists"));
                }
            }

            BigDecimal rent = money(pick(r, "rent", "monthlyrent", "rentamount"), rowNo, "rent", errors, true);
            BigDecimal deposit = money(pick(r, "deposit", "depositheld", "depositpaid"), rowNo, "deposit", errors, false);
            BigDecimal owed = money(pick(r, "owedtoday", "owed", "balance", "arrears", "balancedue", "amountowed"),
                    rowNo, "owed today", errors, false);
            LocalDate start = date(pick(r, "leasestart", "startdate", "movein", "moveindate"), rowNo, errors);
            if (start != null && start.isAfter(today)) {
                errors.add(new RowError(rowNo, "lease start", "Sitting tenants must have started already; add future leases from the Leases screen"));
            }

            if (errors.size() == before) {
                valid.add(new TenantRow(rowNo, unit, name, phone, blank(email), blank(idNumber), rent,
                        deposit != null ? deposit : unit.deposit() == null ? BigDecimal.ZERO : unit.deposit(),
                        start == null ? today.withDayOfMonth(1) : start,
                        owed == null ? BigDecimal.ZERO : owed));
            }
        }
        if (rows.isEmpty()) {
            errors.add(new RowError(1, null, "The file has no rows under the header"));
        }
        BigDecimal totalOwed = valid.stream().map(TenantRow::owed).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!errors.isEmpty() || dryRun) {
            return new ImportResult(dryRun, rows.size(), 0, errors, totalOwed, List.of());
        }

        short billingDay = setting("default_billing_day", 1);
        short dueDays = setting("default_due_days", 5);
        List<String> refs = new ArrayList<>();
        for (TenantRow t : valid) {
            Tenant tenant = tenantService.createEntity(new TenantRequest(Tenant.Type.INDIVIDUAL, t.name(), null,
                    t.idNumber() == null ? null : Tenant.IdType.NATIONAL_ID, t.idNumber(), null, t.email(), t.phone(),
                    null, null, null, null, null, null, "Imported " + today));
            boolean commercial = t.unit().unitType() != null && COMMERCIAL.contains(Unit.Type.valueOf(t.unit().unitType()));
            Lease lease = leaseService.importActive(new LeaseRequest(t.unit().id(), tenant.getId(),
                    commercial ? Lease.Type.COMMERCIAL : Lease.Type.RESIDENTIAL, t.start(), null, t.rent(), null, null,
                    Lease.Frequency.MONTHLY, billingDay, dueDays, t.deposit(), null, null, null,
                    false, false, null, null, null, null, List.of(), "Imported sitting tenant"), today);
            if (t.owed().signum() > 0) {
                billing.createOpeningBalance(tenant, lease, t.owed(), today);
            }
            refs.add(t.unit().unitNo() + " · " + tenant.getName() + " (" + tenant.getAccountCode() + ")");
        }
        return new ImportResult(false, rows.size(), valid.size(), List.of(), totalOwed, refs);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<String, String>> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a CSV file to upload");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw ApiException.badRequest("CSV_ONLY", "Save the sheet as CSV (File → Save as → CSV) and upload that");
        }
        try {
            return CsvReader.read(file.getInputStream());
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Could not read the file");
        }
    }

    private Optional<UnitRef> findUnit(UUID propertyId, String key) {
        return jdbc.sql("""
                        SELECT id, unit_no, status, property_id, unit_type, deposit_amount FROM units
                         WHERE deleted_at IS NULL
                           AND (?::uuid IS NULL OR property_id = ?::uuid)
                           AND (upper(unit_no) = upper(?) OR upper(payment_code) = upper(?))
                         ORDER BY (upper(unit_no) = upper(?)) DESC
                         LIMIT 1""")
                .param(propertyId).param(propertyId).param(key).param(key).param(key)
                .query((rs, n) -> new UnitRef(rs.getObject("id", UUID.class), rs.getString("unit_no"),
                        rs.getString("status"), rs.getObject("property_id", UUID.class), rs.getString("unit_type"),
                        rs.getBigDecimal("deposit_amount")))
                .optional();
    }

    private short setting(String key, int fallback) {
        String v = jdbc.sql("SELECT settings->>? FROM organizations WHERE id = current_setting('app.current_org')::uuid")
                .param(key).query(String.class).optional().orElse(null);
        try {
            return v == null ? (short) fallback : (short) new BigDecimal(v).intValue();
        } catch (NumberFormatException e) {
            return (short) fallback;
        }
    }

    private static String pick(Map<String, String> row, String... names) {
        for (String n : names) {
            String v = row.get(n);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 0712345678, 712345678, 254712345678, +254 712 345 678 → +254712345678 */
    static String kenyanPhone(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[\\s()-]", "");
        if (d.startsWith("+")) d = d.substring(1);
        if (d.matches("^0[17]\\d{8}$")) d = "254" + d.substring(1);
        else if (d.matches("^[17]\\d{8}$")) d = "254" + d;
        return d.matches("^254[17]\\d{8}$") ? "+" + d : null;
    }

    private static BigDecimal money(String raw, int row, String column, List<RowError> errors, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new RowError(row, column, column.substring(0, 1).toUpperCase() + column.substring(1) + " is missing"));
            return null;
        }
        String cleaned = raw.toUpperCase(Locale.ROOT).replace("KES", "").replace("KSH", "").replace(",", "").replace(" ", "");
        try {
            BigDecimal v = new BigDecimal(cleaned);
            if (v.signum() < 0) {
                errors.add(new RowError(row, column, "Cannot be negative"));
                return null;
            }
            if (v.scale() > 2 || v.precision() - v.scale() > 12) {
                errors.add(new RowError(row, column, "Use an amount like 25000 or 25000.50"));
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(new RowError(row, column, "\"" + raw + "\" is not an amount"));
            return null;
        }
    }

    private static Short smallInt(String raw, int row, String column, List<RowError> errors) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0 || v > 20) throw new NumberFormatException();
            return (short) v;
        } catch (NumberFormatException e) {
            errors.add(new RowError(row, column, "Use a whole number from 0 to 20"));
            return null;
        }
    }

    private static LocalDate date(String raw, int row, List<RowError> errors) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(raw.trim(), f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        errors.add(new RowError(row, "lease start", "Use a date like 2025-03-01 or 01/03/2025"));
        return null;
    }

    private static Unit.Type unitType(String raw, int row, List<RowError> errors) {
        if (raw == null || raw.isBlank()) return Unit.Type.OTHER;
        String k = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        Unit.Type mapped = switch (k) {
            case "BEDSITTER", "BEDSIT", "SINGLE", "SINGLE_ROOM" -> Unit.Type.BEDSITTER;
            case "STUDIO" -> Unit.Type.STUDIO;
            case "ONE_BR", "1BR", "1_BR", "1_BEDROOM", "ONE_BEDROOM" -> Unit.Type.ONE_BR;
            case "TWO_BR", "2BR", "2_BR", "2_BEDROOM", "TWO_BEDROOM" -> Unit.Type.TWO_BR;
            case "THREE_BR", "3BR", "3_BR", "3_BEDROOM", "THREE_BEDROOM" -> Unit.Type.THREE_BR;
            case "FOUR_PLUS_BR", "4BR", "4_BR", "4_BEDROOM", "FOUR_BEDROOM", "4+BR" -> Unit.Type.FOUR_PLUS_BR;
            default -> {
                try {
                    yield Unit.Type.valueOf(k);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
        if (mapped == null) {
            errors.add(new RowError(row, "type", "Unknown type \"" + raw + "\". Use e.g. BEDSITTER, ONE_BR, TWO_BR, SHOP, OFFICE"));
        }
        return mapped;
    }
}
