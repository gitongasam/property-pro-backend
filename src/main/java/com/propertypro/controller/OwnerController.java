package com.propertypro.controller;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.OwnerRequest;
import com.propertypro.dto.PortfolioDtos.OwnerResponse;
import com.propertypro.service.OwnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Owners (landlords)")
@RestController
@RequestMapping("/api/v1/owners")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    @Operation(summary = "List owners; q searches name, phone and email")
    @GetMapping
    @PreAuthorize("hasAuthority('OWNER_VIEW')")
    public PageResponse<OwnerResponse> list(@RequestParam(required = false) String q,
                                            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return ownerService.list(q, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('OWNER_VIEW')")
    public OwnerResponse get(@PathVariable UUID id) {
        return ownerService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('OWNER_MANAGE')")
    public OwnerResponse create(@Valid @RequestBody OwnerRequest body) {
        return ownerService.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('OWNER_MANAGE')")
    public OwnerResponse update(@PathVariable UUID id, @Valid @RequestBody OwnerRequest body) {
        return ownerService.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('OWNER_MANAGE')")
    public void delete(@PathVariable UUID id) {
        ownerService.delete(id);
    }
}
