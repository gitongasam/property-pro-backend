package com.propertypro.service;

import com.propertypro.dto.OperationsDtos.AssignRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceCreateRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceResponse;
import com.propertypro.dto.OperationsDtos.StatusRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.MaintenanceRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Maintenance board: requests move OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED, every move logged. */
public interface MaintenanceService {

    PageResponse<MaintenanceResponse> list(MaintenanceRequest.Status status, UUID propertyId, UUID assignedTo, boolean openOnly, Pageable pageable);

    MaintenanceResponse get(UUID id);

    MaintenanceResponse create(MaintenanceCreateRequest req);

    MaintenanceResponse assign(UUID id, AssignRequest req);

    MaintenanceResponse changeStatus(UUID id, StatusRequest req);

    MaintenanceResponse rate(UUID id, short rating);

    MaintenanceRequest find(UUID id);
}
