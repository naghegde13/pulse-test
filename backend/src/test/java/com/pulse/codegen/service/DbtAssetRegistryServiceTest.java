package com.pulse.codegen.service;

import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.repository.DbtAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbtAssetRegistryServiceTest {

    @Mock private DbtAssetRepository dbtAssetRepository;

    @InjectMocks
    private DbtAssetRegistryService service;

    @Test
    void findReuseCandidate_defaultsGenerateRequestsToReuseWrapper() {
        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setDomainId("domain-1");
        asset.setAssetName("employee_conformed");
        asset.setAssetType("model");
        asset.setBusinessConcept("employee");

        when(dbtAssetRepository.findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc("domain-1", "main"))
                .thenReturn(List.of(asset));

        var match = service.findReuseCandidate("domain-1", "employee", "model", null, null, null, "generate");

        assertTrue(match.isPresent());
        assertEquals("reuse_wrapper", match.get().emitStrategy());
        assertEquals("employee_conformed", match.get().asset().getAssetName());
        assertTrue(match.get().score() >= 8);
    }

    @Test
    void findReuseCandidate_prefersMatchingSchemaSignatureAndGrain() {
        DbtAsset weak = new DbtAsset();
        weak.setId("asset-1");
        weak.setDomainId("domain-1");
        weak.setAssetName("employee_basic");
        weak.setAssetType("model");
        weak.setBusinessConcept("employee");
        weak.setGrain("daily");

        DbtAsset strong = new DbtAsset();
        strong.setId("asset-2");
        strong.setDomainId("domain-1");
        strong.setAssetName("employee_conformed");
        strong.setAssetType("model");
        strong.setBusinessConcept("employee");
        strong.setGrain("employee");
        strong.setSchemaSignature("sig 123");

        when(dbtAssetRepository.findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc("domain-1", "main"))
                .thenReturn(List.of(weak, strong));

        var match = service.findReuseCandidate("domain-1", "employee", "model", "employee", null, "sig 123", null);

        assertTrue(match.isPresent());
        assertEquals("employee_conformed", match.get().asset().getAssetName());
        assertTrue(match.get().reasons().contains("Exact analytical grain match."));
        assertTrue(match.get().reasons().contains("Exact schema signature match."));
    }

    @Test
    void findReuseCandidate_honorsRequestedReferenceOnlyStrategyAndAccessLevel() {
        DbtAsset privateAsset = new DbtAsset();
        privateAsset.setId("asset-1");
        privateAsset.setDomainId("domain-1");
        privateAsset.setAssetName("employee_private");
        privateAsset.setAssetType("model");
        privateAsset.setBusinessConcept("employee");
        privateAsset.setAccessLevel("private");

        DbtAsset publicAsset = new DbtAsset();
        publicAsset.setId("asset-2");
        publicAsset.setDomainId("domain-1");
        publicAsset.setAssetName("employee_public");
        publicAsset.setAssetType("model");
        publicAsset.setBusinessConcept("employee");
        publicAsset.setAccessLevel("public");

        when(dbtAssetRepository.findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc("domain-1", "main"))
                .thenReturn(List.of(privateAsset, publicAsset));

        var match = service.findReuseCandidate(
                "domain-1", "employee", "model", null, "public", null, "reference_only");

        assertTrue(match.isPresent());
        assertEquals("employee_public", match.get().asset().getAssetName());
        assertEquals("reference_only", match.get().emitStrategy());
    }

    @Test
    void findReuseCandidate_usesSemanticMetadataWhenBusinessConceptIsNearMatch() {
        DbtAsset generic = new DbtAsset();
        generic.setId("asset-1");
        generic.setDomainId("domain-1");
        generic.setAssetName("daily_workforce_metrics");
        generic.setAssetType("model");
        generic.setBusinessConcept("workforce profile");
        generic.setSchemaSignature("employee id status effective date");
        generic.setMetadata(Map.of("semantic_terms", List.of("employee", "status", "workforce")));

        DbtAsset stronger = new DbtAsset();
        stronger.setId("asset-2");
        stronger.setDomainId("domain-1");
        stronger.setAssetName("employee_status_snapshot");
        stronger.setAssetType("model");
        stronger.setBusinessConcept("employee status");
        stronger.setGrain("employee");
        stronger.setSchemaSignature("employee id status effective date");
        stronger.setMetadata(Map.of(
                "semantic_terms", List.of("employee", "status", "history"),
                "contract_keys", List.of("employee_id", "effective_date")
        ));

        when(dbtAssetRepository.findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc("domain-1", "main"))
                .thenReturn(List.of(generic, stronger));

        var match = service.findReuseCandidate(
                "domain-1",
                "employee status",
                "model",
                "employee",
                null,
                "employee id status effective date",
                null,
                Map.of("contract_keys", List.of("employee_id", "effective_date")));

        assertTrue(match.isPresent());
        assertEquals("employee_status_snapshot", match.get().asset().getAssetName());
        assertTrue(match.get().score() >= 15);
        assertEquals("exact", match.get().compatibility().get("businessConcept"));
        assertTrue(match.get().reasons().stream().anyMatch(reason -> reason.contains("Semantic metadata")));
    }

    @Test
    void findReuseCandidate_downgradesReferenceOnlyWhenCompatibilityIsPartial() {
        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setDomainId("domain-1");
        asset.setAssetName("employee_status_daily");
        asset.setAssetType("model");
        asset.setBusinessConcept("employee");
        asset.setGrain("employee daily");
        asset.setSchemaSignature("employee id status");
        asset.setAccessLevel("public");

        when(dbtAssetRepository.findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc("domain-1", "main"))
                .thenReturn(List.of(asset));

        var match = service.findReuseCandidate(
                "domain-1",
                "employee",
                "model",
                "employee",
                "public",
                "employee id status last updated",
                "reference_only");

        assertTrue(match.isPresent());
        assertEquals("reuse_wrapper", match.get().emitStrategy());
        assertTrue(match.get().warnings().stream().anyMatch(warning -> warning.contains("downgraded")));
        assertEquals(Boolean.FALSE, match.get().compatibility().get("referenceSafe"));
    }
}
