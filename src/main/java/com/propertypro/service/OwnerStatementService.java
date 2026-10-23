package com.propertypro.service;

import com.propertypro.dto.OperationsDtos.GenerateResult;
import com.propertypro.dto.OperationsDtos.OwnerStatementResponse;
import com.propertypro.dto.OperationsDtos.RemittanceRequest;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Monthly owner statements:
 *   opening balance (last statement's unpaid net)
 * + rent collected for the owner (only charge types marked owner_income; deposits are held, not paid out)
 * − approved expenses charged to the owner
 * − management fee (property rate, else owner rate) and VAT on the fee
 * = net payable
 *
 * DRAFT statements are rebuilt on each run; APPROVED, SENT and PAID ones are never touched.
 */
public interface OwnerStatementService {

    GenerateResult generate(YearMonth period);

    List<OwnerStatementResponse> list(YearMonth period);

    OwnerStatementResponse get(UUID id);

    OwnerStatementResponse approve(UUID id);

    /** Emails the statement to the owner (approved statements only). */
    OwnerStatementResponse send(UUID id);

    OwnerStatementResponse recordRemittance(UUID id, RemittanceRequest req);
}
