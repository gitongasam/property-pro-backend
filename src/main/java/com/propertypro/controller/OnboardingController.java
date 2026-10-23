package com.propertypro.controller;

import com.propertypro.common.ApiException;
import com.propertypro.dto.ImportDtos.ImportResult;
import com.propertypro.dto.OnboardingDtos.OnboardingStatus;
import com.propertypro.dto.OnboardingDtos.ProfileRequest;
import com.propertypro.service.ImportService;
import com.propertypro.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Tag(name = "Onboarding")
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboarding;
    private final ImportService imports;

    @Operation(summary = "Set-up checklist: which steps are done, skipped or to do, and trial days left")
    @GetMapping
    public OnboardingStatus status() {
        return onboarding.status();
    }

    @Operation(summary = "Save the 'about your business' answers from the first wizard screen")
    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    public OnboardingStatus profile(@Valid @RequestBody ProfileRequest body) {
        return onboarding.saveProfile(body);
    }

    @Operation(summary = "Skip an optional step (TENANTS, MPESA, TEAM)")
    @PostMapping("/steps/{key}/skip")
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    public OnboardingStatus skip(@PathVariable String key) {
        return onboarding.skip(key, true);
    }

    @DeleteMapping("/steps/{key}/skip")
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    public OnboardingStatus unskip(@PathVariable String key) {
        return onboarding.skip(key, false);
    }

    @Operation(summary = "Finish the wizard (needs at least one property with units)")
    @PostMapping("/complete")
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    public OnboardingStatus complete() {
        return onboarding.complete();
    }

    @Operation(summary = "Import sitting tenants with what they owe today. dryRun=true only checks the file")
    @PostMapping(value = "/import/tenants", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('TENANT_MANAGE') and hasAuthority('LEASE_MANAGE')")
    public ImportResult importTenants(@RequestPart("file") MultipartFile file,
                                                    @RequestParam(required = false) UUID propertyId,
                                                    @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importTenants(propertyId, file, dryRun);
    }

    @Operation(summary = "Download a CSV template: tenants or units")
    @GetMapping("/templates/{kind}.csv")
    public ResponseEntity<byte[]> template(@PathVariable String kind) {
        String body = switch (kind) {
            case "tenants" -> ImportService.TENANT_TEMPLATE;
            case "units" -> ImportService.UNIT_TEMPLATE;
            default -> throw com.propertypro.common.ApiException.notFound("Template " + kind);
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"propertypro-" + kind + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
