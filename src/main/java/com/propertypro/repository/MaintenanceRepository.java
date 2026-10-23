package com.propertypro.repository;

import com.propertypro.entity.MaintenanceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRequest, UUID>,
        JpaSpecificationExecutor<MaintenanceRequest> {
}
