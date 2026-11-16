package com.nexora.corp.customers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerNoteRepository extends JpaRepository<CustomerNote, Long> {

    List<CustomerNote> findByCustomerIdOrderByPinnedDescCreatedAtDesc(Long customerId);
}

