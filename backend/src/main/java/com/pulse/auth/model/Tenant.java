package com.pulse.auth.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Runtime source of truth for tenant identity. Populated by two paths:
 *   1. Bootstrap from {@code application.yml} at startup — rows get {@code origin='bootstrap'}.
 *   2. {@code POST /api/v1/tenants} — rows get {@code origin='api'}.
 *
 * <p>Bootstrap is idempotent: rows with {@code origin='api'} are never modified or deleted by
 * the YAML refresh, so an operator can add a tenant via API without having to edit the YAML.
 */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String origin = "bootstrap";

    @Column(nullable = false)
    private String status = "active";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
