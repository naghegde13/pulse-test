package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code change-types} (SPEC #1 §B.1 rule 6) — OUT = IN with the named columns'
 * type replaced per {@code type_coercions}; order is preserved.
 *
 * <p>Config: {@code type_coercions: {col: newtype}}. A column whose name is not a
 * key in the map keeps its type. An absent/empty {@code type_coercions} is the
 * unconfigured do-nothing passthrough default (SPEC #1 §A.2).
 */
public final class ChangeTypesOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.CHANGE_TYPES;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        Map<String, String> coercions = cfg.getStringMap("type_coercions");
        if (coercions.isEmpty()) {
            return new Schema(in.columns());
        }
        List<ColumnModel> out = new ArrayList<>();
        for (ColumnModel c : in.columns()) {
            String newType = coercions.get(c.name());
            out.add(newType != null ? c.withType(newType) : c);
        }
        return new Schema(out);
    }
}
