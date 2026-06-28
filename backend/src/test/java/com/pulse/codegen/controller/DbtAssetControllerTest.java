package com.pulse.codegen.controller;

import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.service.DbtAssetRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbtAssetControllerTest {

    @Mock private DbtAssetRegistryService dbtAssetRegistryService;

    @InjectMocks
    private DbtAssetController controller;

    @Test
    void listAssets_returnsRegistryPayload() {
        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setAssetName("employee_conformed");
        when(dbtAssetRegistryService.listDomainAssets("domain-1")).thenReturn(List.of(asset));
        when(dbtAssetRegistryService.toApiPayload(asset)).thenReturn(Map.of("assetName", "employee_conformed"));

        ResponseEntity<List<Map<String, Object>>> response = controller.listAssets("domain-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("employee_conformed", response.getBody().get(0).get("assetName"));
    }

    @Test
    void findReuseCandidate_returnsReuseWrapperWhenMatchExists() {
        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        var match = new DbtAssetRegistryService.ReuseMatch(
                asset,
                "reuse_wrapper",
                14,
                List.of("Exact business concept match."),
                List.of(),
                Map.of("referenceSafe", false)
        );
        when(dbtAssetRegistryService.findReuseCandidate("domain-1", "employee", "model", null, null, null, null))
                .thenReturn(Optional.of(match));
        when(dbtAssetRegistryService.toApiPayload(asset)).thenReturn(Map.of("assetName", "employee_conformed"));
        when(dbtAssetRegistryService.toDecisionPayload(match)).thenReturn(Map.of(
                "emitStrategy", "reuse_wrapper",
                "score", 14,
                "reasons", List.of("Exact business concept match."),
                "warnings", List.of(),
                "compatibility", Map.of("referenceSafe", false)
        ));

        ResponseEntity<Map<String, Object>> response = controller.findReuseCandidate(
                "domain-1", "employee", "model", null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("reuse_wrapper", response.getBody().get("emitStrategy"));
        assertNotNull(response.getBody().get("asset"));
    }

    @Test
    void findReuseCandidate_returnsGenerateDefaultsWhenNoMatchExists() {
        when(dbtAssetRegistryService.findReuseCandidate("domain-1", "employee", "model", null, null, null, null))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.findReuseCandidate(
                "domain-1", "employee", "model", null, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("generate", response.getBody().get("emitStrategy"));
        assertEquals(Map.of(), response.getBody().get("asset"));
    }

    @Test
    void refreshAssets_returnsSavedRegistryPayload() {
        var input = new DbtAssetRegistryService.ManifestAssetInput(
                "employee_conformed",
                "model",
                "models/shared/employee_conformed.sql",
                List.of("shared"),
                "finance",
                "public",
                "employee",
                "employee",
                "sig-123",
                "Employee model",
                Map.of()
        );
        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setAssetName("employee_conformed");

        when(dbtAssetRegistryService.refreshFromManifest("domain-1", "domain_dbt", List.of(input)))
                .thenReturn(List.of(asset));
        when(dbtAssetRegistryService.toApiPayload(asset)).thenReturn(Map.of("assetName", "employee_conformed"));

        ResponseEntity<List<Map<String, Object>>> response = controller.refreshAssets(
                "domain-1",
                new DbtAssetController.RefreshDbtAssetsRequest("domain_dbt", List.of(input))
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("employee_conformed", response.getBody().get(0).get("assetName"));
    }
}
