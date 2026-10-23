package com.propertypro.controller;

import com.propertypro.dto.OperationsDtos.ExpenseRequest;
import com.propertypro.dto.OperationsDtos.ExpenseResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Expense;
import com.propertypro.service.ExpenseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Expenses")
@RestController
@RequestMapping("/api/v1/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenses;

    @GetMapping
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    public PageResponse<ExpenseResponse> list(@RequestParam(required = false) UUID propertyId,
                                              @RequestParam(required = false) Expense.Status status,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @PageableDefault(size = 20, sort = "expenseDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return expenses.list(propertyId, status, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    public ExpenseResponse get(@PathVariable UUID id) {
        return expenses.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse create(@Valid @RequestBody ExpenseRequest body) {
        return expenses.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    public ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest body) {
        return expenses.update(id, body);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('EXPENSE_APPROVE')")
    public ExpenseResponse approve(@PathVariable UUID id) {
        return expenses.approve(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('EXPENSE_APPROVE')")
    public ExpenseResponse reject(@PathVariable UUID id) {
        return expenses.reject(id);
    }

    @PostMapping("/{id}/paid")
    @PreAuthorize("hasAuthority('EXPENSE_APPROVE')")
    public ExpenseResponse markPaid(@PathVariable UUID id, @RequestParam(required = false) String method,
                                    @RequestParam(required = false) String reference) {
        return expenses.markPaid(id, method, reference);
    }
}
