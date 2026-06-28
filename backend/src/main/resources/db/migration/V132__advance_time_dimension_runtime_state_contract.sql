-- ARCH-019: AdvanceTimeDimension promoted runtime contract
--
-- Replace legacy endpoint-routing params with runtime-state refs. Promoted
-- artifacts must resolve time-state mutation through packaged runtime helpers,
-- not PULSE callbacks.

UPDATE blueprints
   SET params_schema = '[
     {"name":"target_scope","type":"enum","required":true,"options":["dataset","domain"],"default":"dataset"},
     {"name":"state_binding_ref","type":"string","required":true},
     {"name":"variable_key","type":"string","required":true},
     {"name":"calendar_binding_ref","type":"string","required":true},
     {"name":"calendar_bundle_uri","type":"string","required":true},
     {"name":"calendar_bundle_hash","type":"string","required":false},
     {"name":"calendar_id","type":"string","required":false,"default":"US-FED"},
     {"name":"grain","type":"string","required":false,"default":"DAILY_BUSINESS_DAY"},
     {"name":"timezone","type":"string","required":false,"default":"America/New_York"},
     {"name":"advance_mode","type":"enum","required":false,"options":["next_interval","requested_asof"],"default":"next_interval"},
     {"name":"requested_asof_expr","type":"string","required":false},
     {"name":"replay_policy","type":"enum","required":false,"options":["reject_backward","allow_backward","allow_same_value"],"default":"reject_backward"},
     {"name":"initial_value","type":"string","required":false},
     {"name":"initialization_policy","type":"enum","required":false,"options":["require_existing","allow_projected_initial_value"],"default":"require_existing"},
     {"name":"concurrency_policy","type":"enum","required":false,"options":["serialized_airflow","runtime_sql_lock"],"default":"serialized_airflow"},
     {"name":"evidence_prefix","type":"string","required":true},
     {"name":"evidence_required","type":"boolean","required":false,"default":true},
     {"name":"notes_template","type":"string","required":false},
     {"name":"advanced_by","type":"string","required":false},
     {"name":"source","type":"string","required":false}
   ]'::jsonb
 WHERE blueprint_key = 'AdvanceTimeDimension';
