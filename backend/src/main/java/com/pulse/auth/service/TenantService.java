package com.pulse.auth.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.sor.repository.DomainRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Runtime source of truth for tenant identity is the {@code tenants} table.
 *
 * <p>The YAML config under {@code pulse.tenants.definitions} is a bootstrap seed: at startup
 * {@link #bootstrapFromYaml()} inserts any YAML-declared tenants that are missing from the
 * table. Rows with {@code origin='api'} (created via {@code POST /api/v1/tenants}) are never
 * touched by the bootstrap pass, so an operator can add a tenant via API without editing YAML.
 *
 * <p>The read API still returns {@link TenantDefinition} so callers don't have to change when
 * the source flipped from config to database. The {@link #createTenant} write API returns the
 * full {@link Tenant} entity because API consumers care about {@code origin} and {@code status}.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    static final String ORIGIN_BOOTSTRAP = "bootstrap";
    static final String ORIGIN_API = "api";

    private final TenantRepository tenantRepository;
    private final TenantConfig tenantConfig;
    private final DomainRepository domainRepo;

    public TenantService(TenantRepository tenantRepository,
                         TenantConfig tenantConfig,
                         DomainRepository domainRepo) {
        this.tenantRepository = tenantRepository;
        this.tenantConfig = tenantConfig;
        this.domainRepo = domainRepo;
    }

    // ------------------------------------------------------------
    // Bootstrap
    // ------------------------------------------------------------

    @PostConstruct
    void bootstrapFromYaml() {
        List<TenantDefinition> defs = tenantConfig.getDefinitions();
        if (defs == null || defs.isEmpty()) return;

        for (TenantDefinition def : defs) {
            if (def.getId() == null || def.getId().isBlank()) {
                log.warn("Skipping YAML tenant with blank id (name={})", def.getName());
                continue;
            }
            if (tenantRepository.existsById(def.getId())) {
                continue;
            }
            if (def.getSlug() != null && tenantRepository.existsBySlug(def.getSlug())) {
                log.warn("Skipping bootstrap of tenant {}: slug '{}' already taken by another row",
                        def.getId(), def.getSlug());
                continue;
            }
            Tenant t = new Tenant();
            t.setId(def.getId());
            t.setName(def.getName());
            t.setSlug(def.getSlug());
            t.setOrigin(ORIGIN_BOOTSTRAP);
            t.setStatus("active");
            tenantRepository.save(t);
            log.info("Bootstrapped tenant {} ({}) from YAML", def.getId(), def.getSlug());
        }
    }

    // ------------------------------------------------------------
    // Read API — preserves the pre-V87 TenantDefinition contract
    // ------------------------------------------------------------

    public List<TenantDefinition> listTenants() {
        return tenantRepository.findAll().stream().map(TenantService::toDefinition).toList();
    }

    public TenantDefinition getTenant(String tenantId) {
        return tenantRepository.findById(tenantId)
                .map(TenantService::toDefinition)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
    }

    public boolean tenantExists(String tenantId) {
        return tenantRepository.existsById(tenantId);
    }

    public List<String> getDomainsForTenant(String tenantId) {
        return domainRepo.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(d -> d.getName()).toList();
    }

    // ------------------------------------------------------------
    // Write API — origin='api'
    // ------------------------------------------------------------

    @Transactional
    public Tenant createTenant(String id, String name, String slug) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug is required");
        }
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase kebab-case: [a-z0-9]+(-[a-z0-9]+)*");
        }
        if (tenantRepository.existsById(id)) {
            throw new IllegalStateException("Tenant id already exists: " + id);
        }
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalStateException("Tenant slug already exists: " + slug);
        }
        Tenant t = new Tenant();
        t.setId(id);
        t.setName(name);
        t.setSlug(slug);
        t.setOrigin(ORIGIN_API);
        t.setStatus("active");
        return tenantRepository.save(t);
    }

    // ------------------------------------------------------------
    // Write API — updateTenant
    // ------------------------------------------------------------

    @Transactional
    public Tenant updateTenant(String tenantId, String name) {
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        if (name != null && !name.isBlank()) {
            if (!name.equals(t.getName())) {
                tenantRepository.findByName(name)
                        .filter(other -> !other.getId().equals(tenantId))
                        .ifPresent(conflict -> {
                            throw new IllegalStateException("Tenant name already exists: " + name);
                        });
                t.setName(name);
            }
        }
        return tenantRepository.save(t);
    }

    /**
     * Retrieve the raw {@link Tenant} entity for internal callers that need
     * {@code origin}, {@code status}, etc.
     */
    public Tenant getTenantEntity(String tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private static TenantDefinition toDefinition(Tenant t) {
        TenantDefinition d = new TenantDefinition();
        d.setId(t.getId());
        d.setName(t.getName());
        d.setSlug(t.getSlug());
        return d;
    }
}
