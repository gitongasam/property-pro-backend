package com.propertypro.repository;

import com.propertypro.entity.Owner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID>, JpaSpecificationExecutor<Owner> {
}
