package com.propertypro.repository;

import com.propertypro.entity.ChargeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargeTypeRepository extends JpaRepository<ChargeType, UUID> {

    Optional<ChargeType> findByCode(String code);

    List<ChargeType> findByActiveTrueOrderByAllocationPriorityAsc();
}
