package com.propertypro.repository;

import com.propertypro.entity.Bank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankRepository extends JpaRepository<Bank, String> {

    List<Bank> findByActiveTrueOrderBySortOrderAsc();
}
