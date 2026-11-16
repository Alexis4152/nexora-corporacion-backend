package com.nexora.corp.customers;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @EntityGraph(attributePaths = "tags")
    Optional<Customer> findWithTagsById(Long id);

    Optional<Customer> findFirstByEmailAndArchivedFalse(String email);

    Optional<Customer> findFirstByRfc(String rfc);

    /** Serializa operaciones financieras del mismo cliente (pagos, aplicaciones) entre administradores. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> lockById(Long id);
}

