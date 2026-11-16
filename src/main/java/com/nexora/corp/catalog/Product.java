package com.nexora.corp.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Producto del catálogo dinámico (POS, HOTEL…). Nada de estos datos está fijo en código. */
@Entity
@Table(name = "products")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String tagline;
    private String description;
    private String category;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private String icon;

    @Column(name = "logo_file_id")
    private Long logoFileId;

    @Column(name = "marketing_url")
    private String marketingUrl;

    @Column(name = "system_url")
    private String systemUrl;

    @Column(name = "default_trial_days")
    private int defaultTrialDays;

    @Column(name = "default_grace_days")
    private int defaultGraceDays;

    @Column(name = "renewal_policy")
    private String renewalPolicy;

    @Column(name = "suspension_policy")
    private String suspensionPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_settings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> allowedSettings = new HashMap<>();

    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order")
    private int sortOrder;

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

