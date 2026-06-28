package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import com.pulse.common.text.Slugify;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "domains")
public class Domain extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column
    private String description;

    @Column(name = "current_business_date")
    private java.time.LocalDate currentBusinessDate;

    @Column(name = "business_date_grain")
    private String businessDateGrain;

    @Column(name = "business_date_timezone")
    private String businessDateTimezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_date_config", columnDefinition = "jsonb")
    private java.util.Map<String, Object> businessDateConfig;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public java.time.LocalDate getCurrentBusinessDate() { return currentBusinessDate; }
    public void setCurrentBusinessDate(java.time.LocalDate currentBusinessDate) { this.currentBusinessDate = currentBusinessDate; }
    public String getBusinessDateGrain() { return businessDateGrain; }
    public void setBusinessDateGrain(String businessDateGrain) { this.businessDateGrain = businessDateGrain; }
    public String getBusinessDateTimezone() { return businessDateTimezone; }
    public void setBusinessDateTimezone(String businessDateTimezone) { this.businessDateTimezone = businessDateTimezone; }
    public java.util.Map<String, Object> getBusinessDateConfig() { return businessDateConfig; }
    public void setBusinessDateConfig(java.util.Map<String, Object> businessDateConfig) { this.businessDateConfig = businessDateConfig; }

    /**
     * Safety net for V83's NOT NULL slug constraint: if a caller forgot to set the slug,
     * derive it from the name at insert time using the canonical {@link Slugify} rules.
     * Callers SHOULD set the slug explicitly so they can validate uniqueness up-front and
     * surface a clean error to the user — this is a last-line defense, not the primary
     * path. Slug-on-rename policy (D1): once set, this method does NOT re-derive on update,
     * so the slug remains a stable identifier even when the display name changes.
     */
    @PrePersist
    void deriveSlugIfMissing() {
        if (slug == null || slug.isBlank()) {
            slug = Slugify.slugify(name);
        }
    }
}
