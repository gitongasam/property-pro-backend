package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.common.Money;
import com.propertypro.dto.OperationsDtos.ExpenseRequest;
import com.propertypro.dto.OperationsDtos.ExpenseResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Expense;
import com.propertypro.repository.ExpenseRepository;
import com.propertypro.repository.PropertyRepository;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExpenseServiceImpl implements ExpenseService {

    private final ExpenseRepository expenses;
    private final PropertyRepository properties;
    private final DocumentNumbers numbers;
    private final PlanGate planGate;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(UUID propertyId, Expense.Status status, LocalDate from, LocalDate to,
                                              Pageable pageable) {
        Specification<Expense> byProperty = (r, q, cb) -> cb.equal(r.get("property").get("id"), propertyId);
        Specification<Expense> byStatus = (r, q, cb) -> cb.equal(r.get("status"), status);
        Specification<Expense> fromDate = (r, q, cb) -> cb.greaterThanOrEqualTo(r.<LocalDate>get("expenseDate"), from);
        Specification<Expense> toDate = (r, q, cb) -> cb.lessThanOrEqualTo(r.<LocalDate>get("expenseDate"), to);
        var spec = Specification.allOf(propertyId == null ? null : byProperty, status == null ? null : byStatus,
                from == null ? null : fromDate, to == null ? null : toDate);
        return PageResponse.of(expenses.findAll(spec, pageable), ExpenseResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseResponse get(UUID id) {
        return ExpenseResponse.from(find(id));
    }

    @Override
    @Transactional
    public ExpenseResponse create(ExpenseRequest req) {
        planGate.requireWritable();
        Expense e = new Expense();
        e.setExpenseNo(numbers.next(DocumentNumbers.Type.EXPENSE));
        apply(e, req);
        return ExpenseResponse.from(expenses.save(e));
    }

    @Override
    @Transactional
    public ExpenseResponse update(UUID id, ExpenseRequest req) {
        planGate.requireWritable();
        Expense e = find(id);
        if (e.getOwnerStatementId() != null) {
            throw ApiException.conflict("ON_STATEMENT", "This expense is on an approved owner statement");
        }
        apply(e, req);
        return ExpenseResponse.from(e);
    }

    @Override
    @Transactional
    public ExpenseResponse approve(UUID id) {
        planGate.requireWritable();
        Expense e = find(id);
        if (e.getStatus() != Expense.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("NOT_PENDING", "Only an expense waiting for approval can be approved");
        }
        e.setStatus(Expense.Status.APPROVED);
        e.setApprovedBy(TenantContext.userId().orElse(null));
        e.setApprovedAt(OffsetDateTime.now());
        return ExpenseResponse.from(e);
    }

    @Override
    @Transactional
    public ExpenseResponse reject(UUID id) {
        planGate.requireWritable();
        Expense e = find(id);
        if (e.getStatus() != Expense.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("NOT_PENDING", "Only an expense waiting for approval can be rejected");
        }
        e.setStatus(Expense.Status.REJECTED);
        return ExpenseResponse.from(e);
    }

    @Override
    @Transactional
    public ExpenseResponse markPaid(UUID id, String method, String reference) {
        planGate.requireWritable();
        Expense e = find(id);
        if (e.getStatus() != Expense.Status.APPROVED) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the expense before marking it paid");
        }
        e.setStatus(Expense.Status.PAID);
        if (method != null) e.setPaymentMethod(method);
        if (reference != null) e.setReference(reference);
        return ExpenseResponse.from(e);
    }

    @Override
    public Expense find(UUID id) {
        return expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense"));
    }

    private void apply(Expense e, ExpenseRequest r) {
        Expense.ChargeTo chargeTo = r.chargeTo() == null ? Expense.ChargeTo.OWNER : r.chargeTo();
        if (chargeTo == Expense.ChargeTo.OWNER && r.propertyId() == null) {
            throw ApiException.badRequest("PROPERTY_REQUIRED", "Choose the property this expense is for");
        }
        e.setProperty(r.propertyId() == null ? null
                : properties.findById(r.propertyId()).orElseThrow(() -> ApiException.notFound("Property")));
        e.setUnitId(r.unitId());
        e.setMaintenanceRequestId(r.maintenanceRequestId());
        e.setCategory(r.category());
        e.setDescription(r.description().trim());
        e.setAmount(Money.of(r.amount()));
        e.setVatAmount(Money.of(r.vatAmount()));
        e.setExpenseDate(r.expenseDate() == null ? LocalDate.now() : r.expenseDate());
        e.setChargeTo(chargeTo);
        e.setPaymentMethod(r.paymentMethod());
        e.setReference(r.reference());
    }
}
