package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code route-rows} (SPEC #2 §C.1).
 *
 * <p><b>FIX #1 (one model per branch):</b> {@code route-rows} fans out into N
 * branch models. A handler emits ONE fragment, so this emits a single branch's
 * filtered {@code SELECT * FROM <ref> WHERE <branch_condition>}. The emitter is
 * responsible for invoking the handler once per branch (each call carries that
 * branch's {@code branch_condition}); the handler stays deterministic and
 * branch-local.
 *
 * <p>Condition source: {@code branch_condition} (the emitter passes one per call);
 * else the first entry of a {@code branches} list ({@code condition} key). With no
 * condition the op degrades to a pass-through {@code SELECT * FROM <ref>}.
 */
public final class RouteRowsDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.ROUTE_ROWS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();

        String condition = config.getString("branch_condition");
        boolean fromList = false;
        if (condition == null || condition.isBlank()) {
            List<Map<String, Object>> branches = config.getMapList("branches");
            if (branches.isEmpty()) {
                branches = config.getMapList("routes");
            }
            if (!branches.isEmpty()) {
                Object c = branches.get(0).get("condition");
                if (c != null && !c.toString().isBlank()) {
                    condition = c.toString();
                    fromList = true;
                }
            }
        }

        if (condition == null || condition.isBlank()) {
            return "SELECT * FROM " + ref;
        }

        StringBuilder sql = new StringBuilder();
        if (fromList) {
            // route-rows fans out into one model per branch; the emitter drives the
            // fan-out, this fragment is the first branch's filtered SELECT.
            sql.append("-- route-rows: one model per branch (emitter fan-out); first branch\n");
        }
        sql.append("SELECT * FROM ").append(ref).append("\nWHERE ").append(condition.trim());
        return sql.toString();
    }
}
