package com.propertypro.repository;

import com.propertypro.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    @EntityGraph(attributePaths = {"tenant", "unit", "property", "lease"})
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findWithDetails(@Param("id") UUID id);
}
