package com.nexora.corp.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderBySortOrderAscNameAsc();

    Optional<Product> findByCode(String code);

    boolean existsByCode(String code);
}

