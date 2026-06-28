package com.pulse.chat.plan;

import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.deploy.projection.service.RuntimeProjectionService.ProjectionDriftResult;
import com.pulse.storage.contract.service.StorageAuthorityFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proof matrix for {@link ContractImpactDerivation}. Pins the
 * NONE / TABLE_CONTRACT_STALE / RUNTIME_PROJECTION_STALE /
 * READINESS_RECHECK_REQUIRED transitions against the upstream readiness +
 * projection-drift APIs.
 */
@ExtendWith(MockitoExtension.class)
class ContractImpactDerivationTest {

    @Mock private StorageAuthorityFacade storageAuthorityFacade;
    @Mock private RuntimeProjectionService runtimeProjectionService;

    private ContractImpactDerivation derivation;

    @BeforeEach
    void setUp() {
        derivation = new ContractImpactDerivation(storageAuthorityFacade, runtimeProjectionService);
    }

    @Test
    void allReady_returnsNone() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of("ready", true, "blockers", List.of()));
        RuntimeProjection proj = new RuntimeProjection();
        proj.setId("proj-1");
        when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.of(proj));
        when(runtimeProjectionService.checkDrift("proj-1"))
                .thenReturn(new ProjectionDriftResult(false, "abc", "abc"));

        assertEquals(ContractImpactCode.NONE,
                derivation.derive("ver-1", "pkg-1", "tgt-1", "dev"));
    }

    @Test
    void contractReadinessNotReady_withBlockers_returnsTableContractStale() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of(
                        "ready", false,
                        "blockers", List.of(Map.of("code", "no_active_contracts", "message", "x"))));

        // Even though projection lookup succeeds, table-contract-stale wins by priority.
        lenient().when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.empty());

        assertEquals(ContractImpactCode.TABLE_CONTRACT_STALE,
                derivation.derive("ver-1", "pkg-1", "tgt-1", "dev"));
    }

    @Test
    void readyContractsButNoProjection_returnsRuntimeProjectionStale() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of("ready", true, "blockers", List.of()));
        when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.empty());

        assertEquals(ContractImpactCode.RUNTIME_PROJECTION_STALE,
                derivation.derive("ver-1", "pkg-1", "tgt-1", "dev"));
    }

    @Test
    void readyContractsButProjectionDrifted_returnsRuntimeProjectionStale() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of("ready", true, "blockers", List.of()));
        RuntimeProjection proj = new RuntimeProjection();
        proj.setId("proj-1");
        when(runtimeProjectionService.getActiveProjection("pkg-1", "tgt-1", "dev"))
                .thenReturn(Optional.of(proj));
        when(runtimeProjectionService.checkDrift("proj-1"))
                .thenReturn(new ProjectionDriftResult(true, "old", "new"));

        assertEquals(ContractImpactCode.RUNTIME_PROJECTION_STALE,
                derivation.derive("ver-1", "pkg-1", "tgt-1", "dev"));
    }

    @Test
    void readinessFailedWithoutBlockers_returnsRecheck() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of("ready", false, "blockers", List.of()));

        assertEquals(ContractImpactCode.READINESS_RECHECK_REQUIRED,
                derivation.derive("ver-1", null, null, "dev"));
    }

    @Test
    void facadeThrows_returnsRecheckConservatively() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenThrow(new RuntimeException("boom"));

        assertEquals(ContractImpactCode.READINESS_RECHECK_REQUIRED,
                derivation.derive("ver-1", null, null, "dev"));
    }

    @Test
    void missingPackageOrTarget_skipsProjectionCheck() {
        when(storageAuthorityFacade.getContractReadiness("ver-1"))
                .thenReturn(Map.of("ready", true, "blockers", List.of()));

        assertEquals(ContractImpactCode.NONE,
                derivation.derive("ver-1", null, null, "dev"));
        assertEquals(ContractImpactCode.NONE,
                derivation.derive("ver-1", "pkg-1", null, "dev"));
        assertEquals(ContractImpactCode.NONE,
                derivation.derive("ver-1", "pkg-1", "tgt-1", null));
    }

    @Test
    void nullVersionId_returnsNone() {
        // Defensive: no versionId means we cannot evaluate contracts; skip.
        assertEquals(ContractImpactCode.NONE,
                derivation.derive(null, null, null, null));
    }
}
