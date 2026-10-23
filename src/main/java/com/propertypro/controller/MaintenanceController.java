package com.propertypro.controller;

import com.propertypro.dto.OperationsDtos.AssignRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceCreateRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceResponse;
import com.propertypro.dto.OperationsDtos.RatingRequest;
import com.propertypro.dto.OperationsDtos.StatusRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.MaintenanceRequest;
import com.propertypro.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Maintenance")
@RestController
@RequestMapping("/api/v1/maintenance-requests")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenance;

    @Operation(summary = "The board; open=true shows everything not yet resolved")
    @GetMapping
    @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
    public PageResponse<MaintenanceResponse> list(@RequestParam(required = false) MaintenanceRequest.Status status,
                                                  @RequestParam(required = false) UUID propertyId,
                                                  @RequestParam(required = false) UUID assignedTo,
                                                  @RequestParam(defaultValue = "false") boolean open,
                                                  @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return maintenance.list(status, propertyId, assignedTo, open, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
    public MaintenanceResponse get(@PathVariable UUID id) {
        return maintenance.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('MAINTENANCE_CREATE','PORTAL_TENANT')")
    public MaintenanceResponse create(@Valid @RequestBody MaintenanceCreateRequest body) {
        return maintenance.create(body);
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('MAINTENANCE_ASSIGN')")
    public MaintenanceResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest body) {
        return maintenance.assign(id, body);
    }

    @Operation(summary = "Move the request on the board; resolving with createExpense=true records the cost")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('MAINTENANCE_UPDATE')")
    public MaintenanceResponse status(@PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return maintenance.changeStatus(id, body);
    }

    @PostMapping("/{id}/rating")
    @PreAuthorize("hasAnyAuthority('PORTAL_TENANT','MAINTENANCE_UPDATE')")
    public MaintenanceResponse rate(@PathVariable UUID id, @Valid @RequestBody RatingRequest body) {
        return maintenance.rate(id, body.rating());
    }
}
