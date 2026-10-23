package com.propertypro.repository;

import com.propertypro.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID>, JpaSpecificationExecutor<Tenant> {

    @Query("select t from Tenant t where upper(t.accountCode) = upper(:code)")
    Optional<Tenant> findByAccountCode(@Param("code") String code);

    /** Phone numbers are stored as +2547XXXXXXXX; matches on the last 9 digits. */
    @Query("select t from Tenant t where t.phone like concat('%', :last9)")
    List<Tenant> findByPhoneEnding(@Param("last9") String last9);

    boolean existsByIdNumberHash(String idNumberHash);
}
