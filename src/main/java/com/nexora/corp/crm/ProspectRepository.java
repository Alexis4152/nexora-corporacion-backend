package com.nexora.corp.crm;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProspectRepository extends JpaRepository<Prospect, Long> {

    @EntityGraph(attributePaths = "interests")
    Optional<Prospect> findWithInterestsById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Prospect p WHERE p.id = :id")
    Optional<Prospect> lockById(Long id);
}

