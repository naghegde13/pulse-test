package com.pulse.cobol.service;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CobolFlatteningService {

    public record FlattenOutcome(
            Dataset<Row> dataset,
            List<Map<String, Object>> mappingSpec,
            Map<String, Object> flattenSpec) {}

    public FlattenOutcome flatten(Dataset<Row> dataset) {
        List<Column> columns = new ArrayList<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        Map<String, Integer> aliasCounts = new LinkedHashMap<>();
        for (StructField field : dataset.schema().fields()) {
            collect(field.dataType(), field.name(), field.name(), columns, mapping, aliasCounts);
        }
        Dataset<Row> flattened = dataset.select(columns.toArray(new Column[0]));
        Map<String, Object> flattenSpec = new LinkedHashMap<>();
        flattenSpec.put("mode", "flatten_structs_jsonify_complex");
        flattenSpec.put("columns", mapping);
        return new FlattenOutcome(flattened, mapping, flattenSpec);
    }

    private void collect(
            DataType dataType,
            String sourcePath,
            String aliasBase,
            List<Column> columns,
            List<Map<String, Object>> mapping,
            Map<String, Integer> aliasCounts) {
        if (dataType instanceof StructType structType) {
            for (StructField child : structType.fields()) {
                collect(
                        child.dataType(),
                        sourcePath + "." + child.name(),
                        aliasBase + "_" + child.name(),
                        columns,
                        mapping,
                        aliasCounts
                );
            }
            return;
        }

        String alias = uniqueAlias(sanitize(aliasBase), aliasCounts);
        String strategy = "primitive_pass_through";
        Column column;
        if (dataType instanceof ArrayType || dataType instanceof MapType) {
            column = functions.to_json(functions.col(sourcePath)).alias(alias);
            strategy = "json_stringify";
        } else {
            column = functions.col(sourcePath).alias(alias);
        }
        columns.add(column);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourcePath", sourcePath);
        row.put("outputColumn", alias);
        row.put("dataType", dataType.typeName());
        row.put("strategy", strategy);
        mapping.add(row);
    }

    private String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String uniqueAlias(String alias, Map<String, Integer> aliasCounts) {
        int current = aliasCounts.getOrDefault(alias, 0);
        aliasCounts.put(alias, current + 1);
        return current == 0 ? alias : alias + "_" + current;
    }
}
