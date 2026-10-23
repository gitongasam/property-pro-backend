package com.propertypro.repository;

import com.propertypro.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface  UnitRepository extends JpaRepository<Unit, UUID>, JpaSpecificationExecutor<Unit> {

    long countByPropertyId(UUID propertyId);

    @Modifying
    @Query("update Unit u set u.deletedAt = :now where u.property.id = :propertyId and u.deletedAt is null")
    int softDeleteByProperty(@Param("propertyId") UUID propertyId, @Param("now") OffsetDateTime now);

    long countByPropertyIdAndStatus(UUID propertyId, Unit.Status status);

    @Query("select upper(u.unitNo) from Unit u where u.property.id = :propertyId and upper(u.unitNo) in :unitNos")
    List<String> findExistingUnitNos(@Param("propertyId") UUID propertyId, @Param("unitNos") Collection<String> unitNos);

    @Query("select upper(u.paymentCode) from Unit u where upper(u.paymentCode) in :codes")
    List<String> findExistingPaymentCodes(@Param("codes") Collection<String> codes);
}
