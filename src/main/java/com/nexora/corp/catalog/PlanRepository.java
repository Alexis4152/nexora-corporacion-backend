package com.nexora.corp.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByProductIdOrderBySortOrderAscNameAsc(Long productId);

    List<Plan> findAllByOrderByProductIdAscSortOrderAsc();

    boolean existsByProductIdAndCode(Long productId, String code);
}

