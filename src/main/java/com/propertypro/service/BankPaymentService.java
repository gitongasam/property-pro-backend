package com.propertypro.service;

import com.propertypro.dto.BankDtos.BankAck;
import com.propertypro.dto.BankDtos.BankResponse;
import com.propertypro.dto.BankDtos.StatementImportResult;
import org.springframework.http.HttpHeaders;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Rent paid into the landlord's bank account, from any bank or Sacco. Payments
 * arrive two ways and are handled the same after that (stored raw, never
 * recorded twice, matched by the unit code, receipted):
 * <ul>
 *   <li>a notification from a bank that has a connector (KCB today), and</li>
 *   <li>a statement the landlord uploads, which works for every bank.</li>
 * </ul>
 */
public interface BankPaymentService {

    /** Banks and Saccos the landlord can choose in Settings › Payments. */
    List<BankResponse> banks();

    /**
     * A bank's payment notification. The channel comes from the secret token in the URL; the bank's connector
     * checks the signature and reads the body. Genuine notifications are always acknowledged so the bank stops
     * retrying; problems are kept for staff.
     */
    BankAck notification(String token, String rawBody, HttpHeaders headers);

    /** Records the credits on an uploaded bank statement (CSV). dryRun=true only reads and matches. */
    StatementImportResult importStatement(UUID channelId, MultipartFile file, boolean dryRun);
}
