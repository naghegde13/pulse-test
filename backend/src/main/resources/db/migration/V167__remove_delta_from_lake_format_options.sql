-- V167: Remove "delta" from lake_format options arrays across the catalog.
--
-- V153/V156 seeded lake_format.options containing "delta". V160 replaced the
-- default value from "delta" to "parquet" but did not touch the options
-- arrays. Although the frontend's legalLakeFormats() filters at render time,
-- any surface consuming raw params_schema.options (e.g. API consumers, test
-- fixtures, the chat agent) can still see delta as a listed valid choice.
-- This migration strips "delta" from every active lake_format param's
-- options array to close that leak.

UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'lake_format' AND elem ? 'options'
            THEN elem || jsonb_build_object(
                     'options',
                     (SELECT jsonb_agg(opt)
                      FROM jsonb_array_elements(elem -> 'options') AS opt
                      WHERE opt::text <> '"delta"'))
            ELSE elem
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
) WHERE status = 'ACTIVE'
  AND params_schema @> '[{"name":"lake_format"}]';
