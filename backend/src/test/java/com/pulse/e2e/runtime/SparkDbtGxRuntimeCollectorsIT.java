package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.e2e.support.RepresentativePipelineFixtureFactory;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SparkDbtGxRuntimeCollectorsIT {

    @Autowired private DomainRepository domainRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private SystemOfRecordRepository systemOfRecordRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private CredentialProfileRepository credentialProfileRepository;
    @Autowired private CompositionService compositionService;
    @Autowired private CodeGenerationService codeGenerationService;
    @Autowired private GeneratedArtifactRepository generatedArtifactRepository;
    @Autowired private ObjectMapper objectMapper;

    @TempDir Path tempDir;

    @Test
    void canonicalRepresentativeScenarioMarksSparkAndDbtApplicableButGxNotApplicableWithoutDq() throws Exception {
        RepresentativePipelineFixtureFactory fixtureFactory = new RepresentativePipelineFixtureFactory(
                domainRepository,
                pipelineRepository,
                pipelineVersionRepository,
                blueprintRepository,
                systemOfRecordRepository,
                connectorDefinitionRepository,
                connectorInstanceRepository,
                credentialProfileRepository,
                compositionService
        );
        var fixture = fixtureFactory.create("tenant-runtime-collectors");

        GenerationRun run = codeGenerationService.generate(
                fixture.pipeline().getId(),
                fixture.version().getId(),
                fixture.tenantId(),
                "tester"
        );
        assertEquals("COMPLETED", run.getStatus());

        SparkDbtGxRuntimeCollectors collectors = new SparkDbtGxRuntimeCollectors(objectMapper);
        SparkDbtGxRuntimeCollectors.CollectorBundle bundle = collectors.collect(
                new SparkDbtGxRuntimeCollectors.CollectorRequest(
                        "loan-master-live-runtime",
                        run.getId(),
                        generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()),
                        List.of(),
                        tempDir.resolve("evidence")
                )
        );

        assertEquals("APPLICABLE", bundle.spark().applicability());
        assertEquals("MISSING", bundle.spark().verdict());
        assertEquals("APPLICABLE", bundle.dbt().applicability());
        assertEquals("MISSING", bundle.dbt().verdict());
        assertEquals("NOT_APPLICABLE", bundle.gx().applicability());
        assertEquals("NOT_APPLICABLE", bundle.gx().verdict());
        assertTrue(Files.exists(tempDir.resolve("evidence/spark-runtime-collection.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/dbt-runtime-collection.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/gx-runtime-collection.json")));
    }
}
