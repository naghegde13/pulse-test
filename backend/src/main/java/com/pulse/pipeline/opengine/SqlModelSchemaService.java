package com.pulse.pipeline.opengine;

import com.pulse.expression.service.BuildFailureException;
import com.pulse.expression.service.CalciteSqlModelValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@code sql-model} op's output schema (SPEC #1 §B rule 27, #2 §C.4).
 *
 * <p><b>CALCITE-PHASE-2 LANDED — Calcite-primary (ADR-0024 forward-update).</b>
 * {@code sql-model} now derives its OUT schema by validating the user-authored
 * step chain against its input schema with the deterministic
 * {@link CalciteSqlModelValidator} (SPEC #6). The DECLARE-schema path is the
 * <b>fallback</b>: on a Calcite parse/validation error the optional
 * {@code declared_output_schema}/{@code declared_schema} is used (warn-logged);
 * with no declared schema present the build <b>loud-fails</b> (ADR 0011 — no LLM,
 * no guess). This service NEVER calls {@code schemaInferenceService} or any LLM
 * path.
 *
 * <p>The Calcite branch is single call into {@link CalciteSqlModelValidator}; the
 * validator does not execute SQL and does not resolve a date ({@code [[ … ]]}
 * tokens are typed as DATE placeholders for parse/derivation only).
 */
@Service
public class SqlModelSchemaService {

    /** Config key carrying the ordered {@code sql-model} step chain (SPEC #6 §B.1). */
    public static final String STEPS = "steps";
    /** Config key carrying the optional DE/Customer declared output schema (§A.6 fallback). */
    public static final String DECLARED_SCHEMA = "declared_schema";
    /** Alternate config key for the declared output schema (#6 §A.6 / catalog draft). */
    public static final String DECLARED_OUTPUT_SCHEMA = "declared_output_schema";

    private final CalciteSqlModelValidator calciteValidator;

    public SqlModelSchemaService(CalciteSqlModelValidator calciteValidator) {
        this.calciteValidator = calciteValidator;
    }

    /**
     * Resolve via the declare-schema path: build the output {@link Schema} from the
     * op's {@code declared_schema} config (a list of {@code {name,type[,nullable]}} maps).
     * Loud-fails if absent. This is the §A.6 fallback path, kept callable directly.
     */
    @SuppressWarnings("unchecked")
    public Schema resolveByDeclaredSchema(ResolvedConfig cfg) {
        List<Map<String, Object>> declared = readDeclared(cfg);
        if (declared.isEmpty()) {
            throw new OpEngineException(
                    "sql-model requires either a derivable step chain (config 'steps') or a "
                    + "declared output schema (config 'declared_schema'/'declared_output_schema'); "
                    + "neither was usable (ADR 0011 — no LLM fallback)");
        }
        List<ColumnModel> cols = new ArrayList<>();
        for (Map<String, Object> d : declared) {
            cols.add(ColumnModel.fromMap(d));
        }
        return new Schema(cols);
    }

    /**
     * THE CALCITE-PRIMARY resolution (SPEC #6 §A.6). Derives the OUT schema of the
     * {@code sql-model} step chain against {@code inputSchema} via the deterministic
     * {@link CalciteSqlModelValidator}: derivation wins; on a Calcite error the
     * optional {@code declaredSchema} is used (warn-logged); with no declared schema
     * the build loud-fails ({@link BuildFailureException}). Never an LLM (ADR 0011).
     *
     * @param steps          the ordered step chain (never empty for the Calcite path)
     * @param inputSchema    the upstream {@code input}-port schema (may be empty)
     * @param declaredSchema the optional §A.6 fallback (may be {@code null}/empty)
     * @return the resolved OUT schema (derived, else declared)
     */
    public Schema resolveByCalcite(List<CalciteSqlModelValidator.SqlModelStep> steps,
                                   Schema inputSchema,
                                   List<Map<String, Object>> declaredSchema) {
        List<Map<String, Object>> inputCols = schemaToColumns(inputSchema);
        List<Map<String, Object>> declared =
                (declaredSchema == null || declaredSchema.isEmpty()) ? null : declaredSchema;
        // Single call into the Calcite lane's validator (no SQL execution, no date resolution).
        List<Map<String, Object>> outColumns =
                calciteValidator.resolveSqlModelSchema(inputCols, steps, declared);
        return columnsToSchema(outColumns);
    }

    /**
     * The op-engine seam: resolve a {@code sql-model} op-entry's OUT schema, given
     * the running input {@link Schema} and the resolved op config. Calcite-primary
     * when a {@code steps} chain is present; declare-schema otherwise (and as the
     * §A.6 fallback inside {@link #resolveByCalcite}).
     */
    public Schema resolve(Schema inputSchema, ResolvedConfig cfg) {
        List<CalciteSqlModelValidator.SqlModelStep> steps = readSteps(cfg);
        if (!steps.isEmpty()) {
            return resolveByCalcite(steps, inputSchema, readDeclared(cfg));
        }
        // No step chain — only the declare-schema path is available (loud-fail if absent).
        return resolveByDeclaredSchema(cfg);
    }

    // ---- config readers ---------------------------------------------------

    /** config {@code steps} (a list of {@code {name, sql, materialize}} maps) → step records. */
    private List<CalciteSqlModelValidator.SqlModelStep> readSteps(ResolvedConfig cfg) {
        List<CalciteSqlModelValidator.SqlModelStep> out = new ArrayList<>();
        if (cfg == null) {
            return out;
        }
        for (Map<String, Object> m : cfg.getMapList(STEPS)) {
            Object name = m.get("name");
            Object sql = m.get("sql");
            boolean materialize = Boolean.TRUE.equals(m.get("materialize"))
                    || "true".equalsIgnoreCase(String.valueOf(m.get("materialize")));
            out.add(new CalciteSqlModelValidator.SqlModelStep(
                    name == null ? null : name.toString(),
                    sql == null ? null : sql.toString(),
                    materialize));
        }
        return out;
    }

    /** The declared output schema from either config key (§A.6). */
    private List<Map<String, Object>> readDeclared(ResolvedConfig cfg) {
        if (cfg == null) {
            return List.of();
        }
        List<Map<String, Object>> declared = cfg.getMapList(DECLARED_SCHEMA);
        if (declared.isEmpty()) {
            declared = cfg.getMapList(DECLARED_OUTPUT_SCHEMA);
        }
        return declared;
    }

    // ---- Schema <-> column-descriptor-list round-trip ---------------------

    private static List<Map<String, Object>> schemaToColumns(Schema schema) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (schema != null) {
            for (ColumnModel c : schema.columns()) {
                cols.add(c.toMap());
            }
        }
        return cols;
    }

    private static Schema columnsToSchema(List<Map<String, Object>> columns) {
        List<ColumnModel> cols = new ArrayList<>();
        if (columns != null) {
            for (Map<String, Object> m : columns) {
                ColumnModel cm = ColumnModel.fromMap(m);
                if (cm != null) {
                    cols.add(cm);
                }
            }
        }
        return new Schema(cols);
    }
}
