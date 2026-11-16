package com.nexora.corp.customers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, Long> {

    List<CustomerContact> findByCustomerIdOrderByPrimaryDescNameAsc(Long customerId);
}

