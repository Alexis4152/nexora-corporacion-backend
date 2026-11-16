package com.nexora.corp.customers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Cliente corporativo único: contrate uno o varios productos, es UN registro (Cliente → Suscripciones → Producto → Plan). */
@Entity
@Table(name = "customers")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "person_type", nullable = false)
    private String personType;

    @Column(name = "trade_name", nullable = false)
    private String tradeName;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    private String rfc;

    @Column(name = "tax_regime")
    private String taxRegime;

    @Column(name = "tax_zip")
    private String taxZip;

    @Column(nullable = false)
    private String email;

    private String phone;
    private String whatsapp;
    private String address;
    private String city;
    private String state;

    @Builder.Default
    private String country = "MX";

    private String industry;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "source_prospect_id")
    private Long sourceProspectId;

    private String notes;
    private boolean archived;

    @Column(name = "customer_since", nullable = false)
    private LocalDate customerSince;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "customer_tags", joinColumns = @JoinColumn(name = "customer_id"), inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    private int version;

    @PrePersist
    @PreUpdate
    void normalize() {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (rfc != null) {
            rfc = rfc.trim().toUpperCase(Locale.ROOT);
        }
    }
}

