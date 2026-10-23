package com.propertypro.controller;

import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.dto.PortfolioDtos.UnitResponse;
import com.propertypro.dto.PortfolioDtos.UnitStatusRequest;
import com.propertypro.service.UnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Units")
@RestController
@RequestMapping("/api/v1/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('UNIT_VIEW')")
    public UnitResponse get(@PathVariable UUID id) {
        return unitService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitResponse update(@PathVariable UUID id, @Valid @RequestBody UnitRequest body) {
        return unitService.update(id, body);
    }

    @Operation(summary = "Set VACANT, RESERVED, UNDER_MAINTENANCE or UNAVAILABLE (OCCUPIED comes from leases)")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitResponse setStatus(@PathVariable UUID id, @Valid @RequestBody UnitStatusRequest body) {
        return unitService.setStatus(id, body.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public void delete(@PathVariable UUID id) {
        unitService.delete(id);
    }
}
