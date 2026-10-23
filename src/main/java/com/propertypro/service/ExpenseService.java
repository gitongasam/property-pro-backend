package com.propertypro.service;

import com.propertypro.dto.OperationsDtos.ExpenseRequest;
import com.propertypro.dto.OperationsDtos.ExpenseResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Expense;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

/** Expenses: PENDING_APPROVAL → APPROVED → PAID (or REJECTED). Approved owner expenses go on owner statements. */
public interface ExpenseService {

    PageResponse<ExpenseResponse> list(UUID propertyId, Expense.Status status, LocalDate from, LocalDate to, Pageable pageable);

    ExpenseResponse get(UUID id);

    ExpenseResponse create(ExpenseRequest req);

    ExpenseResponse update(UUID id, ExpenseRequest req);

    ExpenseResponse approve(UUID id);

    ExpenseResponse reject(UUID id);

    ExpenseResponse markPaid(UUID id, String method, String reference);

    Expense find(UUID id);
}
