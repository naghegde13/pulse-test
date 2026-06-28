package com.pulse.e2e.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.deploy.model.Package;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.SchemaPropagationService.PropagationSummary;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Thin typed client around the public REST surface used by the E2E scenario harness.
 * Tests that use this client are intentionally API-driven and avoid direct chat/service shortcuts.
 */
public class ApiScenarioClient {

    private static final TypeReference<List<Blueprint>> BLUEPRINT_LIST = new TypeReference<>() {};
    private static final TypeReference<List<GeneratedArtifact>> GENERATED_ARTIFACT_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiScenarioClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public List<Blueprint> listActiveBlueprints() throws Exception {
        return readList(
                mockMvc.perform(get("/api/v1/blueprints"))
                        .andExpect(status().isOk())
                        .andReturn(),
                BLUEPRINT_LIST);
    }

    public Map<String, Object> createTenant(String id, String name, String slug) throws Exception {
        Map<String, Object> request = Map.of(
                "id", id,
                "name", name,
                "slug", slug);
        return readMap(
                mockMvc.perform(post("/api/v1/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    public Domain createDomain(String tenantId, String name, String description) throws Exception {
        Map<String, Object> request = Map.of(
                "name", name,
                "description", description);
        return readBody(
                mockMvc.perform(post("/api/v1/tenants/{tenantId}/domains", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isOk())
                        .andReturn(),
                Domain.class);
    }

    public SystemOfRecord createSor(String tenantId,
                                    String name,
                                    String description,
                                    String domainId,
                                    Map<String, Object> metadata) throws Exception {
        Map<String, Object> request = Map.of(
                "name", name,
                "description", description,
                "domainId", domainId,
                "metadata", metadata);
        return readBody(
                mockMvc.perform(post("/api/v1/tenants/{tenantId}/sors", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isOk())
                        .andReturn(),
                SystemOfRecord.class);
    }

    public ConnectorInstance createConnector(String sorId,
                                             String connectorDefinitionId,
                                             String name,
                                             String description,
                                             Map<String, Object> configTemplate) throws Exception {
        Map<String, Object> request = Map.of(
                "connectorDefinitionId", connectorDefinitionId,
                "name", name,
                "description", description,
                "configTemplate", configTemplate);
        return readBody(
                mockMvc.perform(post("/api/v1/sors/{sorId}/connectors", sorId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isOk())
                        .andReturn(),
                ConnectorInstance.class);
    }

    public CredentialProfile upsertCredential(String connectorInstanceId,
                                              String environment,
                                              Map<String, Object> requestBody) throws Exception {
        return readBody(
                mockMvc.perform(put("/api/v1/connector-instances/{ciId}/credentials/{env}", connectorInstanceId, environment)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(requestBody)))
                        .andExpect(status().isOk())
                        .andReturn(),
                CredentialProfile.class);
    }

    public Dataset createDataset(String connectorInstanceId,
                                 Map<String, Object> requestBody) throws Exception {
        return readBody(
                mockMvc.perform(post("/api/v1/connector-instances/{ciId}/datasets", connectorInstanceId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(requestBody)))
                        .andExpect(status().isOk())
                        .andReturn(),
                Dataset.class);
    }

    public Pipeline createPipeline(String tenantId,
                                   String name,
                                   String description,
                                   String domainId) throws Exception {
        return createPipeline(tenantId, name, description, domainId, "DPC");
    }

    public Pipeline createPipeline(String tenantId,
                                   String name,
                                   String description,
                                   String domainId,
                                   String defaultStorageBackend) throws Exception {
        Map<String, Object> request = Map.of(
                "name", name,
                "description", description,
                "domainId", domainId,
                "defaultStorageBackend", defaultStorageBackend == null ? "DPC" : defaultStorageBackend);
        MvcResult result = mockMvc.perform(post("/api/v1/tenants/{tenantId}/pipelines", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andReturn();
        assertEquals(201, result.getResponse().getStatus(),
                "createPipeline failed: " + result.getResponse().getContentAsString());
        return readBody(result, Pipeline.class);
    }

    public PipelineVersion getVersion(String tenantId, String pipelineId, String versionId) throws Exception {
        return readBody(
                mockMvc.perform(get("/api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}",
                                tenantId, pipelineId, versionId))
                        .andExpect(status().isOk())
                        .andReturn(),
                PipelineVersion.class);
    }

    public SubPipelineInstance addInstance(String versionId,
                                           String pipelineId,
                                           String blueprintKey,
                                           String name,
                                           Map<String, Object> params) throws Exception {
        Map<String, Object> request = Map.of(
                "pipelineId", pipelineId,
                "blueprintKey", blueprintKey,
                "name", name,
                "params", params);
        MvcResult result = mockMvc.perform(post("/api/v1/versions/{versionId}/composition/instances", versionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "addInstance failed: " + result.getResponse().getContentAsString());
        Map<String, Object> body = readMap(result);
        Object instance = body.get("instance");
        return objectMapper.convertValue(instance == null ? body : instance, SubPipelineInstance.class);
    }

    public void wire(String versionId,
                     String sourceInstanceId,
                     String sourcePortName,
                     String targetInstanceId,
                     String targetPortName) throws Exception {
        Map<String, Object> request = Map.of(
                "sourceInstanceId", sourceInstanceId,
                "sourcePortName", sourcePortName,
                "targetInstanceId", targetInstanceId,
                "targetPortName", targetPortName);
        mockMvc.perform(post("/api/v1/versions/{versionId}/composition/wirings", versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    public PropagationSummary recomputeSchema(String versionId) throws Exception {
        return readBody(
                mockMvc.perform(post("/api/v1/versions/{versionId}/schema/recompute", versionId))
                        .andExpect(status().isOk())
                        .andReturn(),
                PropagationSummary.class);
    }

    public GenerationRun generate(String versionId,
                                  String pipelineId,
                                  String tenantId,
                                  String userId) throws Exception {
        Map<String, Object> request = Map.of(
                "pipelineId", pipelineId,
                "tenantId", tenantId,
                "userId", userId);
        return readBody(
                mockMvc.perform(post("/api/v1/versions/{versionId}/generate", versionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isOk())
                        .andReturn(),
                GenerationRun.class);
    }

    public List<GeneratedArtifact> getArtifacts(String generationRunId) throws Exception {
        return readList(
                mockMvc.perform(get("/api/v1/generations/{runId}/artifacts", generationRunId))
                        .andExpect(status().isOk())
                        .andReturn(),
                GENERATED_ARTIFACT_LIST);
    }

    public Package buildPackage(String versionId,
                                String pipelineId,
                                String tenantId,
                                String userId) throws Exception {
        Map<String, Object> request = Map.of(
                "pipelineId", pipelineId,
                "tenantId", tenantId,
                "userId", userId);
        return readBody(
                mockMvc.perform(post("/api/v1/versions/{versionId}/packages", versionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                        .andExpect(status().isOk())
                        .andReturn(),
                Package.class);
    }

    private <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }

    private <T> T readList(MvcResult result, TypeReference<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }

    private Map<String, Object> readMap(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), MAP_TYPE);
    }
}
