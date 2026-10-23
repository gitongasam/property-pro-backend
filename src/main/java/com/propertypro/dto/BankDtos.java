package com.propertypro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Banks, bank payment notifications and bank statement uploads. */
public final class BankDtos {

    private BankDtos() {}

    /** A bank in the Settings › Payments picker. */
    public record   BankResponse(String code, String name, String paybill, String accountFormat, String integration,
                               boolean automaticNotifications) {}

    /**
     * One credit to the landlord's account, from a bank notification or a statement line, in the same shape
     * whichever bank it came from.
     */
    public record BankCredit(
            String reference,
            String requestId,
            String channelCode,
            OffsetDateTime paidAt,
            BigDecimal amount,
            String currency,
            /** What the payer typed or what the statement narration says, e.g. 1234567890#KLM-B4. */
            String accountTyped,
            String payerName,
            String payerPhone,
            String narration,
            /** The account the bank says it credited; checked against the channel's. */
            String creditedAccount,
            BigDecimal balanceAfter) {}

    /** What a bank connector wants sent back, with the HTTP status. */
    public record BankAck(int httpStatus, Object body) {}

    public record StatementLineResult(int line, LocalDate date, BigDecimal amount, String narration, String reference,
                                      /** NEW (dry run), MATCHED, UNMATCHED, ALREADY_RECORDED or ERROR */
                                      String status,
                                      /** The unit or tenant code found in the narration, if any. */
                                      String matchedCode,
                                      String tenantName,
                                      String receiptNo,
                                      String problem) {}

    public record StatementImportResult(boolean dryRun, int linesRead, int credits, int recorded, int matched,
                                        int unmatched, int alreadyRecorded, BigDecimal totalCredited,
                                        List<StatementLineResult> lines, List<String> problems) {}
}
