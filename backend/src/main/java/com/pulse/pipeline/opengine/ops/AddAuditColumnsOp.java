package com.pulse.pipeline.opengine.ops;

import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code add-audit-columns} (SPEC #1 §B.1 rule 25) — OUT = IN + the PULSE audit
 * column set.
 *
 * <p>Exactly the 8 audit columns from
 * {@link IngestionAuditColumns#asColumnDescriptors()} are appended after the input
 * columns, in that source-of-truth order:
 * {@code _pulse_ingested_at}, {@code _pulse_processing_ts}, {@code _pulse_pipeline},
 * {@code _pulse_task}, {@code _pulse_run_id}, {@code _pulse_source_uri},
 * {@code _pulse_business_date}, {@code _pulse_dag_id}.
 *
 * <p>{@link IngestionAuditColumns} is the single source of truth (C-1, locked
 * 2026-06-15); this op never hardcodes a second copy of the names or order.
 */
public final class AddAuditColumnsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.ADD_AUDIT_COLUMNS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        List<ColumnModel> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (in != null) {
            out.addAll(in.columns());
            for (ColumnModel column : in.columns()) {
                if (column.name() != null) seen.add(column.name());
            }
        }
        for (Map<String, Object> descriptor : IngestionAuditColumns.asColumnDescriptors()) {
            Object name = descriptor.get("name");
            if (name != null && seen.contains(name.toString())) continue;
            out.add(ColumnModel.fromMap(descriptor));
            if (name != null) seen.add(name.toString());
        }
        return new Schema(out);
    }
}
