package com.propertypro.repository;

import com.propertypro.entity.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PropertyRepository extends JpaRepository<Property, UUID>, JpaSpecificationExecutor<Property> {

    @Query("select count(p) > 0 from Property p where upper(p.code) = upper(:code)")
    boolean codeExists(@Param("code") String code);

    long countByOwnerId(UUID ownerId);
}
