-- V32: Generic transform blueprints for pipeline data transformations.
-- 3 blueprint patterns:
--   GenericJoin      -> Join two datasets on specified keys
--   GenericAggregate -> Group by + aggregate functions
--   GenericFilter    -> Filter rows with SQL WHERE conditions

-- =====================================================
-- TRANSFORM (3)
-- =====================================================

INSERT INTO blueprints (id, blueprint_key, name, description, category, version, params_schema, input_ports, output_ports, deferred, pipeline_config) VALUES

('01JBP0XFRM0GENERICJN0001', 'GenericJoin', 'Join',
 'Join two or more datasets on specified keys. Supports inner, left, right, full outer, and cross joins.',
 'TRANSFORM', '1.0.0',
 '[{"name":"join_type","type":"enum","options":["inner","left","right","full_outer","cross"],"required":true,"description":"Type of join to perform"},{"name":"join_keys","type":"object[]","required":true,"description":"Array of join key pairs mapping left to right columns","items":{"left_column":"string","right_column":"string"}},{"name":"select_columns","type":"string[]","description":"Optional list of column names to keep in the output"},{"name":"alias_left","type":"string","default":"l","description":"Optional prefix alias for left dataset columns"},{"name":"alias_right","type":"string","default":"r","description":"Optional prefix alias for right dataset columns"}]',
 '[{"name":"left_input","description":"Left dataset for the join"},{"name":"right_input","description":"Right dataset for the join"}]',
 '[{"name":"joined_output","description":"Joined dataset result"}]',
 false, false),

('01JBP0XFRM0GENERICAG0001', 'GenericAggregate', 'Aggregate',
 'Group data by specified columns and compute aggregate functions (sum, count, avg, min, max, count_distinct).',
 'TRANSFORM', '1.0.0',
 '[{"name":"group_by_columns","type":"string[]","required":true,"description":"Columns to group by"},{"name":"aggregations","type":"object[]","required":true,"description":"Aggregate function definitions","items":{"column":"string","function":"enum(sum,count,avg,min,max,count_distinct)","alias":"string"}},{"name":"having_clause","type":"string","description":"Optional SQL HAVING expression to filter aggregated results"}]',
 '[{"name":"data_input","description":"Input dataset to aggregate"}]',
 '[{"name":"aggregated_output","description":"Aggregated dataset result"}]',
 false, false),

('01JBP0XFRM0GENERICFL0001', 'GenericFilter', 'Filter',
 'Filter rows using SQL WHERE conditions. Supports the built-in SQL expression builder or raw SQL.',
 'TRANSFORM', '1.0.0',
 '[{"name":"filter_mode","type":"enum","options":["visual","sql"],"required":true,"default":"visual","description":"Whether to use the visual condition builder or raw SQL"},{"name":"conditions","type":"object[]","description":"Array of filter conditions used in visual mode","items":{"column":"string","operator":"enum(eq,neq,gt,gte,lt,lte,like,not_like,in,not_in,is_null,is_not_null)","value":"string","logic":"enum(AND,OR)"}},{"name":"raw_sql","type":"string","description":"Raw SQL WHERE clause used when filter_mode is sql"}]',
 '[{"name":"data_input","description":"Input dataset to filter"}]',
 '[{"name":"filtered_output","description":"Filtered dataset result"}]',
 false, false);
