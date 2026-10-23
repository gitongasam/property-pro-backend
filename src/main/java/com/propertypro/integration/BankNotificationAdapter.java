package com.propertypro.integration;

import com.propertypro.dto.BankDtos.BankCredit;
import org.springframework.http.HttpHeaders;

import java.util.Map;

/**
 * One bank's payment notification API. Each bank signs, shapes and expects a
 * reply to its notifications differently; everything after {@link #parse}
 * (storing, de-duplicating, matching, receipting) is shared.
 *
 * <p>To add a bank: implement this as a {@code @Component}, and set the bank's
 * {@code integration} to API in a migration.
 */
public interface BankNotificationAdapter {

    /** The banks.code this connector handles, e.g. "KCB". */
    String bankCode();

    /** Whether the request really came from the bank (signature, shared secret...). */
    boolean verify(String rawBody, HttpHeaders headers);

    /** The credit described by a verified notification. */
    BankCredit parse(Map<String, Object> body);

    /** The reply body the bank expects: accepted (it stops retrying) or refused. */
    Object acknowledgement(String reference, boolean accepted, String message);
}
