package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;

import java.util.List;

/**
 * Everything an {@link OpEmitHandler} needs to emit one op's code fragment
 * (SPEC #2 §C). Built per-op as the codegen compiler walks the op-list; the
 * config is already param-resolved (param-refs substituted) by the design-time
 * resolver, mirroring "one op-list, two readers".
 *
 * <p>Fragments are deterministic strings (no LLM, ADR 0013) and must be
 * byte-stable (ADR 0009): use the provided ordered schema, no map-iteration
 * order, explicit ORDER BY tiebreakers on dedup/rank.
 */
public final class EmitContext {

    private final Mode mode;
    private final ModeResolver modeResolver;
    private final ResolvedConfig config;
    /** The op's input schema (the design-time columns flowing in). */
    private final Schema inputSchema;
    /** The op's output schema (the Phase-1 authoritative columns this op must produce). */
    private final Schema outputSchema;
    /** Secondary input schema (join only); null otherwise. */
    private final Schema secondarySchema;
    /** dbt-SQL: the upstream relation reference for {@code FROM} (e.g. {@code ref('...')} or a CTE). */
    private final String upstreamRef;
    /** dbt-SQL: the secondary relation reference (join right side); null otherwise. */
    private final String secondaryRef;
    /** PySpark: the input DataFrame variable name (e.g. {@code df}). */
    private final String dfVar;
    /** The medallion lake layer (bronze/silver/gold) for Mode-aware format choice. */
    private final String lakeLayer;

    private EmitContext(Builder b) {
        this.mode = b.mode;
        this.modeResolver = b.modeResolver;
        this.config = b.config == null ? ResolvedConfig.empty() : b.config;
        this.inputSchema = b.inputSchema == null ? Schema.empty() : b.inputSchema;
        this.outputSchema = b.outputSchema == null ? Schema.empty() : b.outputSchema;
        this.secondarySchema = b.secondarySchema;
        this.upstreamRef = b.upstreamRef;
        this.secondaryRef = b.secondaryRef;
        this.dfVar = b.dfVar == null ? "df" : b.dfVar;
        this.lakeLayer = b.lakeLayer;
    }

    public Mode mode() { return mode; }
    public ModeResolver modeResolver() { return modeResolver; }
    public ResolvedConfig config() { return config; }
    public Schema inputSchema() { return inputSchema; }
    public Schema outputSchema() { return outputSchema; }
    public Schema secondarySchema() { return secondarySchema; }
    public String upstreamRef() { return upstreamRef; }
    public String secondaryRef() { return secondaryRef; }
    public String dfVar() { return dfVar; }
    public String lakeLayer() { return lakeLayer; }

    /** Input column names in order (a common handler need). */
    public List<String> inputColumnNames() { return inputSchema.names(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Mode mode = Mode.GCP_PULSE;
        private ModeResolver modeResolver;
        private ResolvedConfig config;
        private Schema inputSchema;
        private Schema outputSchema;
        private Schema secondarySchema;
        private String upstreamRef;
        private String secondaryRef;
        private String dfVar;
        private String lakeLayer;

        public Builder mode(Mode m) { this.mode = m; return this; }
        public Builder modeResolver(ModeResolver r) { this.modeResolver = r; return this; }
        public Builder config(ResolvedConfig c) { this.config = c; return this; }
        public Builder inputSchema(Schema s) { this.inputSchema = s; return this; }
        public Builder outputSchema(Schema s) { this.outputSchema = s; return this; }
        public Builder secondarySchema(Schema s) { this.secondarySchema = s; return this; }
        public Builder upstreamRef(String r) { this.upstreamRef = r; return this; }
        public Builder secondaryRef(String r) { this.secondaryRef = r; return this; }
        public Builder dfVar(String v) { this.dfVar = v; return this; }
        public Builder lakeLayer(String l) { this.lakeLayer = l; return this; }

        public EmitContext build() { return new EmitContext(this); }
    }
}
