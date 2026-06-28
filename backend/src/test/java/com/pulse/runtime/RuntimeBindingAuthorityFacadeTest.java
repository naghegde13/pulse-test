package com.pulse.runtime;

import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.ResolutionResult;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.StorageRoots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * ARCH-017 + PKT-FINAL-5 / BUG-39: RuntimeBindingAuthorityFacade resolution
 * contract.
 *
 * <p>BUG-39 fold-in: runtime bindings are deployment-global, so
 * {@code resolvePrimaryBinding(environment)} and
 * {@code resolveLocalProofBinding()} take no tenant arg. The facade still
 * guarantees that DIAGNOSTIC rows never surface as operational authority.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeBindingAuthorityFacadeTest {

    @Mock
    private RuntimeBindingRepository bindingRepository;

    @InjectMocks
    private RuntimeBindingAuthorityFacade facade;

    @Test
    @DisplayName("resolvePrimaryBinding returns Resolved when active PRIMARY binding exists")
    void resolvePrimaryBindingReturnsResolved() {
        RuntimeBinding primary = primaryBinding("integration", "GCP");
        when(bindingRepository.findActivePrimary("integration"))
                .thenReturn(Optional.of(primary));

        ResolutionResult<RuntimeBinding> result = facade.resolvePrimaryBinding("integration");

        assertInstanceOf(ResolutionResult.Resolved.class, result);
        RuntimeBinding resolved = ((ResolutionResult.Resolved<RuntimeBinding>) result).value();
        assertEquals("integration", resolved.getEnvironment());
        assertEquals("GCP", resolved.getBindingKind());
        assertTrue(resolved.isPrimary());
    }

    @Test
    @DisplayName("resolvePrimaryBinding returns Unresolved with 'binding_missing' when no binding")
    void resolvePrimaryBindingReturnsMissingWhenNone() {
        when(bindingRepository.findActivePrimary("prod"))
                .thenReturn(Optional.empty());

        ResolutionResult<RuntimeBinding> result = facade.resolvePrimaryBinding("prod");

        assertInstanceOf(ResolutionResult.Unresolved.class, result);
        ResolutionResult.Unresolved<RuntimeBinding> unresolved =
                (ResolutionResult.Unresolved<RuntimeBinding>) result;
        assertEquals("binding_missing", unresolved.blockerCode());
        // BUG-39: message references deployment scope, not a tenant.
        assertTrue(unresolved.message().toLowerCase().contains("deployment"),
                "Message should reference deployment scope, was: " + unresolved.message());
    }

    @Test
    @DisplayName("resolveLocalProofBinding returns Unresolved with 'local_roots_incomplete' when roots are null")
    void resolveLocalProofIncompleteRoots() {
        RuntimeBinding localBinding = primaryBinding("local", "GCP");
        when(bindingRepository.findLocalProof())
                .thenReturn(Optional.of(localBinding));

        ResolutionResult<RuntimeBinding> result = facade.resolveLocalProofBinding();

        assertInstanceOf(ResolutionResult.Unresolved.class, result);
        ResolutionResult.Unresolved<RuntimeBinding> unresolved =
                (ResolutionResult.Unresolved<RuntimeBinding>) result;
        assertEquals("local_roots_incomplete", unresolved.blockerCode());
        assertTrue(unresolved.message().contains("incomplete storage roots"));
    }

    @Test
    @DisplayName("resolveLocalProofBinding returns Resolved when roots are complete")
    void resolveLocalProofComplete() {
        RuntimeBinding localBinding = primaryBinding("local", "GCP");
        localBinding.setStorageRootFiles("gs://acme-files");
        localBinding.setStorageRootLake("gs://acme-lake");
        localBinding.setStorageRootOps("gs://acme-ops");
        when(bindingRepository.findLocalProof())
                .thenReturn(Optional.of(localBinding));

        ResolutionResult<RuntimeBinding> result = facade.resolveLocalProofBinding();

        assertInstanceOf(ResolutionResult.Resolved.class, result);
        RuntimeBinding resolved = ((ResolutionResult.Resolved<RuntimeBinding>) result).value();
        assertTrue(resolved.hasCompleteRoots());
    }

    @Test
    @DisplayName("DIAGNOSTIC rows are never returned as operational authority")
    void diagnosticRowsNeverReturnedAsOperational() {
        // Defence-in-depth check: even if a DIAGNOSTIC row passes the query
        // filter somehow, the facade must reject it as operational authority.
        RuntimeBinding diagnosticBinding = new RuntimeBinding();
        diagnosticBinding.setEnvironment("integration");
        diagnosticBinding.setBindingKind("GCP");
        diagnosticBinding.setSettingsRole("DIAGNOSTIC");
        diagnosticBinding.setRecordState("ACTIVE");
        when(bindingRepository.findActivePrimary("integration"))
                .thenReturn(Optional.of(diagnosticBinding));

        ResolutionResult<RuntimeBinding> result = facade.resolvePrimaryBinding("integration");

        assertInstanceOf(ResolutionResult.Unresolved.class, result);
        ResolutionResult.Unresolved<RuntimeBinding> unresolved =
                (ResolutionResult.Unresolved<RuntimeBinding>) result;
        assertEquals("binding_missing", unresolved.blockerCode());
    }

    @Test
    @DisplayName("resolveStorageRoots returns all three roots from PRIMARY binding")
    void resolveStorageRootsFromPrimary() {
        RuntimeBinding binding = primaryBinding("integration", "GCP");
        binding.setStorageRootFiles("gs://acme-files");
        binding.setStorageRootLake("gs://acme-lake");
        binding.setStorageRootOps("gs://acme-ops");

        StorageRoots roots = facade.resolveStorageRoots(binding);

        assertEquals("gs://acme-files", roots.files());
        assertEquals("gs://acme-lake", roots.lake());
        assertEquals("gs://acme-ops", roots.ops());
    }

    @Test
    @DisplayName("resolveLocalProofBinding returns Unresolved with 'binding_missing' when no local proof exists")
    void resolveLocalProofMissing() {
        when(bindingRepository.findLocalProof())
                .thenReturn(Optional.empty());

        ResolutionResult<RuntimeBinding> result = facade.resolveLocalProofBinding();

        assertInstanceOf(ResolutionResult.Unresolved.class, result);
        ResolutionResult.Unresolved<RuntimeBinding> unresolved =
                (ResolutionResult.Unresolved<RuntimeBinding>) result;
        assertEquals("binding_missing", unresolved.blockerCode());
    }

    @Test
    @DisplayName("resolveStorageRoots returns nulls when roots are not populated")
    void resolveStorageRootsNullWhenEmpty() {
        RuntimeBinding binding = primaryBinding("dev", "DPC");
        // No storage roots set

        StorageRoots roots = facade.resolveStorageRoots(binding);

        assertNull(roots.files());
        assertNull(roots.lake());
        assertNull(roots.ops());
    }

    @Test
    @DisplayName("resolveStorageRoots(env) reads from global active PRIMARY binding")
    void resolveStorageRootsByEnvironment() {
        RuntimeBinding primary = primaryBinding("uat", "GCP");
        primary.setStorageRootFiles("gs://global-files");
        primary.setStorageRootLake("gs://global-lake");
        primary.setStorageRootOps("gs://global-ops");
        when(bindingRepository.findActivePrimary("uat"))
                .thenReturn(Optional.of(primary));

        ResolutionResult<StorageRoots> result = facade.resolveStorageRoots("uat");

        assertInstanceOf(ResolutionResult.Resolved.class, result);
        StorageRoots roots = ((ResolutionResult.Resolved<StorageRoots>) result).value();
        assertEquals("gs://global-files", roots.files());
        assertEquals("gs://global-lake", roots.lake());
        assertEquals("gs://global-ops", roots.ops());
    }

    // ---- helpers ----

    private static RuntimeBinding primaryBinding(String environment, String bindingKind) {
        RuntimeBinding b = new RuntimeBinding();
        b.setEnvironment(environment);
        b.setBindingKind(bindingKind);
        b.setSettingsRole("PRIMARY");
        b.setRecordState("ACTIVE");
        return b;
    }

    @SuppressWarnings("unused")
    private static void unusedAssertFalse() {
        // Reference import so static analysis doesn't strip it.
        assertFalse(false);
    }
}
