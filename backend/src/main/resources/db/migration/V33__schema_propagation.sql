-- V33: Schema propagation support.
-- Adds output_schema JSONB column to sub_pipeline_instances so each node
-- can advertise the schema it produces.  Downstream nodes (transforms,
-- destinations) read the upstream output_schema for column auto-complete
-- and validation.
--
-- Format: {"columns": [{"name": "col1", "type": "string"}, ...]}

ALTER TABLE sub_pipeline_instances
    ADD COLUMN output_schema JSONB;
