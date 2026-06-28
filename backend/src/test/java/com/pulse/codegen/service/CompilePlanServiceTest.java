package com.pulse.codegen.service;

import com.pulse.blueprint.exception.BlueprintCompatReadOnlyException;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import com.pulse.codegen.model.DbtAsset;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.sor.repository.DomainRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompilePlanServiceTest {

    @Mock private BlueprintRepository blueprintRepository;
    @Mock private DeprecatedBlueprintCompatibilityService compat;
    @Mock private DbtAssetRegistryService dbtAssetRegistryService;
    @Mock private GitRepoRepository gitRepoRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private com.pulse.storage.contract.service.TableContractService tableContractService;
    @Mock private com.pulse.pipeline.service.OrchestrationNamespaceService orchestrationNamespaceService;

    @InjectMocks
    private CompilePlanService service;

    @Test
    void build_marksOrchestrationSensorsAsControlPlaneRuntimeOnly() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setName("Employee Pipeline");

        SubPipelineInstance sensor = new SubPipelineInstance();
        sensor.setId("inst-1");
        sensor.setName("Orders Ready");
        sensor.setBlueprintKey("DatabaseReadinessSensor");
        sensor.setParams(Map.of("sql", "SELECT 1"));

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("DatabaseReadinessSensor");
        blueprint.setCategory(BlueprintCategory.ORCHESTRATION);
        blueprint.setCompositionRole("orchestration_sensor");
        blueprint.setEmitStrategy("runtime_only");
        blueprint.setValidLayers(List.of("bronze", "silver", "gold"));
        when(blueprintRepository.findByBlueprintKey("DatabaseReadinessSensor"))
                .thenReturn(Optional.of(blueprint));

        var snapshot = service.build(pipeline, "version-1", List.of(sensor), List.of());

        assertNotNull(snapshot);
        assertEquals("servicing/pipelines/employee_pipeline", snapshot.namespace());
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) snapshot.nodes().get(0);
        assertEquals("control_plane", node.get("resolvedLayer"));
        assertEquals("airflow", node.get("executionContext"));
        assertEquals("runtime_only", node.get("emitStrategy"));
        assertEquals("orchestration_sensor", node.get("compositionRole"));
    }

    @Test
    void build_goldLayer_resolvesDbtSpark() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Analytics");
        pipeline.setName("Revenue Rollups");

        SubPipelineInstance fact = new SubPipelineInstance();
        fact.setId("inst-fact");
        fact.setName("Daily Revenue");
        fact.setBlueprintKey("FactBuild");
        fact.setParams(Map.of());

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("FactBuild");
        blueprint.setCategory(BlueprintCategory.MODELING);
        blueprint.setValidLayers(List.of("gold"));
        when(blueprintRepository.findByBlueprintKey("FactBuild"))
                .thenReturn(Optional.of(blueprint));

        var snapshot = service.build(pipeline, "version-1", List.of(fact), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) snapshot.nodes().get(0);
        assertEquals("gold", node.get("resolvedLayer"));
        assertEquals("dbt_spark", node.get("executionContext"));
    }

    @Test
    void build_snapshotModelChecksRegistryForReusableDbtModelNotSnapshot() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setName("Snapshot Pipeline");

        SubPipelineInstance snapshotModel = new SubPipelineInstance();
        snapshotModel.setId("inst-snapshot");
        snapshotModel.setName("Subscription Snapshot");
        snapshotModel.setBlueprintKey("SnapshotModel");
        snapshotModel.setParams(Map.of(
                "business_concept", "subscription_snapshot",
                "grain", "subscription_id,ds",
                "schema_signature", "subscription_snapshot_v1"
        ));

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("SnapshotModel");
        blueprint.setCategory(BlueprintCategory.MODELING);
        blueprint.setSupportsReuse(true);
        blueprint.setValidLayers(List.of("gold"));
        when(blueprintRepository.findByBlueprintKey("SnapshotModel"))
                .thenReturn(Optional.of(blueprint));

        DbtAsset asset = new DbtAsset();
        asset.setId("asset-snapshot");
        asset.setDomainId("domain-1");
        asset.setAssetName("snp_subscription_snapshot");
        asset.setAssetType("model");
        asset.setPath("models/marts/servicing/snp_subscription_snapshot.sql");
        var reuseMatch = new DbtAssetRegistryService.ReuseMatch(
                asset,
                "reuse_wrapper",
                18,
                List.of("Exact business concept match.", "Exact grain match."),
                List.of("Wrap to preserve point-in-time snapshot ownership."),
                Map.of("referenceSafe", true)
        );
        when(dbtAssetRegistryService.findReuseCandidate(
                "domain-1", "subscription_snapshot", "model", "subscription_id,ds", "", "subscription_snapshot_v1",
                null, snapshotModel.getParams(), "main"))
                .thenReturn(Optional.of(reuseMatch));
        when(dbtAssetRegistryService.toApiPayload(asset))
                .thenReturn(Map.of("assetName", "snp_subscription_snapshot"));
        when(dbtAssetRegistryService.toDecisionPayload(reuseMatch))
                .thenReturn(Map.of("emitStrategy", "reuse_wrapper", "score", 18));

        var snapshot = service.build(pipeline, "version-1", List.of(snapshotModel), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) snapshot.nodes().get(0);
        assertEquals("gold", node.get("resolvedLayer"));
        assertEquals("reuse_wrapper", node.get("emitStrategy"));
        assertEquals("snp_subscription_snapshot", ((Map<?, ?>) node.get("reuseAsset")).get("assetName"));
    }

    @Test
    void build_setsReuseWrapperWhenRegistryFindsMatchingAsset() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setName("Employee Pipeline");

        SubPipelineInstance model = new SubPipelineInstance();
        model.setId("inst-1");
        model.setName("Current Employees");
        model.setBlueprintKey("WideDenormalizedMart");
        model.setParams(Map.of(
                "business_concept", "employee",
                "contract_keys", List.of("employee_id")
        ));

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("WideDenormalizedMart");
        blueprint.setCategory(BlueprintCategory.MODELING);
        blueprint.setSupportsReuse(true);
        blueprint.setValidLayers(List.of("gold"));
        when(blueprintRepository.findByBlueprintKey("WideDenormalizedMart"))
                .thenReturn(Optional.of(blueprint));

        DbtAsset asset = new DbtAsset();
        asset.setId("asset-1");
        asset.setDomainId("domain-1");
        asset.setAssetName("employee_conformed");
        asset.setAssetType("model");
        asset.setPath("models/shared/employee_conformed.sql");
        var reuseMatch = new DbtAssetRegistryService.ReuseMatch(
                asset,
                "reuse_wrapper",
                16,
                List.of("Exact business concept match.", "Exact schema signature match."),
                List.of("Wrap to preserve local ownership semantics."),
                Map.of("referenceSafe", false)
        );
        when(dbtAssetRegistryService.findReuseCandidate(
                "domain-1", "employee", "model", "", "", "", null, model.getParams(), "main"))
                .thenReturn(Optional.of(reuseMatch));
        when(dbtAssetRegistryService.toApiPayload(asset))
                .thenReturn(Map.of("assetName", "employee_conformed"));
        when(dbtAssetRegistryService.toDecisionPayload(reuseMatch))
                .thenReturn(Map.of(
                        "emitStrategy", "reuse_wrapper",
                        "score", 16,
                        "reasons", List.of("Exact business concept match."),
                        "warnings", List.of("Wrap to preserve local ownership semantics."),
                        "compatibility", Map.of("referenceSafe", false)
                ));

        var snapshot = service.build(pipeline, "version-1", List.of(model), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) snapshot.nodes().get(0);
        assertEquals("reuse_wrapper", node.get("emitStrategy"));
        assertEquals("employee_conformed", ((Map<?, ?>) node.get("reuseAsset")).get("assetName"));
        assertEquals(16, ((Map<?, ?>) node.get("reuseDecision")).get("score"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) ((Map<?, ?>) node.get("reuseDecision")).get("compatibility")).get("referenceSafe"));
    }

    @Test
    void build_factBuildChecksRegistryForReusableDbtModel() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");
        pipeline.setName("Loan Mart");

        SubPipelineInstance fact = new SubPipelineInstance();
        fact.setId("inst-fact");
        fact.setName("Loan Fact");
        fact.setBlueprintKey("FactBuild");
        fact.setParams(Map.of(
                "business_concept", "loan_fact",
                "grain", "loan_id",
                "access_level", "domain",
                "schema_signature", "loan_fact_v1"
        ));

        Blueprint blueprint = new Blueprint();
        blueprint.setBlueprintKey("FactBuild");
        blueprint.setCategory(BlueprintCategory.MODELING);
        blueprint.setSupportsReuse(true);
        blueprint.setValidLayers(List.of("gold"));
        when(blueprintRepository.findByBlueprintKey("FactBuild"))
                .thenReturn(Optional.of(blueprint));

        DbtAsset asset = new DbtAsset();
        asset.setId("asset-fact");
        asset.setDomainId("domain-1");
        asset.setAssetName("fct_loan_master");
        asset.setAssetType("model");
        asset.setPath("models/marts/servicing/fct_loan_master.sql");
        var reuseMatch = new DbtAssetRegistryService.ReuseMatch(
                asset,
                "reuse_wrapper",
                18,
                List.of("Exact business concept match.", "Exact grain match."),
                List.of("Wrap to preserve pipeline-local semantics."),
                Map.of("referenceSafe", true)
        );
        when(dbtAssetRegistryService.findReuseCandidate(
                "domain-1", "loan_fact", "model", "loan_id", "domain", "loan_fact_v1", null, fact.getParams(), "main"))
                .thenReturn(Optional.of(reuseMatch));
        when(dbtAssetRegistryService.toApiPayload(asset))
                .thenReturn(Map.of("assetName", "fct_loan_master"));
        when(dbtAssetRegistryService.toDecisionPayload(reuseMatch))
                .thenReturn(Map.of(
                        "emitStrategy", "reuse_wrapper",
                        "score", 18,
                        "reasons", List.of("Exact business concept match."),
                        "warnings", List.of("Wrap to preserve pipeline-local semantics."),
                        "compatibility", Map.of("referenceSafe", true)
                ));

        var snapshot = service.build(pipeline, "version-1", List.of(fact), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) snapshot.nodes().get(0);
        assertEquals("reuse_wrapper", node.get("emitStrategy"));
        assertEquals("fct_loan_master", ((Map<?, ?>) node.get("reuseAsset")).get("assetName"));
        assertEquals(18, ((Map<?, ?>) node.get("reuseDecision")).get("score"));
    }

    // -----------------------------------------------------------------------
    //  PKT-0006: deprecated/deferred blueprint rejection
    // -----------------------------------------------------------------------

    @Test
    void build_rejectsDeprecatedBlueprint() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Analytics");
        pipeline.setName("Stale Pipeline");

        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setId("inst-1");
        instance.setName("Old Reconciliation");
        instance.setBlueprintKey("Reconciliation");
        instance.setParams(Map.of());

        Blueprint deprecated = new Blueprint();
        deprecated.setBlueprintKey("Reconciliation");
        deprecated.setCategory(BlueprintCategory.DATA_QUALITY);
        deprecated.setStatus("deprecated");
        deprecated.setDeferred(true);
        deprecated.setReplacementBlueprintKey("DQValidator");
        when(blueprintRepository.findByBlueprintKey("Reconciliation"))
                .thenReturn(Optional.of(deprecated));
        when(compat.isCompatReadOnly(deprecated)).thenReturn(true);

        assertThrows(BlueprintCompatReadOnlyException.class,
                () -> service.build(pipeline, "version-1", List.of(instance), List.of()));
    }

    @Test
    void build_rejectsDeferredBlueprint() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Analytics");
        pipeline.setName("Deferred Pipeline");

        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setId("inst-1");
        instance.setName("Derive Step");
        instance.setBlueprintKey("Derive");
        instance.setParams(Map.of());

        Blueprint deferred = new Blueprint();
        deferred.setBlueprintKey("Derive");
        deferred.setCategory(BlueprintCategory.TRANSFORM);
        deferred.setStatus("active");
        deferred.setDeferred(true);
        when(blueprintRepository.findByBlueprintKey("Derive"))
                .thenReturn(Optional.of(deferred));
        when(compat.isCompatReadOnly(deferred)).thenReturn(true);

        assertThrows(BlueprintCompatReadOnlyException.class,
                () -> service.build(pipeline, "version-1", List.of(instance), List.of()));
    }
}
