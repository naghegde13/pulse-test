package com.pulse.pipeline.service;

import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.ReleaseStage;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceSqlSchemaResolverTest {

    @Mock private ConnectorInstanceRepository connectorInstanceRepo;
    @Mock private ConnectorDefinitionRepository connectorDefinitionRepo;
    @Mock private CredentialProfileRepository credentialProfileRepo;

    @Test
    void resolveSourceColumnsLowersMnemonicsBeforePrepare() {
        SourceSqlSchemaResolver resolver =
                new SourceSqlSchemaResolver(connectorInstanceRepo, connectorDefinitionRepo, credentialProfileRepo);
        ConnectorInstance connector = connector("ci-source", "01JCONN0SRC0POSTGRES00001", "Loan Warehouse Prod");
        CredentialProfile credential = credential("ci-source", Map.of(
                "jdbc_url", "jdbc:h2:mem:source_prepare;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "username", "sa",
                "password", ""));

        when(connectorInstanceRepo.findById("ci-source")).thenReturn(Optional.of(connector));
        when(connectorDefinitionRepo.findById("01JCONN0SRC0POSTGRES00001")).thenReturn(Optional.empty());
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-source", "dev"))
                .thenReturn(Optional.of(credential));

        SubPipelineInstance instance = new SubPipelineInstance();
        instance.setParams(Map.of(
                "connector_instance_id", "ci-source",
                "source_query", "SELECT 42 AS loan_id, [[ PBD ]] AS as_of_date"));

        var columns = resolver.resolveSourceColumns(instance).orElseThrow();

        assertEquals("loan_id", String.valueOf(columns.get(0).get("name")).toLowerCase());
        assertEquals("as_of_date", String.valueOf(columns.get(1).get("name")).toLowerCase());
        assertEquals("date", columns.get(1).get("type"));
    }

    @Test
    void jdbcUrlUsesConnectorDefinitionMetadataNotInstanceName() {
        SourceSqlSchemaResolver resolver =
                new SourceSqlSchemaResolver(connectorInstanceRepo, connectorDefinitionRepo, credentialProfileRepo);
        ConnectorInstance connector = connector("ci-source", "01JCONN0SRC0POSTGRES00001", "Loan Warehouse Prod");
        ConnectorDefinition definition = definition("PostgreSQL", "airbyte/source-postgres");

        String jdbcUrl = resolver.jdbcUrl(connector, definition, Map.of(
                "host", "pg-host",
                "port", "15432",
                "database", "lending"));

        assertEquals("jdbc:postgresql://pg-host:15432/lending", jdbcUrl);
    }

    @Test
    void lowerDesignTimeMnemonicsRejectsUnknownTokens() {
        SourceSqlSchemaResolver resolver =
                new SourceSqlSchemaResolver(connectorInstanceRepo, connectorDefinitionRepo, credentialProfileRepo);

        assertTrue(resolver.lowerDesignTimeMnemonics("SELECT [[ PBD ]]").contains("DATE '2026-01-01'"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> resolver.lowerDesignTimeMnemonics("SELECT [[ NOPE ]]"));
    }

    private ConnectorInstance connector(String id, String definitionId, String name) {
        ConnectorInstance connector = new ConnectorInstance();
        connector.setId(id);
        connector.setConnectorDefinitionId(definitionId);
        connector.setSorId("sor-1");
        connector.setName(name);
        return connector;
    }

    private ConnectorDefinition definition(String name, String dockerRepository) {
        ConnectorDefinition definition = new ConnectorDefinition();
        definition.setName(name);
        definition.setConnectorType(ConnectorType.SOURCE);
        definition.setDockerRepository(dockerRepository);
        definition.setDockerImageTag("1.0.0");
        definition.setConnectionSpec(Map.of());
        definition.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        return definition;
    }

    private CredentialProfile credential(String connectorInstanceId, Map<String, Object> metadata) {
        CredentialProfile credential = new CredentialProfile();
        credential.setConnectorInstanceId(connectorInstanceId);
        credential.setEnvironment("dev");
        credential.setConnectionMetadata(metadata);
        return credential;
    }
}
