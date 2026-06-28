-- V34: Router blueprint -- routes data to multiple output ports based on conditions.
-- Output ports are dynamic: one per routing condition + an optional default/unmatched port.

INSERT INTO blueprints (id, blueprint_key, name, description, category, version, params_schema, input_ports, output_ports, deferred, pipeline_config) VALUES

('01JBP0XFRM0GENERICROUT01', 'GenericRouter', 'Router',
 'Route rows to different output ports based on SQL conditions. Each route defines a condition and a named output. Rows matching no condition go to the default output.',
 'TRANSFORM', '1.0.0',
 '[{"name":"routes","type":"object[]","required":true,"description":"Array of routing rules. Each has a name (becomes the output port), a SQL condition, and an optional description.","items":{"name":"string","condition":"string","description":"string"}},{"name":"include_default","type":"boolean","default":true,"description":"Whether to include a default output port for unmatched rows"}]',
 '[{"name":"data_input","description":"Input dataset to route"}]',
 '[{"name":"default_output","description":"Rows that match no routing condition"}]',
 false, false);
