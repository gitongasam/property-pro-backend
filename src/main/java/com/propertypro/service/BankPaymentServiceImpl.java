package com.propertypro.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.common.ApiException;
import com.propertypro.common.BankStatementParser;
import com.propertypro.common.BankStatementParser.ParsedStatement;
import com.propertypro.common.BankStatementParser.StatementRow;
import com.propertypro.common.CsvReader;
import com.propertypro.dto.BankDtos.BankAck;
import com.propertypro.dto.BankDtos.BankCredit;
import com.propertypro.dto.BankDtos.BankResponse;
import com.propertypro.dto.BankDtos.StatementImportResult;
import com.propertypro.dto.BankDtos.StatementLineResult;
import com.propertypro.entity.Bank;
import com.propertypro.entity.Payment;
import com.propertypro.entity.PaymentChannel;
import com.propertypro.integration.BankNotificationAdapter;
import com.propertypro.repository.BankRepository;
import com.propertypro.security.CryptoService;
import com.propertypro.tenancy.SystemExecutor;
import com.propertypro.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BankPaymentServiceImpl implements BankPaymentService {

    private static final Set<PaymentChannel.Type> BANK_TYPES =
            Set.of(PaymentChannel.Type.BANK_PAYBILL, PaymentChannel.Type.BANK_ACCOUNT);

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final PaymentService payments;
    private final PaymentChannelService channels;
    private final BankRepository banks;
    private final CryptoService crypto;
    private final PlanGate planGate;
    private final ObjectMapper json;
    private final Map<String, BankNotificationAdapter> adapters;

    public BankPaymentServiceImpl(SystemExecutor system, JdbcClient jdbc, PaymentService payments,
                                  PaymentChannelService channels, BankRepository banks, CryptoService crypto,
                                  PlanGate planGate, ObjectMapper json, List<BankNotificationAdapter> adapters) {
        this.system = system;
        this.jdbc = jdbc;
        this.payments = payments;
        this.channels = channels;
        this.banks = banks;
        this.crypto = crypto;
        this.planGate = planGate;
        this.json = json;
        this.adapters = adapters.stream().collect(Collectors.toMap(BankNotificationAdapter::bankCode, Function.identity()));
    }

    /** The channel a credit is for, read before the organisation context is set. */
    private record ChannelInfo(UUID id, UUID organizationId, String bankCode, byte[] bankAccount) {}

    /** What happened to one credit. */
    private record Outcome(String status, String receiptNo, String tenantName, String problem) {}

    @Override
    public List<BankResponse> banks() {
        return banks.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(b -> new BankResponse(b.getCode(), b.getName(), b.getPaybill(), b.getAccountFormat(),
                        b.getIntegration().name(),
                        b.getIntegration() == Bank.Integration.API && adapters.containsKey(b.getCode())))
                .toList();
    }

    // ------------------------------------------------------------ notifications

    @Override
    public BankAck notification(String token, String rawBody, HttpHeaders headers) {
        Optional<ChannelInfo> ch = system.asSystem(() -> jdbc.sql("""
                        SELECT id, organization_id, bank_code, bank_account_no_enc FROM payment_channels
                         WHERE webhook_token = ? AND active AND channel_type IN ('BANK_PAYBILL','BANK_ACCOUNT')""")
                .param(token)
                .query((rs, n) -> new ChannelInfo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBytes(4)))
                .optional());
        BankNotificationAdapter adapter = ch.map(c -> adapters.get(c.bankCode())).orElse(null);
        if (adapter == null) {
            log.warn("Bank notification with an unknown token, or for a bank without a connector; ignored");
            return new BankAck(404, Map.of("message", "Unknown payment channel"));
        }
        if (!adapter.verify(rawBody, headers)) {
            log.warn("{} notification with a missing or invalid signature; refused", adapter.bankCode());
            return new BankAck(401, adapter.acknowledgement("", false, "Invalid signature"));
        }
        Map<String, Object> body;
        try {
            body = json.readValue(rawBody, new TypeReference<>() {});
        } catch (IOException e) {
            return new BankAck(400, adapter.acknowledgement("", false, "Body is not valid JSON"));
        }
        BankCredit credit = adapter.parse(body);
        if (credit.reference() == null || credit.reference().isBlank()) {
            log.warn("{} notification without a transaction reference; refused", adapter.bankCode());
            return new BankAck(400, adapter.acknowledgement("", false, "Transaction reference is missing"));
        }
        ChannelInfo c = ch.get();
        Outcome o = record(c, null, credit, "IPN", Payment.Source.BANK_IPN, credit.accountTyped(), body);
        log.info("{} {} KES {} account '{}' -> {}{}", adapter.bankCode(), credit.reference(), credit.amount(),
                credit.accountTyped(), o.status(), o.problem() == null ? "" : " (" + o.problem() + ")");
        return new BankAck(200, adapter.acknowledgement(credit.reference(), true, "Notification received"));
    }

    // ------------------------------------------------------------ statements

    @Override
    public StatementImportResult importStatement(UUID channelId, MultipartFile file, boolean dryRun) {
        planGate.requireWritable();
        PaymentChannel channel = channels.find(channelId);
        if (!BANK_TYPES.contains(channel.getChannelType())) {
            throw ApiException.badRequest("NOT_A_BANK_CHANNEL", "Upload statements to a bank Paybill or bank account channel");
        }
        ChannelInfo c = new ChannelInfo(channel.getId(), TenantContext.requireOrganizationId(),
                channel.getBankCode() == null ? Bank.OTHER : channel.getBankCode(), channel.getBankAccountNoEnc());
        UUID userId = TenantContext.userId().orElse(null);
        ParsedStatement parsed = BankStatementParser.parse(readRows(file));
        Map<String, String> codes = knownCodes();

        List<StatementLineResult> lines = new ArrayList<>();
        int recorded = 0, matched = 0, unmatched = 0, already = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (StatementRow row : parsed.credits()) {
            BankCredit credit = row.credit();
            total = total.add(credit.amount());
            String code = findCode(credit.narration(), codes);
            Outcome o;
            if (dryRun) {
                o = new Outcome(alreadyRecorded(c, credit.reference()) ? "ALREADY_RECORDED" : "NEW", null, null,
                        code == null ? "No unit code found in the narration; staff will match it" : null);
            } else {
                o = record(c, userId, credit, "STATEMENT", Payment.Source.BANK_IMPORT,
                        code == null ? credit.narration() : code, Map.of("line", row.line(), "narration", credit.narration()));
            }
            switch (o.status()) {
                case "MATCHED" -> { recorded++; matched++; }
                case "UNMATCHED" -> { recorded++; unmatched++; }
                case "ALREADY_RECORDED" -> already++;
                default -> { }
            }
            lines.add(new StatementLineResult(row.line(), credit.paidAt().toLocalDate(), credit.amount(), credit.narration(),
                    credit.reference(), o.status(), code, o.tenantName(), o.receiptNo(), o.problem()));
        }
        return new StatementImportResult(dryRun, parsed.linesRead(), parsed.credits().size(), recorded, matched,
                unmatched, already, total, lines, parsed.problems());
    }

    // ------------------------------------------------------------ shared

    /**
     * Stores the credit, then records and matches the payment. Each step is its own transaction, so a failure
     * while recording still leaves the stored credit (marked ERROR) for staff.
     */
    private Outcome record(ChannelInfo c, UUID userId, BankCredit credit, String txSource, Payment.Source source,
                           String accountForMatching, Object raw) {
        UUID txId = system.asOrganization(c.organizationId(), userId, () -> jdbc.sql("""
                        INSERT INTO bank_transactions (organization_id, payment_channel_id, bank_code, source,
                               transaction_reference, request_id, channel_code, trans_time, amount, currency,
                               customer_reference, payer_name, payer_phone, narration, balance_after, raw_payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (payment_channel_id, transaction_reference) DO NOTHING
                        RETURNING id""")
                .param(c.organizationId()).param(c.id()).param(c.bankCode()).param(txSource)
                .param(truncate(credit.reference(), 60)).param(credit.requestId()).param(credit.channelCode())
                .param(credit.paidAt()).param(credit.amount()).param(credit.currency())
                .param(truncate(credit.accountTyped(), 100)).param(truncate(credit.payerName(), 150))
                .param(truncate(credit.payerPhone(), 64)).param(credit.narration()).param(credit.balanceAfter())
                .param(toJson(raw))
                .query(UUID.class).optional().orElse(null));
        if (txId == null) {
            return new Outcome("ALREADY_RECORDED", null, null, null);
        }
        // The same credit may already be in the books through another route (notification, then statement)
        if (alreadyRecorded(c, credit.reference())) {
            mark(c, txId, "DUPLICATE", "A bank payment with this reference is already recorded");
            return new Outcome("ALREADY_RECORDED", null, null, null);
        }
        String problem = problem(c, credit);
        if (problem != null) {
            mark(c, txId, "ERROR", problem);
            return new Outcome("ERROR", null, null, problem);
        }
        try {
            return system.asOrganization(c.organizationId(), userId, () -> {
                Payment p = payments.recordBank(c.id(), source, truncate(credit.reference(), 60), credit.amount(),
                        credit.paidAt(), credit.payerName(), credit.payerPhone(), accountForMatching);
                jdbc.sql("""
                                UPDATE bank_transactions SET payment_id = ?, processed_at = now(),
                                       processing_status = CASE WHEN ? = 'UNMATCHED' THEN 'UNMATCHED' ELSE 'MATCHED' END
                                 WHERE id = ?""")
                        .param(p.getId()).param(p.getStatus().name()).param(txId).update();
                jdbc.sql("UPDATE payment_channels SET verified_at = now() WHERE id = ? AND verified_at IS NULL")
                        .param(c.id()).update();
                boolean isMatched = p.getStatus() != Payment.Status.UNMATCHED;
                return new Outcome(isMatched ? "MATCHED" : "UNMATCHED", p.getReceiptNo(),
                        p.getTenant() == null ? null : p.getTenant().getName(),
                        isMatched ? null : "No tenant found for the account; staff will match it");
            });
        } catch (RuntimeException e) {
            log.error("Recording bank credit {} failed; kept for review", credit.reference(), e);
            mark(c, txId, "ERROR", String.valueOf(e.getMessage()));
            return new Outcome("ERROR", null, null, "Could not record this payment; it is kept for review");
        }
    }

    /** Why this credit must not become a payment, or null if it is fine. */
    private String problem(ChannelInfo c, BankCredit credit) {
        if (credit.amount() == null || credit.amount().signum() <= 0) {
            return "amount is missing or not positive";
        }
        if (credit.currency() != null && !credit.currency().isBlank() && !"KES".equalsIgnoreCase(credit.currency())) {
            return "currency " + credit.currency() + " is not KES";
        }
        // A notification for another account is never booked on this channel
        String ours = c.bankAccount() == null ? "" : crypto.decrypt(c.bankAccount());
        String credited = credit.creditedAccount() == null ? "" : credit.creditedAccount().trim();
        if (!credited.isEmpty() && !ours.isEmpty() && !credited.equals(ours)) {
            return "credited account " + credited + " is not this channel's account";
        }
        return null;
    }

    private boolean alreadyRecorded(ChannelInfo c, String reference) {
        return system.asOrganization(c.organizationId(), null, () -> jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM payments WHERE method = 'BANK' AND upper(reference) = upper(?))
                            OR EXISTS (SELECT 1 FROM bank_transactions WHERE payment_channel_id = ? AND transaction_reference = ?
                                                                       AND payment_id IS NOT NULL)""")
                .param(truncate(reference, 60)).param(c.id()).param(truncate(reference, 60))
                .query(Boolean.class).single());
    }

    private void mark(ChannelInfo c, UUID txId, String status, String reason) {
        system.asOrganization(c.organizationId(), null, () -> jdbc.sql("""
                        UPDATE bank_transactions SET processing_status = ?, result_desc = ?, processed_at = now()
                         WHERE id = ?""")
                .param(status).param(reason).param(txId).update());
    }

    /** Unit payment codes and tenant account codes of this organisation: normalised -> as stored. */
    private Map<String, String> knownCodes() {
        Map<String, String> codes = new HashMap<>();
        jdbc.sql("""
                        SELECT payment_code FROM units WHERE deleted_at IS NULL AND payment_code IS NOT NULL
                        UNION
                        SELECT account_code FROM tenants WHERE deleted_at IS NULL AND account_code IS NOT NULL""")
                .query(String.class).list()
                .forEach(code -> codes.putIfAbsent(normalise(code), code));
        return codes;
    }

    /**
     * The one unit or tenant code mentioned in a narration such as "MPESA C2B 2547XXXXXXXX 1234567890#KLM-B4 JOHN",
     * also when the payer typed it with spaces ("KLM B4"). Null when there is none, or more than one.
     */
    static String findCode(String narration, Map<String, String> codes) {
        if (narration == null || narration.isBlank() || codes.isEmpty()) return null;
        String[] tokens = narration.toUpperCase(Locale.ROOT).split("[\\s#/,;:|()\\[\\]]+");
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < tokens.length; i++) {
            StringBuilder joined = new StringBuilder();
            for (int j = i; j < Math.min(tokens.length, i + 3); j++) {
                joined.append(tokens[j]);
                String key = normalise(joined.toString());
                if (key.length() >= 3 && codes.containsKey(key)) {
                    found.add(codes.get(key));
                }
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static String normalise(String code) {
        return code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static List<List<String>> readRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose the bank statement CSV file to upload");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw ApiException.badRequest("CSV_ONLY", "Save the statement as CSV (File → Save as → CSV) and upload that");
        }
        if (name.endsWith(".pdf")) {
            throw ApiException.badRequest("CSV_ONLY", "Download the statement as CSV or Excel from internet banking, not PDF");
        }
        try {
            return CsvReader.readRows(file.getInputStream(), BankStatementParser.MAX_LINES + 1);
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Could not read the file");
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
