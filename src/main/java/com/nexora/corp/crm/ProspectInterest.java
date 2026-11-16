package com.nexora.corp.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "productId")
public class ProspectInterest {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "plan_id")
    private Long planId;
}

