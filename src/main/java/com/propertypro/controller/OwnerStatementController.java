package com.propertypro.controller;

import com.propertypro.common.ApiException;
import com.propertypro.dto.OperationsDtos.GenerateResult;
import com.propertypro.dto.OperationsDtos.OwnerStatementResponse;
import com.propertypro.dto.OperationsDtos.RemittanceRequest;
import com.propertypro.service.OwnerStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Tag(name = "Owner statements")
@RestController
@RequestMapping("/api/v1/owner-statements")
@RequiredArgsConstructor
public class OwnerStatementController {

    private final OwnerStatementService statements;

    @GetMapping
    @PreAuthorize("hasAuthority('STATEMENT_VIEW')")
    public List<OwnerStatementResponse> list(@RequestParam(required = false) String period) {
        return statements.list(period == null || period.isBlank() ? null : parse(period));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('STATEMENT_VIEW')")
    public OwnerStatementResponse get(@PathVariable UUID id) {
        return statements.get(id);
    }

    @Operation(summary = "Build (or rebuild) draft statements for every owner, e.g. period=2026-10")
    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('STATEMENT_GENERATE')")
    public GenerateResult generate(@RequestParam String period) {
        return statements.generate(parse(period));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('STATEMENT_APPROVE')")
    public OwnerStatementResponse approve(@PathVariable UUID id) {
        return statements.approve(id);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('STATEMENT_APPROVE')")
    public OwnerStatementResponse send(@PathVariable UUID id) {
        return statements.send(id);
    }

    private static YearMonth parse(String period) {
        try {
            return YearMonth.parse(period.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw com.propertypro.common.ApiException.badRequest("INVALID_PERIOD", "Period must look like 2026-10");
        }
    }

    @PostMapping("/{id}/remittances")
    @PreAuthorize("hasAuthority('REMITTANCE_RECORD')")
    public OwnerStatementResponse remit(@PathVariable UUID id, @Valid @RequestBody RemittanceRequest body) {
        return statements.recordRemittance(id, body);
    }
}
