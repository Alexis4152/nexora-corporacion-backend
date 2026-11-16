package com.nexora.corp.crm;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "prospects")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Prospect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    private String phone;
    private String whatsapp;
    private String email;
    private String city;
    private String state;

    @Builder.Default
    private String country = "MX";

    private String industry;

    @Column(nullable = false)
    private String source;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "estimated_budget")
    private BigDecimal estimatedBudget;

    @Column(name = "estimated_value")
    private BigDecimal estimatedValue;

    private int probability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProspectStage stage = ProspectStage.NEW;

    @Column(name = "stage_changed_at")
    @Builder.Default
    private Instant stageChangedAt = Instant.now();

    @Column(name = "next_contact_at")
    private Instant nextContactAt;

    private String comments;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "lost_detail")
    private String lostDetail;

    @Column(name = "converted_customer_id")
    private Long convertedCustomerId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "prospect_interests", joinColumns = @JoinColumn(name = "prospect_id"))
    @Builder.Default
    private Set<ProspectInterest> interests = new HashSet<>();

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
}

