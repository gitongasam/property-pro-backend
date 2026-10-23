package com.propertypro.controller;

import com.propertypro.dto.ImportDtos.ImportResult;
import com.propertypro.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "Units")
@RestController
@RequestMapping("/api/v1/properties/{propertyId}/units/import")
@RequiredArgsConstructor
public class UnitImportController {

    private final ImportService imports;

    @Operation(summary = "Add many units to a property from a CSV. dryRun=true only checks the file")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public ImportResult importUnits(@PathVariable UUID propertyId,
                                                  @RequestPart("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importUnits(propertyId, file, dryRun);
    }
}
