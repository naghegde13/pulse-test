package com.pulse.sor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the V83 NOT-NULL slug constraint on {@code domains}.
 *
 * <p>V83 added {@code slug NOT NULL UNIQUE(tenant_id, slug)} but the chat tool, REST
 * controller, and update path were never updated to set slug — every new INSERT failed
 * with a constraint violation until this fix shipped. This test asserts that any future
 * Domain INSERT without an explicit slug still succeeds, so a fourth callsite slipping
 * in (or someone removing the {@code @PrePersist} safety net) can't reintroduce the bug
 * silently.
 */
class DomainSlugRegressionTest {

    @Test
    void savingDomainWithoutSlug_derivesItFromName() {
        Domain d = new Domain();
        d.setId("test-dom-slug-regression");
        d.setTenantId("tenant-home-lending");
        d.setName("Loss & Mitigation");
        // Deliberately do NOT call d.setSlug(...) — the @PrePersist net is the contract under test.

        d.deriveSlugIfMissing();

        assertNotNull(d.getSlug(), "slug must not be null after save");
        assertTrue(!d.getSlug().isBlank(), "slug must not be blank after save");
        assertEquals("loss-mitigation", d.getSlug(),
                "slug must follow the canonical Slugify rules so it matches V83 backfill");
    }

    @Test
    void explicitSlug_isPreservedAcrossSave() {
        // The PrePersist must NOT clobber a caller-set slug — callers want to validate
        // uniqueness up-front and need their value to round-trip.
        Domain d = new Domain();
        d.setId("test-dom-slug-explicit");
        d.setTenantId("tenant-home-lending");
        d.setName("Some Display Name");
        d.setSlug("custom-slug-pre-set");

        d.deriveSlugIfMissing();
        assertEquals("custom-slug-pre-set", d.getSlug());
    }
}
