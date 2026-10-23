package com.propertypro.controller;

import com.propertypro.dto.OrganizationDtos.ProfileRequest;
import com.propertypro.dto.OrganizationDtos.ProfileResponse;
import com.propertypro.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organisation")
@RestController
@RequestMapping("/api/v1/organization")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;

    @GetMapping
    public ProfileResponse get() {
        return service.get();
    }

    @Operation(summary = "Update the business profile and defaults (VAT on fees, billing day, reminders)")
    @PutMapping
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    public ProfileResponse update(@Valid @RequestBody ProfileRequest body) {
        return service.update(body);
    }
}
