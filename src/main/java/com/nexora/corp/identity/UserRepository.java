package com.nexora.corp.identity;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(Long id);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.code = 'SUPER_ADMIN' AND u.active")
    long countActiveSuperAdmins();

    boolean existsByEmail(String email);
}

