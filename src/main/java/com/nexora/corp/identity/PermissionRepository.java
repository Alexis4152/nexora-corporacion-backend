package com.nexora.corp.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findAllByOrderBySortOrderAsc();

    List<Permission> findByCodeIn(Collection<String> codes);
}

