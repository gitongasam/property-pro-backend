package com.propertypro.common;

import com.propertypro.dto.BankDtos.BankCredit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a bank statement exported as CSV from any bank's internet banking.
 * Statements start with a few lines about the account, so the header row is
 * looked for in the first lines; columns are recognised by the names banks
 * use (Narration / Description / Particulars, Credit / Money In / Deposits...).
 * Only credits (money in) are returned.
 */
public final class BankStatementParser {

    public static final int MAX_LINES = 5000;
    private static final int HEADER_SEARCH_LINES = 30;
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private static final Set<String> DATE = Set.of("transactiondate", "date", "valuedate", "postingdate", "trandate",
            "txndate", "bookingdate", "transdate", "postdate", "completiontime", "datetime");
    private static final Set<String> NARRATION = Set.of("narration", "description", "details", "transactiondetails",
            "particulars", "narrative", "remarks", "transactiondescription", "memo");
    private static final Set<String> REFERENCE = Set.of("reference", "ref", "referenceno", "referencenumber", "refno",
            "transactionreference", "chequeno", "chqrefno", "chequenumber", "transactionid", "receiptno", "bankreference");
    private static final Set<String> CREDIT = Set.of("credit", "credits", "creditamount", "moneyin", "deposit",
            "deposits", "paidin", "cr", "creditkes", "amountcredit", "lodgements");
    private static final Set<String> DEBIT = Set.of("debit", "debits", "debitamount", "moneyout", "withdrawal",
            "withdrawals", "paidout", "dr", "debitkes", "amountdebit");
    private static final Set<String> AMOUNT = Set.of("amount", "transactionamount", "amountkes");
    private static final Set<String> BALANCE = Set.of("balance", "runningbalance", "ledgerbalance", "closingbalance",
            "bookbalance", "availablebalance");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            "d/M/uuuu", "d-M-uuuu", "uuuu-M-d", "d.M.uuuu", "d-MMM-uuuu", "d MMM uuuu", "d/MMM/uuuu",
            "d-MMM-uu", "d MMM uu", "d/M/uu", "uuuu/M/d").stream()
            .map(p -> new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH))
            .toList();

    private BankStatementParser() {}

    /** A credit and the line of the file it came from (1-based, as a spreadsheet shows it). */
    public record StatementRow(int line, BankCredit credit) {}

    public record ParsedStatement(int linesRead, List<StatementRow> credits, List<String> problems) {}

    public static ParsedStatement parse(List<List<String>> rows) {
        int headerAt = -1;
        Map<String, Integer> cols = Map.of();
        for (int i = 0; i < Math.min(rows.size(), HEADER_SEARCH_LINES); i++) {
            Map<String, Integer> found = columns(rows.get(i));
            if (found.containsKey("date") && (found.containsKey("credit") || found.containsKey("amount"))) {
                headerAt = i;
                cols = found;
                break;
            }
        }
        if (headerAt < 0) {
            throw ApiException.badRequest("STATEMENT_COLUMNS_NOT_FOUND",
                    "Could not find the statement columns. The file needs a date column and a Credit (money in) column");
        }

        List<StatementRow> credits = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int linesRead = 0;
        for (int i = headerAt + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            int lineNo = i + 1;
            String dateText = cell(r, cols.get("date"));
            if (r.stream().allMatch(String::isBlank)) continue;
            linesRead++;
            if (dateText.isEmpty()) continue;   // totals and sub-totals have no date

            BigDecimal amount = cols.containsKey("credit")
                    ? amount(cell(r, cols.get("credit")))
                    : amount(cell(r, cols.get("amount")));
            if (!cols.containsKey("credit") && cols.containsKey("debit") && amount(cell(r, cols.get("debit"))) != null
                    && amount(cell(r, cols.get("debit"))).signum() > 0) {
                continue;   // a single Amount column next to a Debit column: this line is money out
            }
            if (amount == null || amount.signum() <= 0) continue;   // money out, or totals / blank lines

            LocalDate date = date(dateText);
            if (date == null) {
                problems.add("Line " + lineNo + ": could not read the date '" + dateText + "'");
                continue;
            }
            String narration = cell(r, cols.get("narration"));
            BigDecimal balance = amount(cell(r, cols.get("balance")));
            String reference = cell(r, cols.get("reference"));
            if (reference.isEmpty()) {
                // No bank reference: a fingerprint of the line, so re-uploading the same statement is harmless
                reference = "STMT-" + fingerprint(date + "|" + amount.toPlainString() + "|" + narration + "|"
                        + (balance == null ? "" : balance.toPlainString()));
            }
            OffsetDateTime paidAt = date.atStartOfDay(NAIROBI).withHour(12).toOffsetDateTime();
            credits.add(new StatementRow(lineNo, new BankCredit(reference, null, "STATEMENT", paidAt, amount, "KES",
                    narration, null, null, narration, null, balance)));
        }
        return new ParsedStatement(linesRead, credits, problems);
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> found = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String h = CsvReader.normalise(header.get(c));
            if (DATE.contains(h)) found.putIfAbsent("date", c);
            else if (NARRATION.contains(h)) found.putIfAbsent("narration", c);
            else if (REFERENCE.contains(h)) found.putIfAbsent("reference", c);
            else if (CREDIT.contains(h)) found.putIfAbsent("credit", c);
            else if (DEBIT.contains(h)) found.putIfAbsent("debit", c);
            else if (AMOUNT.contains(h)) found.putIfAbsent("amount", c);
            else if (BALANCE.contains(h)) found.putIfAbsent("balance", c);
        }
        return found;
    }

    private static String cell(List<String> row, Integer col) {
        return col == null || col >= row.size() ? "" : row.get(col).trim();
    }

    /** "KES 12,500.00", "12500", "(1,000.00)" (negative); blank or text is null. */
    static BigDecimal amount(String value) {
        String v = value.replaceAll("(?i)kes|ksh|cr\\b", "").replaceAll("[,\\s]", "");
        boolean negative = v.startsWith("(") && v.endsWith(")") || v.startsWith("-") || v.toUpperCase(Locale.ROOT).endsWith("DR");
        v = v.replaceAll("(?i)dr$", "").replaceAll("[()\\-]", "");
        if (v.isEmpty()) return null;
        try {
            BigDecimal n = new BigDecimal(v);
            return negative ? n.negate() : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Dates as banks print them; a time after the date is ignored. */
    static LocalDate date(String value) {
        String v = value.trim();
        if (v.isEmpty()) return null;
        try {
            return LocalDateTime.parse(v).toLocalDate();
        } catch (RuntimeException ignored) {
            // not ISO date-time
        }
        for (String candidate : List.of(v, v.split("[ T]")[0], v.replaceAll("\\s+\\d{1,2}:\\d{2}(:\\d{2})?(\\s*[AP]M)?$", ""))) {
            for (DateTimeFormatter f : DATE_FORMATS) {
                try {
                    return LocalDate.parse(candidate, f);
                } catch (RuntimeException ignored) {
                    // try the next format
                }
            }
        }
        return null;
    }

    private static String fingerprint(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
