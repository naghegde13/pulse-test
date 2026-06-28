package com.pulse.chat.prompt;

import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * §8b ACTIVE MODE injection (fragment 01 §8b; ADR 0001/0007) — the GLOBAL storage
 * Mode (persona) injected as a per-deployment CONSTANT into the
 * Build/Composer · Configure · Provision · Planner stage prompts (the stages
 * that touch composition / storage). It is NOT a per-turn tool: the prompt-builder
 * reads {@link RuntimeAuthorityService#getActivePersona()} once and bakes the
 * Mode + the storage mapping in, so a stage sets only user-tier fields and never
 * the storage backend / lake format / bucket / path.
 *
 * <p>The mapping matches the live persona presets (RuntimeAuthorityService): GCP
 * bronze+silver = BigQuery-managed Iceberg, gold = BigQuery native; DPC
 * bronze+silver+gold = Hive + Parquet on S3A.</p>
 */
@Component
public class ActiveModeBlock {

    /** A stable marker the assembled prompt carries so tests can assert injection. */
    public static final String MARKER = "ACTIVE MODE:";

    private final RuntimeAuthorityService runtimeAuthorityService;

    @Autowired
    public ActiveModeBlock(RuntimeAuthorityService runtimeAuthorityService) {
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    /**
     * The injected ACTIVE MODE block for the active deployment persona. Storage is
     * a per-deployment CONSTANT — the stage never sets or asks for it.
     */
    public String render() {
        RuntimePersona persona = runtimeAuthorityService.getActivePersona();
        return render(persona);
    }

    /** Render for an explicit persona (visible for tests / deterministic rendering). */
    public static String render(RuntimePersona persona) {
        boolean gcp = persona == RuntimePersona.GCP_PULSE;
        String mode = gcp ? "GCP" : "DPC";
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(MARKER).append(' ').append(mode)
          .append(" (").append(persona.name()).append(") ===\n");
        sb.append("[injected constant — RuntimeAuthorityService.getActivePersona()]\n");
        sb.append("Storage is fixed by this Mode for the whole deployment. You do NOT choose, ask for, or set the\n");
        sb.append("storage backend, lake format, bucket, or path — they are derived from the Mode + medallion layer:\n");
        if (gcp) {
            sb.append("  bronze, silver -> BigQuery-managed Iceberg (iceberg_bq_managed) ; gold -> BigQuery native (bq_native).\n");
            sb.append("  (In DPC Mode: bronze + silver + gold -> Hive + Parquet on S3A object storage.)\n");
        } else {
            sb.append("  bronze, silver, gold -> Hive + Parquet (parquet) on S3A object storage.\n");
            sb.append("  (In GCP Mode: bronze + silver -> BigQuery-managed Iceberg ; gold -> BigQuery native.)\n");
        }
        sb.append("Set only user-tier canonical fields; the lake format follows from the Mode. ");
        sb.append("gold-on-GCP is bq_native; never override. iceberg_bq_managed is GCP-only; parquet is DPC-only.\n");
        sb.append("Surface the storage target read-only if the user asks \"where does this land\"; otherwise stay silent.\n");
        return sb.toString();
    }
}
