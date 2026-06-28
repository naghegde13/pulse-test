package com.pulse.e2e.support;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.ReleaseStage;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;

import java.util.List;
import java.util.Map;

public class RepresentativePipelineFixtureFactory {

    private final DomainRepository domainRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final BlueprintRepository blueprintRepository;
    private final SystemOfRecordRepository systemOfRecordRepository;
    private final ConnectorDefinitionRepository connectorDefinitionRepository;
    private final ConnectorInstanceRepository connectorInstanceRepository;
    private final CredentialProfileRepository credentialProfileRepository;
    private final CompositionService compositionService;

    public RepresentativePipelineFixtureFactory(DomainRepository domainRepository,
                                                PipelineRepository pipelineRepository,
                                                PipelineVersionRepository pipelineVersionRepository,
                                                BlueprintRepository blueprintRepository,
                                                SystemOfRecordRepository systemOfRecordRepository,
                                                ConnectorDefinitionRepository connectorDefinitionRepository,
                                                ConnectorInstanceRepository connectorInstanceRepository,
                                                CredentialProfileRepository credentialProfileRepository,
                                                CompositionService compositionService) {
        this.domainRepository = domainRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.blueprintRepository = blueprintRepository;
        this.systemOfRecordRepository = systemOfRecordRepository;
        this.connectorDefinitionRepository = connectorDefinitionRepository;
        this.connectorInstanceRepository = connectorInstanceRepository;
        this.credentialProfileRepository = credentialProfileRepository;
        this.compositionService = compositionService;
    }

    public PipelineFixture create(String tenantId) {
        Domain domain = new Domain();
        domain.setId("domain-proof");
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-proof");
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName("Representative Runtime Bridge Proof");
        pipeline.setCreatedBy("tester");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-proof");
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("tester");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        blueprintRepository.save(fileIngestionBlueprint());
        blueprintRepository.save(genericFilterBlueprint());
        blueprintRepository.save(lakeWriterBlueprint());

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-proof");
        sor.setTenantId(tenantId);
        sor.setName("Loan Source");
        sor.setDescription("Representative source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("tester");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-source");
        sourceDef.setName("S3-compatible Object Storage");
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-s3");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("full_refresh"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-dest");
        destDef.setName("Delta Lake");
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-delta-lake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of());
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-source");
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("Loan Drops");
        source.setConfigTemplate(Map.of(
                "bucket", "loan-source-bucket",
                "path_prefix", "loan_master/",
                "file_format", "csv",
                "header", "true",
                "infer_schema", "true"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-dest");
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("Delta Lakehouse");
        destination.setConfigTemplate(Map.of(
                "lake_format", "delta",
                "write_mode", "overwrite"
        ));
        connectorInstanceRepository.save(destination);

        CredentialProfile sourceCreds = new CredentialProfile();
        sourceCreds.setId("cred-source");
        sourceCreds.setConnectorInstanceId(source.getId());
        sourceCreds.setEnvironment("DEV");
        sourceCreds.setConnectionConfig(Map.of(
                "aws_access_key_id", "vault://pulse/dev/aws/access_key",
                "aws_secret_access_key", "vault://pulse/dev/aws/secret_key",
                "region", "us-east-1"
        ));
        sourceCreds.setStatus(CredentialStatus.VALID);
        credentialProfileRepository.save(sourceCreds);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest Loan Files",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName(),
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true"
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Filter Current Loans",
                Map.of("filter_mode", "sql", "raw_sql", "loan_status = 'Current'")
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "LakeWriter",
                "Write Current Loans To Lake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        // ARCH-010: lake_layer + lake_format must be set together
                        // when either is present. The legacy fixture used
                        // params-only lake_format=delta with no layer; that was
                        // tolerated only because no canonical validator ran.
                        "lake_layer", "silver",
                        "lake_format", "delta",
                        "write_mode", "overwrite"
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        return new PipelineFixture(tenantId, domain, pipeline, version);
    }

    private Blueprint fileIngestionBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-file");
        bp.setBlueprintKey("FileIngestion");
        bp.setName("File Ingestion");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Ingest files from storage");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "raw_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("bronze"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericFilterBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-filter");
        bp.setBlueprintKey("GenericFilter");
        bp.setName("Generic Filter");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Filter rows with SQL predicate");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "filtered_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint lakeWriterBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-sink");
        bp.setBlueprintKey("LakeWriter");
        bp.setName("Lake Writer");
        bp.setCategory(BlueprintCategory.DESTINATION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Write rows into a local Delta lake target");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of());
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    public record PipelineFixture(String tenantId, Domain domain, Pipeline pipeline, PipelineVersion version) {
    }
}
