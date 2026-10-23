package com.propertypro.repository;

import com.propertypro.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findFirstByOrganizationIdAndStatusIn(UUID organizationId,
                                                               Collection<Subscription.Status> statuses);

    Optional<Subscription> findFirstByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
