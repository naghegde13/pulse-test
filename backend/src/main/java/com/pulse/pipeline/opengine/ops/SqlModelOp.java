package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpEngineException;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;
import com.pulse.pipeline.opengine.SqlModelSchemaService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code sql-model} (SPEC #1 §B.1 rule 27) — OUT = the SQL chain's output columns.
 *
 * <p><b>Resolution path (CALCITE-PHASE-2 landed).</b> When wired with the
 * {@link SqlModelSchemaService} (the op-engine seam), {@code sql-model} is
 * <b>Calcite-primary</b>: it derives OUT by validating the {@code steps} chain
 * against the input schema, with the DE/Customer {@code declared_schema} as the
 * §A.6 fallback and a loud-fail when neither resolves (ADR 0011 — no LLM).
 *
 * <p><b>Declared-only fallback (no service wired).</b> If constructed without the
 * service (the legacy no-arg path), the op resolves ONLY via the declare-schema
 * config — a list of {@code {name, type[, nullable]}} maps under
 * {@code declared_schema} — and loud-fails when absent. This keeps the op usable
 * as a pure, dependency-free rule instance in tests / non-Spring contexts.
 */
public final class SqlModelOp implements SchemaOp {

    /** Config key carrying the DE-supplied output column descriptors. */
    static final String DECLARED_SCHEMA = "declared_schema";

    /** The Calcite-primary seam; null in the legacy declared-only path. */
    private final SqlModelSchemaService sqlModelSchemaService;

    /** Legacy declared-only path (no Calcite seam). */
    public SqlModelOp() {
        this(null);
    }

    /** Calcite-primary path: delegate OUT-schema resolution to the seam. */
    public SqlModelOp(SqlModelSchemaService sqlModelSchemaService) {
        this.sqlModelSchemaService = sqlModelSchemaService;
    }

    @Override
    public String opName() {
        return OpVocabulary.SQL_MODEL;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Calcite-primary when the seam is wired (steps -> derive; declared -> §A.6 fallback;
        // neither -> loud-fail). NEVER reaches schemaInferenceService / any LLM (ADR 0011).
        if (sqlModelSchemaService != null) {
            return sqlModelSchemaService.resolve(in, cfg);
        }

        // Legacy declared-only path (no Calcite seam wired).
        List<Map<String, Object>> declared = cfg.getMapList(DECLARED_SCHEMA);
        if (declared.isEmpty()) {
            throw new OpEngineException(
                    "sql-model: a declared output schema ('" + DECLARED_SCHEMA + "') is "
                            + "required when the Calcite seam is not wired — the SQL's output "
                            + "columns cannot be inferred (ADR 0011, no LLM fallback). Supply "
                            + "the declared output schema (Customer/DE).");
        }

        List<ColumnModel> out = new ArrayList<>(declared.size());
        for (Map<String, Object> entry : declared) {
            ColumnModel cm = ColumnModel.fromMap(entry);
            if (cm != null) out.add(cm);
        }
        if (out.isEmpty()) {
            throw new OpEngineException(
                    "sql-model: the declared output schema ('" + DECLARED_SCHEMA + "') "
                            + "contained no usable column descriptors.");
        }
        return new Schema(out);
    }
}
