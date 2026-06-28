package com.pulse.runtime.controller;

import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime-authority")
public class RuntimeAuthorityController {

    private final RuntimeAuthorityService runtimeAuthorityService;

    public RuntimeAuthorityController(RuntimeAuthorityService runtimeAuthorityService) {
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAuthority() {
        RuntimeAuthority auth = runtimeAuthorityService.getAuthority();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activePersona", auth.activePersona().name());
        response.put("displayName", auth.displayName());
        response.put("allowedTargetTypes", auth.allowedTargetTypes());
        response.put("allowedStorageBackends", auth.allowedStorageBackends());
        response.put("allowedOrchestrators", auth.allowedOrchestrators());
        response.put("allowedComputeRuntimes", auth.allowedComputeRuntimes());
        response.put("allowedStorageKinds", auth.allowedStorageKinds());
        response.put("allowedCatalogs", auth.allowedCatalogs());
        response.put("allowedBrokerPeers", auth.allowedBrokerPeers());
        response.put("allowedMaterializations", auth.allowedMaterializations());
        response.put("secretAuthority", auth.secretAuthority().name());
        response.put("legalRuntimeMatrixVersion", auth.legalRuntimeMatrixVersion());

        // PKT-0023: Physical design authority — partition transforms, layout
        // strategies, DDL executors, and DDL limits per persona.
        response.put("physicalDesignAuthority", buildPhysicalDesignAuthority(auth));

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildPhysicalDesignAuthority(RuntimeAuthority auth) {
        Map<String, Object> pda = new LinkedHashMap<>();
        RuntimePersona persona = auth.activePersona();

        // Partition transforms available per persona
        pda.put("partitionTransforms", switch (persona) {
            case GCP_PULSE -> List.of("identity", "year", "month", "day", "hour", "truncate", "bucket");
            case DPC_PULSE -> List.of("identity", "year", "month", "day");
        });

        // Layout strategies (sort/cluster) per persona
        pda.put("layoutStrategies", switch (persona) {
            case GCP_PULSE -> List.of("clustering", "z_order");
            case DPC_PULSE -> List.of("sort_by");
        });

        // DDL executors available per persona
        pda.put("ddlExecutors", switch (persona) {
            case GCP_PULSE -> List.of("BIGQUERY_SQL", "SPARK_SQL");
            case DPC_PULSE -> List.of("HIVE_JDBC", "SPARK_SQL");
        });

        // DDL dialects available per persona
        pda.put("ddlDialects", switch (persona) {
            case GCP_PULSE -> List.of("BIGQUERY", "SPARK_ICEBERG");
            case DPC_PULSE -> List.of("HIVE", "SPARK_ICEBERG");
        });

        // DDL limits
        pda.put("ddlLimits", Map.of(
                "maxStatementsPerProjection", 50,
                "maxBodySizeBytes", 65536,
                "requireIdempotency", true
        ));

        return pda;
    }
}
