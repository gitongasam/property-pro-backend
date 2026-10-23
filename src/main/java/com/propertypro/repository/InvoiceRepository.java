package com.propertypro.repository;

import com.propertypro.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    Set<Invoice.Status> OPEN = EnumSet.of(Invoice.Status.ISSUED, Invoice.Status.PARTIALLY_PAID);

    @EntityGraph(attributePaths = {"tenant", "unit", "property", "lease", "lines"})
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findWithLines(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"tenant", "unit", "property", "lease"})
    @Query("""
            select i from Invoice i
             where i.status in :statuses
               and i.penaltyAppliedAt is null
               and i.lease is not null
               and i.source <> :exclude
               and i.dueDate < :before
            """)
    List<Invoice> findPenaltyCandidates(@Param("statuses") Collection<Invoice.Status> statuses,
                                        @Param("exclude") Invoice.Source exclude,
                                        @Param("before") LocalDate before);

    /** Open invoices past due with no penalty yet (the penalty job filters by each lease's grace days). */
    default List<Invoice> findPenaltyCandidates(LocalDate before) {
        return findPenaltyCandidates(OPEN, Invoice.Source.PENALTY, before);
    }

    @EntityGraph(attributePaths = {"tenant", "unit"})
    @Query("select i from Invoice i where i.status in :statuses and i.dueDate = :date")
    List<Invoice> findByStatusAndDueDate(@Param("statuses") Collection<Invoice.Status> statuses,
                                         @Param("date") LocalDate date);

    /** Open invoices due on one date (reminder SMS). */
    default List<Invoice> findOpenDueOn(LocalDate date) {
        return findByStatusAndDueDate(OPEN, date);
    }
}
