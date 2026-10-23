package com.propertypro.repository;

import com.propertypro.entity.Lease;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaseRepository extends JpaRepository<Lease, UUID>, JpaSpecificationExecutor<Lease> {

    /** The lease a tenant in this unit is on today (for matching M-Pesa payments by unit code). */
    @EntityGraph(attributePaths = {"tenant", "unit"})
    @Query("""
            select l from Lease l
             where l.unit.id = :unitId and l.status in :statuses and l.startDate <= :today
             order by l.startDate desc
            """)
    List<Lease> findCurrentForUnit(@Param("unitId") UUID unitId,
                                   @Param("statuses") Collection<Lease.Status> statuses,
                                   @Param("today") LocalDate today);

    @EntityGraph(attributePaths = {"tenant", "unit"})
    @Query("select l from Lease l where l.tenant.id = :tenantId and l.status in :statuses order by l.startDate desc")
    List<Lease> findByTenantAndStatus(@Param("tenantId") UUID tenantId,
                                      @Param("statuses") Collection<Lease.Status> statuses);

    /** Leases due an invoice on or before the given date (the billing job). */
    @EntityGraph(attributePaths = {"tenant", "unit", "unit.property", "charges"})
    @Query("""
            select distinct l from Lease l
             where l.status in :statuses and l.nextBillingDate is not null and l.nextBillingDate <= :date
            """)
    List<Lease> findDueForBilling(@Param("statuses") Collection<Lease.Status> statuses, @Param("date") LocalDate date);

    @Query("select l from Lease l where l.status in :statuses and l.endDate is not null and l.endDate < :date")
    List<Lease> findEnded(@Param("statuses") Collection<Lease.Status> statuses, @Param("date") LocalDate date);

    @EntityGraph(attributePaths = {"tenant", "unit", "unit.property"})
    @Query("select l from Lease l where l.id = :id")
    Optional<Lease> findWithDetails(@Param("id") UUID id);
}
