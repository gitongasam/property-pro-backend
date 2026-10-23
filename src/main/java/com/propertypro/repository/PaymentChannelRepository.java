package com.propertypro.repository;

import com.propertypro.entity.PaymentChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentChannelRepository extends JpaRepository<PaymentChannel, UUID> {

    List<PaymentChannel> findByActiveTrueOrderByCreatedAtAsc();
}
