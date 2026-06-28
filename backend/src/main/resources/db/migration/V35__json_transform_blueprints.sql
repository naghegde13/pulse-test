-- V35: JSON flattening and struct-building transform blueprints.

INSERT INTO blueprints (id, blueprint_key, name, description, category, version, params_schema, input_ports, output_ports, deferred, pipeline_config) VALUES

('01JBP0XFRM0JSONFLAT00001', 'JsonFlatten', 'JSON Flatten',
 'Flatten nested JSON/struct columns into a flat tabular schema. Nested fields become dot-separated column names (e.g., address.city). Supports configurable depth, array explosion, and prefix options.',
 'TRANSFORM', '1.0.0',
 '[{"name":"source_columns","type":"string[]","required":true,"description":"Columns containing nested JSON/struct data to flatten. Use * to flatten all struct columns."},{"name":"max_depth","type":"integer","default":3,"description":"Maximum nesting depth to flatten. 0 = unlimited."},{"name":"separator","type":"string","default":"_","description":"Separator for flattened column names (e.g., address_city vs address.city)"},{"name":"explode_arrays","type":"boolean","default":false,"description":"If true, array columns are exploded into separate rows (LATERAL VIEW EXPLODE). If false, arrays are kept as-is."},{"name":"prefix","type":"string","description":"Optional prefix to add to all flattened column names"},{"name":"keep_original","type":"boolean","default":false,"description":"Keep the original nested column alongside the flattened columns"}]',
 '[{"name":"data_input","description":"Input dataset with nested JSON/struct columns"}]',
 '[{"name":"flat_output","description":"Flattened tabular dataset"}]',
 false, false),

('01JBP0XFRM0JSONSTRUCT001', 'JsonStruct', 'Build JSON/Struct',
 'Combine flat columns into nested JSON or struct columns. Group related columns into named structs or build JSON strings for downstream consumption.',
 'TRANSFORM', '1.0.0',
 '[{"name":"output_format","type":"enum","options":["struct","json_string"],"default":"struct","required":true,"description":"Output as a Spark struct column or a JSON string column"},{"name":"mappings","type":"object[]","required":true,"description":"Array of struct definitions. Each maps flat columns into a named nested structure.","items":{"struct_name":"string","fields":"object[]","description":"string"}},{"name":"drop_source_columns","type":"boolean","default":false,"description":"Drop the original flat columns after building the struct/JSON"},{"name":"passthrough_columns","type":"string[]","description":"Columns to keep as-is in the output alongside the new struct columns. Use * for all non-mapped columns."}]',
 '[{"name":"data_input","description":"Input dataset with flat columns"}]',
 '[{"name":"struct_output","description":"Dataset with nested struct/JSON columns"}]',
 false, false);
