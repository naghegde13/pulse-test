package com.pulse.runtime.service;

import com.pulse.runtime.config.RuntimeAuthorityProperties;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.model.SecretAuthorityKind;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@EnableConfigurationProperties(RuntimeAuthorityProperties.class)
public class RuntimeAuthorityService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAuthorityService.class);

    private final RuntimeAuthorityProperties props;
    private volatile RuntimeAuthority authority;

    public RuntimeAuthorityService(RuntimeAuthorityProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void initialize() {
        RuntimePersona persona = resolvePersona();
        RuntimeAuthority resolved = buildAuthority(persona);
        validate(resolved);
        this.authority = resolved;
        log.info("Runtime authority initialized: persona={}, matrixVersion={}, targetTypes={}, storageBackends={}",
                persona, resolved.legalRuntimeMatrixVersion(),
                resolved.allowedTargetTypes(), resolved.allowedStorageBackends());
    }

    public RuntimeAuthority getAuthority() {
        return authority;
    }

    public RuntimePersona getActivePersona() {
        return authority.activePersona();
    }

    public boolean isTargetTypeAllowed(String targetType) {
        if ("LOCAL_MATERIALIZATION".equals(targetType)) {
            return props.isAllowLocalMaterialization();
        }
        return authority.isTargetTypeAllowed(targetType);
    }

    public boolean isStorageBackendAllowed(String backend) {
        return authority.isStorageBackendAllowed(backend);
    }

    public boolean isMaterializationAllowed(String layer, String format) {
        return authority.isMaterializationAllowed(layer, format);
    }

    public void validateTargetType(String targetType) {
        if (!isTargetTypeAllowed(targetType)) {
            throw new RuntimeAuthorityViolationException(
                    "Target type '" + targetType + "' is not allowed for persona "
                            + authority.activePersona());
        }
    }

    public void validateStorageBackend(String backend) {
        if (!isStorageBackendAllowed(backend)) {
            throw new RuntimeAuthorityViolationException(
                    "Storage backend '" + backend + "' is not allowed for persona "
                            + authority.activePersona());
        }
    }

    public void validateMaterialization(String layer, String format) {
        if (!isMaterializationAllowed(layer, format)) {
            throw new RuntimeAuthorityViolationException(
                    "Materialization format '" + format + "' at layer '" + layer
                            + "' is not allowed for persona " + authority.activePersona());
        }
    }

    private RuntimePersona resolvePersona() {
        String raw = props.getActivePersona();
        if (raw == null || raw.isBlank()) {
            log.warn("pulse.runtime.active-persona is not set; defaulting to GCP_PULSE");
            return RuntimePersona.GCP_PULSE;
        }
        return RuntimePersona.parse(raw);
    }

    private RuntimeAuthority buildAuthority(RuntimePersona persona) {
        if (hasExplicitConfig()) {
            return buildFromExplicitConfig(persona);
        }
        return buildPreset(persona);
    }

    private boolean hasExplicitConfig() {
        return props.getAllowedTargetTypes() != null && !props.getAllowedTargetTypes().isEmpty();
    }

    private RuntimeAuthority buildFromExplicitConfig(RuntimePersona persona) {
        return new RuntimeAuthority(
                persona,
                persona.displayName(),
                new LinkedHashSet<>(props.getAllowedTargetTypes()),
                new LinkedHashSet<>(props.getAllowedStorageBackends()),
                new LinkedHashSet<>(props.getAllowedOrchestrators()),
                new LinkedHashSet<>(props.getAllowedComputeRuntimes()),
                new LinkedHashSet<>(props.getAllowedStorageKinds()),
                new LinkedHashSet<>(props.getAllowedCatalogs()),
                parseBrokerPeers(props.getAllowedBrokerPeers()),
                props.getAllowedMaterializations(),
                SecretAuthorityKind.parse(props.getSecretAuthority()),
                props.getLegalRuntimeMatrixVersion()
        );
    }

    private RuntimeAuthority buildPreset(RuntimePersona persona) {
        return switch (persona) {
            case GCP_PULSE -> buildGcpPreset();
            case DPC_PULSE -> buildDpcPreset();
        };
    }

    private RuntimeAuthority buildGcpPreset() {
        Map<String, List<String>> materializations = new LinkedHashMap<>();
        materializations.put("bronze", List.of("iceberg_bq_managed"));
        materializations.put("silver", List.of("iceberg_bq_managed"));
        materializations.put("gold", List.of("bq_native"));

        return new RuntimeAuthority(
                RuntimePersona.GCP_PULSE,
                RuntimePersona.GCP_PULSE.displayName(),
                Set.of("GCP_COMPOSER_DATAPROC"),
                Set.of("GCP"),
                Set.of("COMPOSER"),
                Set.of("DATAPROC"),
                Set.of("GCS"),
                Set.of("BIGQUERY", "BIGLAKE_ICEBERG"),
                Set.of(RuntimePersona.DPC_PULSE),
                materializations,
                SecretAuthorityKind.parse(props.getSecretAuthority()),
                props.getLegalRuntimeMatrixVersion()
        );
    }

    private RuntimeAuthority buildDpcPreset() {
        Map<String, List<String>> materializations = new LinkedHashMap<>();
        materializations.put("bronze", List.of("parquet"));
        materializations.put("silver", List.of("parquet"));
        materializations.put("gold", List.of("parquet"));

        return new RuntimeAuthority(
                RuntimePersona.DPC_PULSE,
                RuntimePersona.DPC_PULSE.displayName(),
                Set.of("DPC_AIRFLOW_OPENSHIFT_SPARK"),
                Set.of("DPC"),
                Set.of("AIRFLOW_ON_PREM"),
                Set.of("CLOUDERA_SPARK_SERVICE"),
                Set.of("S3A_OBJECT_STORAGE"),
                Set.of("HIVE_JDBC"),
                Set.of(RuntimePersona.GCP_PULSE),
                materializations,
                SecretAuthorityKind.parse(props.getSecretAuthority()),
                props.getLegalRuntimeMatrixVersion()
        );
    }

    private void validate(RuntimeAuthority auth) {
        if (auth.allowedTargetTypes().isEmpty()) {
            throw new IllegalStateException(
                    "Runtime authority for persona " + auth.activePersona()
                            + " has empty allowedTargetTypes; check pulse.runtime configuration");
        }
        if (auth.allowedStorageBackends().isEmpty()) {
            throw new IllegalStateException(
                    "Runtime authority for persona " + auth.activePersona()
                            + " has empty allowedStorageBackends; check pulse.runtime configuration");
        }
    }

    public void validateBrokerPeerAllowed(String peerPersona) {
        RuntimePersona parsed = RuntimePersona.parse(peerPersona);
        if (!authority.isBrokerPeerAllowed(parsed)) {
            throw new RuntimeAuthorityViolationException(
                    "Broker peer persona '" + parsed + "' is not allowed for persona "
                            + authority.activePersona());
        }
    }

    private Set<RuntimePersona> parseBrokerPeers(List<String> raw) {
        Set<RuntimePersona> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String value : raw) {
            if (value != null && !value.isBlank()) out.add(RuntimePersona.parse(value));
        }
        return out;
    }

    public static class RuntimeAuthorityViolationException extends RuntimeException {
        public RuntimeAuthorityViolationException(String message) {
            super(message);
        }
    }
}
