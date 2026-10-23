package com.propertypro.repository;

import com.propertypro.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    @Query("select o from Organization o where lower(o.slug) = lower(:slug) and o.deletedAt is null")
    Optional<Organization> findBySlug(@Param("slug") String slug);

    @Query("select count(o) > 0 from Organization o where lower(o.slug) = lower(:slug)")
    boolean slugExists(@Param("slug") String slug);
}
