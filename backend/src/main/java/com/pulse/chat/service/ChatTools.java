package com.pulse.chat.service;

import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

public class ChatTools {

    public static List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                tool("navigate_ui",
                        "Navigate the main UI screen to a specific page. Call this tool whenever the conversation shifts to a different topic so the user sees the relevant screen. The UI and chat must always be in sync.",
                        Map.of("type", "object", "properties", Map.of(
                                "page", Map.of("type", "string", "enum", List.of("data_sources", "data_source_detail", "pipelines", "pipeline_detail", "blueprints", "commands"),
                                        "description", "Which page to show"),
                                "resource_id", Map.of("type", "string", "description", "The ID of the specific resource to show (e.g., SOR ID, pipeline ID). Required for detail pages.")
                        ), "required", List.of("page"))),

                tool("list_data_sources",
                        "List all registered Systems of Record (SORs) with their connectors and dataset counts. Use this to see what data sources are available before suggesting a pipeline.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),

                tool("list_connectors",
                        "List all connectors for a specific SOR (data source). Returns connector names, types, credential statuses, and dataset counts. Use this when you know the SOR and need to see its connectors.",
                        Map.of("type", "object", "properties", Map.of(
                                "sor_name", Map.of("type", "string", "description", "Human-readable name of the SOR to list connectors for")
                        ), "required", List.of("sor_name"))),

                tool("list_datasets",
                        "List datasets defined for a specific connector instance, a specific SOR, or all datasets in the tenant. Shows schema format, classification, and definition type.",
                        Map.of("type", "object", "properties", Map.of(
                                "connector_instance_id", Map.of("type", "string", "description", "Optional: filter datasets by connector instance ID"),
                                "sor_name", Map.of("type", "string", "description", "Optional: filter datasets by SOR name")
                        ), "required", List.of())),

	                tool("create_data_source",
	                        "REQUIRED to persist a new SOR. Nothing is saved until you call this tool. Register a new System of Record (data source) in the tenant. Returns the created SOR with its internal ID.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "name", Map.of("type", "string", "description", "Name of the data source (e.g., 'HR System', 'SAP Finance')"),
	                                "domain_name", Map.of("type", "string", "description", "Business domain name (compatibility input; canonical domain identity is preferred when known)"),
	                                "domain_id", Map.of("type", "string", "description", "Canonical domain ID when already known"),
	                                "description", Map.of("type", "string", "description", "Brief description of what this data source contains")
	                        ), "required", List.of("name"))),

                tool("create_connector",
                        "REQUIRED to persist new connector metadata. Nothing is saved until you call this tool. Create a new connector instance on an existing SOR. Pass sor_name, connector_name, connector_type, and config. If you already have secret references, pass them in credential_refs (preferred) or credentials (legacy compatibility). Do not claim the connection is ready until credential status is confirmed.",
                        Map.of("type", "object", "properties", Map.of(
                                "sor_name", Map.of("type", "string", "description", "Human-readable name of the SOR to add the connector to"),
                                "connector_name", Map.of("type", "string", "description", "Name for the new connector (e.g., 'Workday S3 Connector')"),
                                "connector_type", Map.of("type", "string", "description", "Type of connector (e.g., 'S3', 'File', 'JDBC', 'Kafka'). Matched to available connector definitions."),
                                "description", Map.of("type", "string", "description", "Brief description of the connector"),
                                "config", Map.of("type", "object", "properties", Map.of(), "description", "Configuration parameters (e.g., bucket_name, region, directory_path). Stored as config template."),
                                "credential_refs", Map.of("type", "object", "properties", Map.of(), "description", "Preferred: secret references already collected for this connector (e.g., gcp-sm://... or vault://...). Stored separately in the credential profile."),
                                "credentials", Map.of("type", "object", "properties", Map.of(), "description", "Legacy compatibility input. Only secret references should be provided here; raw secrets should go through the dedicated credential flow instead."),
                                "credentials_environment", Map.of("type", "string", "description", "Optional environment name for persisted credential refs (defaults to DEV).")
                        ), "required", List.of("sor_name", "connector_name"))),

                tool("list_domains",
                        "List all data domains for the current tenant. Returns domain names, descriptions, and business date configuration.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),

                tool("create_domain",
                        "REQUIRED to persist a new domain. Nothing is saved until you call this tool. Create a new data domain for the tenant. Optionally set the global business date.",
                        Map.of("type", "object", "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Domain name (e.g., 'HR', 'Finance')"),
                                "description", Map.of("type", "string", "description", "Brief description of the domain"),
                                "current_business_date", Map.of("type", "string", "description", "Global business date for this domain in YYYY-MM-DD format. Set to today's date if the user provides it."),
                                "business_date_grain", Map.of("type", "string", "enum", List.of("DAILY", "DAILY_BUSINESS_DAY", "WEEKLY", "MONTHLY"),
                                        "description", "How often the business date advances. Defaults to DAILY.")
                        ), "required", List.of("name"))),

                tool("create_dataset",
                        "REQUIRED to persist a new dataset. Nothing is saved until you call this tool. Create a new dataset on an existing SOR with schema, time dimension, and file naming convention. IMPORTANT: You MUST include schema_snapshot with the full fields array whenever you know or can infer the columns. If the user uploaded a file, shared column names, or discussed the schema, you MUST pass it here. Omitting schema when you have the information is a bug.",
                        Map.of("type", "object", "properties", Map.ofEntries(
                                entry("sor_name", Map.of("type", "string", "description", "Human-readable name of the SOR this dataset belongs to")),
                                entry("name", Map.of("type", "string", "description", "Dataset name (e.g., 'employees', 'transactions')")),
                                entry("description", Map.of("type", "string", "description", "Brief description of what the dataset contains")),
                                entry("classification", Map.of("type", "string", "description", "Data classification (e.g., 'PII', 'CONFIDENTIAL', 'PUBLIC')")),
                                entry("schema_snapshot", Map.of("type", "object", "properties", Map.of(), "description", "ALWAYS INCLUDE when you know the columns. Object with a 'fields' array. Each field: {\"name\": \"col_name\", \"type\": \"string|integer|date|decimal|boolean\", \"pii\": true/false}. Example: {\"fields\": [{\"name\": \"employee_id\", \"type\": \"string\"}, {\"name\": \"salary\", \"type\": \"decimal\", \"pii\": true}]}")),
                                entry("time_grain", Map.of("type", "string", "enum", List.of("DAILY", "DAILY_BUSINESS_DAY", "WEEKLY", "MONTHLY", "HOURLY", "REAL_TIME"),
                                        "description", "How often this dataset is produced")),
                                entry("current_asof", Map.of("type", "string", "description", "Starting business as-of date in YYYY-MM-DD format")),
                                entry("file_naming_pattern", Map.of("type", "string", "description", "File naming convention (e.g., 'employees_YYYYMMDD.csv')")),
                                entry("processing_datetime_source", Map.of("type", "string",
                                        "enum", List.of("filename_segment", "file_arrival_time", "airflow_run_time"),
                                        "description", "Where the per-row processing_datetime audit value comes from. `filename_segment` if encoded in the filename (e.g. employees_YYYYMMDD_YYYYMMDDHH24MISS.csv has both business_date and processing_datetime). `file_arrival_time` if the file's last-modified timestamp on object storage IS the processing_datetime (codegen emits Spark F.input_file_modification_time). `airflow_run_time` (default) for the Airflow DAG-run timestamp. SET THIS — when the user answers Phase 2g's question about processing_datetime, persist the answer here so codegen reads it instead of guessing.")),
                                entry("connector_instance_id", Map.of("type", "string", "description", "Optional: connector instance ID to link the dataset to")),
                                entry("partition_strategy", Map.of("type", "object", "properties", Map.of(), "description", "Physical partition strategy. Object with 'columns' array of partition column specs. Example: {\"columns\": [{\"name\": \"ingest_date\", \"transform\": \"identity\"}]}")),
                                entry("cluster_strategy", Map.of("type", "object", "properties", Map.of(), "description", "Physical cluster/sort strategy. Object with 'columns' array. Example: {\"columns\": [\"customer_id\", \"account_id\"]}")),
                                entry("write_mode", Map.of("type", "string", "enum", List.of("append", "overwrite", "merge"),
                                        "description", "Write semantics for this dataset. Defaults to 'append'.")),
                                entry("table_format_hint", Map.of("type", "string", "enum", List.of("PARQUET", "ICEBERG", "DELTA"),
                                        "description", "Preferred table format. Subject to runtime authority validation."))
                        ), "required", List.of("sor_name", "name"))),

	                tool("list_blueprints",
	                        "List available blueprint patterns, optionally filtered by category and surface. " +
	                        "By default returns active composition-surface blueprints (the safe set for adding to a pipeline). " +
	                        "Pass surface='orchestration_policy' to list version-policy blueprints, 'all' to see every active row, " +
	                        "or 'none' for non-addable/internal rows. Pass include_deprecated=true to also include rows with " +
	                        "status='deprecated' (marked '[DEPRECATED → {replacement}]'); non-deprecated deferred rows stay hidden.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "category", Map.of("type", "string",
	                                        "enum", List.of("INGESTION", "TRANSFORM", "MODELING", "DATA_QUALITY", "ORCHESTRATION", "DESTINATION"),
	                                        "description", "Filter by blueprint category"),
	                                "surface", Map.of("type", "string",
	                                        "enum", List.of("composition", "orchestration_policy", "none", "all"),
	                                        "default", "composition",
	                                        "description", "Add surface filter (ARCH-011). Default is 'composition' so chat mutation planning is composition-safe."),
	                                "include_deprecated", Map.of("type", "boolean",
	                                        "default", false,
	                                        "description", "When true, include status='deprecated' rows (including ones also marked deferred). Each deprecated blueprint is shown with a [DEPRECATED → {replacement}] suffix.")
	                        ), "required", List.of())),

	                tool("get_blueprint_detail",
	                        "Get full details of a specific blueprint including parameters, input/output ports, usage guidance, valid layers, composition role, emit strategy, schema behavior, and reuse capability.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "blueprint_key", Map.of("type", "string", "description", "The blueprint key (e.g., 'FileIngestion', 'BronzeToSilverCleaning')")
	                        ), "required", List.of("blueprint_key"))),

	                tool("list_dbt_assets",
	                        "List dbt assets currently indexed for a domain. Use this before proposing a new dbt-backed transform or modeling step when reuse may be appropriate.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "domain_id", Map.of("type", "string", "description", "Canonical domain ID to inspect"),
	                                "domain_name", Map.of("type", "string", "description", "Optional domain name if ID is not known")
	                        ), "required", List.of())),

	                tool("find_dbt_reuse_candidate",
	                        "Find a reuse candidate in the dbt asset registry for a given business concept and asset type. Call this before proposing a new dbt-backed transform or model whenever reuse might be viable. Returns the best matching asset plus the recommended emit strategy, semantic reasons, compatibility, and warnings so the planner can explain generate vs reuse decisions truthfully.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "domain_id", Map.of("type", "string", "description", "Canonical domain ID"),
	                                "domain_name", Map.of("type", "string", "description", "Optional domain name if ID is not known"),
	                                "business_concept", Map.of("type", "string", "description", "Business concept such as employee, customer, payment"),
	                                "asset_type", Map.of("type", "string", "enum", List.of("model", "snapshot"), "description", "dbt asset type to search"),
	                                "grain", Map.of("type", "string", "description", "Optional analytical grain"),
	                                "access_level", Map.of("type", "string", "description", "Optional access level"),
	                                "schema_signature", Map.of("type", "string", "description", "Optional compatibility signature"),
	                                "planning_context", Map.of("type", "object", "properties", Map.of(), "description", "Optional semantic planning context for reuse scoring, such as semantic_terms, contract_keys, lineage_inputs, group_by_columns, dimensions, measures, select_columns, or output_columns."),
	                                "emit_strategy", Map.of("type", "string", "enum", List.of("generate", "reuse_wrapper", "reference_only"), "description", "Optional requested emit strategy override")
	                        ), "required", List.of("business_concept", "asset_type"))),

                tool("get_composition",
                        "Get the current composition of a pipeline version - all blueprint instances and port wirings.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID to inspect")
                        ), "required", List.of("pipeline_id"))),

                // --------------------------------------------------------------
                // ADR 0025 / IMPL-ui-composition Phase 3 — the op-emitting
                // mutation tier. Each tool emits exactly ONE PlanOperation into
                // the per-turn op-queue and writes NOTHING to the canonical graph
                // or the Command Log; apply_plan is the sole canonical writer (P4).
                // Instances are addressed by NAME (the instanceRef). The legacy
                // direct-write composition tools below are KEPT (route-to-queue,
                // §7.16 #7) so existing prompts keep working.
                // --------------------------------------------------------------

                tool("add_blueprint_instance",
                        "Stage adding a composition step (blueprint instance) to the pipeline. Emits one op into the candidate (STAGING) graph; nothing is persisted until apply_plan. REQUIRED: reasoning (the rationale shown in the Plan Preview). NO initial params here — set them with set_params afterward. Rejects deprecated/deferred blueprints (BLUEPRINT_COMPAT_READ_ONLY) and orchestration-policy blueprints (STEP_REQUIRES_PIPELINE_ORCHESTRATION).",
                        Map.of("type", "object", "properties", Map.of(
                                "instance_name", Map.of("type", "string", "description", "The instance ref (a unique NAME for this step in the pipeline)"),
                                "blueprint_key", Map.of("type", "string", "description", "Blueprint key to instantiate. Must be on add_surface=composition."),
                                "storage_backend", Map.of("type", "string", "description", "Optional canonical storage backend override (DPC|GCP)."),
                                "lake_layer", Map.of("type", "string", "description", "Optional lake layer (bronze|silver|gold)."),
                                "lake_format", Map.of("type", "string", "description", "Optional lake format."),
                                "reasoning", Map.of("type", "string", "description", "REQUIRED. Why this step is being added (shown in the Plan Preview).")
                        ), "required", List.of("instance_name", "blueprint_key", "reasoning"))),

                tool("set_params",
                        "Stage a params update for an existing composition step (addressed by NAME). Emits one op into the candidate graph; nothing is persisted until apply_plan. Params are STRUCTURED key/values (no sub-LLM).",
                        Map.of("type", "object", "properties", Map.of(
                                "instance_name", Map.of("type", "string", "description", "The instance ref (NAME) to configure"),
                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Structured params to set/merge on the instance."),
                                "storage_backend", Map.of("type", "string", "description", "Optional canonical storage backend (DPC|GCP)."),
                                "lake_layer", Map.of("type", "string", "description", "Optional lake layer (bronze|silver|gold)."),
                                "lake_format", Map.of("type", "string", "description", "Optional lake format."),
                                "reasoning", Map.of("type", "string", "description", "Optional rationale shown in the Plan Preview.")
                        ), "required", List.of("instance_name", "params"))),

                tool("remove_instance",
                        "Stage removing a composition step (addressed by NAME) and its incident wirings. Emits one op into the candidate graph; nothing is persisted until apply_plan.",
                        Map.of("type", "object", "properties", Map.of(
                                "instance_name", Map.of("type", "string", "description", "The instance ref (NAME) to remove"),
                                "reasoning", Map.of("type", "string", "description", "Optional rationale shown in the Plan Preview.")
                        ), "required", List.of("instance_name"))),

                tool("remove_wire",
                        "Stage removing ONE port wiring between two composition steps (addressed by NAME). Emits one op into the candidate graph; nothing is persisted until apply_plan.",
                        Map.of("type", "object", "properties", Map.of(
                                "source_instance_name", Map.of("type", "string", "description", "Source step NAME"),
                                "source_port", Map.of("type", "string", "description", "EXACT output port name on the source"),
                                "target_instance_name", Map.of("type", "string", "description", "Target step NAME"),
                                "target_port", Map.of("type", "string", "description", "EXACT input port name on the target"),
                                "reasoning", Map.of("type", "string", "description", "Optional rationale shown in the Plan Preview.")
                        ), "required", List.of("source_instance_name", "source_port", "target_instance_name", "target_port"))),

                tool("rename_instance",
                        "Stage renaming a composition step and fixing all wiring references to it. Emits one op into the candidate graph; nothing is persisted until apply_plan.",
                        Map.of("type", "object", "properties", Map.of(
                                "instance_name", Map.of("type", "string", "description", "The current step NAME (old ref)"),
                                "new_name", Map.of("type", "string", "description", "The new step NAME (new ref)"),
                                "reasoning", Map.of("type", "string", "description", "Optional rationale shown in the Plan Preview.")
                        ), "required", List.of("instance_name", "new_name"))),

                tool("set_pipeline_setting",
                        "Stage a portless pipeline-level setting (orchestration policy etc., ADR 0020/0021). Emits one op into the candidate graph; folds into pipeline.update at apply_plan. Nothing is persisted until apply_plan.",
                        Map.of("type", "object", "properties", Map.of(
                                "settings", Map.of("type", "object", "properties", Map.of(), "description", "Pipeline-level settings to set (e.g. schedule_cron, catchup_enabled)."),
                                "reasoning", Map.of("type", "string", "description", "Optional rationale shown in the Plan Preview.")
                        ), "required", List.of("settings"))),

                // ARCH-018 canonical plan_* tool names. The propose_* tools below
                // are deprecated aliases that route to the same handlers so existing
                // chat sessions and prompts keep working through the transition.

                tool("plan_create_pipeline",
                        "Plan creating a new pipeline in DOMAIN scope. Creates a PREVIEW plan the user must approve via apply_plan; no product state is written until apply.",
                        Map.of("type", "object", "properties", Map.of(
                                "scope", Map.of("type", "string", "enum", List.of("DOMAIN"), "default", "DOMAIN", "description", "Planning scope. Only DOMAIN is supported; draft refs are plan-preview labels, not product IDs."),
                                "name", Map.of("type", "string", "description", "Pipeline name"),
                                "domain", Map.of("type", "string", "description", "Legacy compatibility input for business domain name"),
                                "domain_name", Map.of("type", "string", "description", "Preferred human-readable business domain name"),
                                "domain_id", Map.of("type", "string", "description", "Preferred canonical domain ID when already known"),
                                "description", Map.of("type", "string", "description", "Plain-English description of what this pipeline does"),
                                "default_storage_backend", Map.of("type", "string",
                                        "enum", List.of("DPC", "GCP"),
                                        "description", "Optional explicit pipeline storage backend override (ARCH-010). Omit for normal flows; PULSE derives the default from the active Runtime Authority and new instances inherit it unless explicitly overridden.")
                        ), "required", List.of("name", "description"))),

                tool("plan_add_step",
                        "Plan adding a composition step (blueprint instance) to a pipeline. Rejects orchestration policy blueprints with STEP_REQUIRES_PIPELINE_ORCHESTRATION and deprecated / deferred blueprints with BLUEPRINT_COMPAT_READ_ONLY. Creates a PREVIEW plan; no mutation until apply_plan.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "blueprint_key", Map.of("type", "string", "description", "Blueprint key to instantiate. Must be on add_surface=composition."),
                                "instance_name", Map.of("type", "string", "description", "Human-readable name for this step"),
                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Configuration parameters for the instance. For ingestion blueprints, include sor_id, connector_instance_id, and dataset_ids.")
                        ), "required", List.of("pipeline_id", "blueprint_key", "instance_name"))),

                tool("plan_wire_ports",
                        "Plan wiring an output port to an input port between two existing composition steps. Use EXACT port names from the blueprint, not generic 'input'/'output'.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "source_instance_name", Map.of("type", "string", "description", "Name of the source instance"),
                                "source_port", Map.of("type", "string", "description", "EXACT output port name (e.g., 'raw_output', 'cleaned_output')"),
                                "target_instance_name", Map.of("type", "string", "description", "Name of the target instance"),
                                "target_port", Map.of("type", "string", "description", "EXACT input port name (e.g., 'data_input', 'sensitive_data')")
                        ), "required", List.of("pipeline_id", "source_instance_name", "source_port", "target_instance_name", "target_port"))),

                tool("plan_set_step_params",
                        "Plan a generic-params update for a composition step. ARCH-018 forbidden-key blockers reject canonical storage / lake / table-contract fields (STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN), pipeline orchestration policy fields (STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION), DQ expectation fields (STEP_PARAMS_REQUIRES_DQ_TOOL), empty payloads (STEP_PARAMS_EMPTY_AFTER_NORMALIZATION), and deprecated / deferred instances (BLUEPRINT_COMPAT_READ_ONLY). Use the appropriate canonical tool for those categories instead.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "instance_name", Map.of("type", "string", "description", "Name of the instance to configure"),
                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Generic params to set or update. May not contain canonical storage / lake / table / orchestration / DQ keys.")
                        ), "required", List.of("pipeline_id", "instance_name", "params"))),

                tool("plan_configure_remote_pipeline_invocation",
                        "Plan configuring a RemotePipelineInvocation composition step. This is design-time only: it creates a PREVIEW plan and never starts, cancels, or polls broker runtime edges. The user must approve and call apply_plan before product state changes.",
                        Map.of("type", "object", "properties", Map.ofEntries(
                                Map.entry("pipeline_id", Map.of("type", "string", "description", "Target pipeline ID")),
                                Map.entry("version_id", Map.of("type", "string", "description", "Optional target version ID; defaults to the active version at apply time")),
                                Map.entry("instance_id", Map.of("type", "string", "description", "Optional existing RemotePipelineInvocation instance ID to update")),
                                Map.entry("instance_name", Map.of("type", "string", "description", "Name for a new RemotePipelineInvocation step")),
                                Map.entry("federated_tenant_key", Map.of("type", "string", "description", "Trusted peer tenant key from the validated trust binding")),
                                Map.entry("remote_target_ref", Map.of("type", "string", "description", "Mirrored remote target reference to invoke")),
                                Map.entry("environment", Map.of("type", "string", "description", "Runtime environment for the peer invocation")),
                                Map.entry("airflow_connection_id", Map.of("type", "string", "description", "Airflow Connection ID available in the executing environment for the peer Airflow API")),
                                Map.entry("remote_dag_id", Map.of("type", "string", "description", "Optional remote Airflow DAG ID; defaults from the runtime mirror during generation/projection")),
                                Map.entry("poll_interval_seconds", Map.of("type", "integer", "description", "Optional polling interval for remote DAG run facts")),
                                Map.entry("timeout_seconds", Map.of("type", "integer", "description", "Optional timeout for the remote DAG run")),
                                Map.entry("payload_template", Map.of("type", "object", "properties", Map.of(), "description", "Optional non-secret payload template passed to the remote target"))
                        ), "required", List.of("pipeline_id", "federated_tenant_key", "remote_target_ref", "environment", "airflow_connection_id"))),

                // -------------------- deprecated propose_* aliases --------------------

                tool("propose_create_pipeline",
                        "DEPRECATED alias for plan_create_pipeline. Creates a PREVIEW plan; no product-state writes. Prefer plan_create_pipeline for new code paths.",
                        Map.of("type", "object", "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Pipeline name"),
                                "domain", Map.of("type", "string", "description", "Legacy compatibility input for business domain name"),
                                "domain_name", Map.of("type", "string", "description", "Preferred human-readable business domain name"),
                                "domain_id", Map.of("type", "string", "description", "Preferred canonical domain ID when already known"),
                                "description", Map.of("type", "string", "description", "Plain-English description of what this pipeline does")
                        ), "required", List.of("name", "description"))),

                tool("propose_add_instance",
                        "DEPRECATED alias for plan_add_step. Creates a PREVIEW plan; no product-state writes.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "blueprint_key", Map.of("type", "string", "description", "Blueprint key to instantiate"),
                                "instance_name", Map.of("type", "string", "description", "Human-readable name for this step"),
                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Configuration parameters for the instance.")
                        ), "required", List.of("pipeline_id", "blueprint_key", "instance_name"))),

                tool("propose_wiring",
                        "DEPRECATED alias for plan_wire_ports. Creates a PREVIEW plan; no product-state writes.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "source_instance_name", Map.of("type", "string", "description", "Name of the source instance"),
                                "source_port", Map.of("type", "string", "description", "EXACT output port name"),
                                "target_instance_name", Map.of("type", "string", "description", "Name of the target instance"),
                                "target_port", Map.of("type", "string", "description", "EXACT input port name")
                        ), "required", List.of("pipeline_id", "source_instance_name", "source_port", "target_instance_name", "target_port"))),

                tool("propose_set_params",
                        "DEPRECATED alias for plan_set_step_params. Subject to the ARCH-018 forbidden-key blockers.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Target pipeline ID"),
                                "instance_name", Map.of("type", "string", "description", "Name of the instance to configure"),
                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Parameters to set or update")
                        ), "required", List.of("pipeline_id", "instance_name", "params"))),

                // --- AI Requirements Gathering Tools ---

                // T10 (WORKLIST-RESOLUTIONS §1): get_step_schema is the canonical
                // name; get_upstream_schema below is a DEPRECATED alias routing to
                // the same handler so existing chat sessions / prompts keep working.
                tool("get_step_schema",
                        "Get the inferred output schema feeding a step. Follows port wirings backwards to find the upstream instance's output schema, auto-resolving from dataset schema snapshots for ingestion nodes. Use this to understand what columns are available before configuring downstream transforms.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "The instance ID to get the feeding schema for")
                        ), "required", List.of("pipeline_id", "version_id", "instance_id"))),

                tool("get_upstream_schema",
                        "DEPRECATED alias for get_step_schema. Get the inferred output schema of an upstream step. Follows port wirings backwards to find the upstream instance's output schema, auto-resolving from dataset schema snapshots for ingestion nodes. Prefer get_step_schema for new code paths.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "The instance ID to get the upstream schema for")
                        ), "required", List.of("pipeline_id", "version_id", "instance_id"))),

                // ----------------------------------------------------------------
                // Phase 9 (IMPL-ui-composition; WORKLIST-RESOLUTIONS §1 T8/T9/T11-T14):
                // the new discovery + validation read tools. ALL read-only — they
                // never enqueue an op and never write canonical / Command-Log state.
                // ----------------------------------------------------------------

                tool("get_composition_overview",
                        "Read-only compact summary of a pipeline's active-version composition: step count, wire count, lake layers present, open/unwired ports, and the unresolved-schema count. Use this for a fast structural read before deeper inspection; call get_composition for the full per-step detail.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID to summarize")
                        ), "required", List.of("pipeline_id"))),

                tool("get_blueprint_op_list",
                        "Read-only: return the declared op-list / schema_behavior for a blueprint (ADR 0012 op-vocabulary, ADR 0011 schema authority). On this branch the seeded op-list (V153) may not be present yet; when absent the tool returns a clear 'op-list not yet seeded (V153 pending)' note rather than failing, and falls back to the blueprint's declared ports/params.",
                        Map.of("type", "object", "properties", Map.of(
                                "blueprint_key", Map.of("type", "string", "description", "The blueprint key (e.g., 'FileIngestion', 'BronzeToSilverCleaning')")
                        ), "required", List.of("blueprint_key"))),

                tool("validate_structure",
                        "Read-only structural validation of a pipeline version's composition graph (no LLM): detects orphan steps (no wires in or out), cycles, unreachable steps, and unwired required input ports. Returns {ok, issues: [{code, instance, message}]}.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Optional pipeline ID (its active version is used if version_id is omitted)"),
                                "version_id", Map.of("type", "string", "description", "Optional pipeline version ID (preferred when known)")
                        ), "required", List.of())),

                tool("validate_configuration",
                        "Read-only per-step configuration completeness check against each step's Blueprint contract (no LLM): missing required params, unwired required input ports, plus a table-contract / runtime punch-list folded in from check_table_contract_readiness. Returns {ok, issues: [{code, instance, message}]}.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Optional pipeline ID (its active version is used if version_id is omitted)"),
                                "version_id", Map.of("type", "string", "description", "Optional pipeline version ID (preferred when known)")
                        ), "required", List.of())),

                tool("validate_plan",
                        "Read-only Apply pre-flight over a pipeline version (the layer-3 check before apply_plan). INTERIM composition (WORKLIST-RESOLUTIONS §7.16 #15): validate_structure + validate_configuration + check_table_contract_readiness. The full deterministic-Builder compile pre-flight (ADR 0012/0013) is NOT on this branch yet; the result is clearly labelled INTERIM until it lands. Returns {ok, interim:true, structure, configuration, contractReadiness}.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Optional pipeline ID (its active version is used if version_id is omitted)"),
                                "version_id", Map.of("type", "string", "description", "Optional pipeline version ID (preferred when known)")
                        ), "required", List.of())),

                tool("validate_sql_expression",
                        "Read-only Calcite parse-validation of a derived-column expression, filter/router predicate, or sql-model body. Returns {valid, diagnostics:[{severity, code, message}], referencedColumns}. Parse-only/declared check on this branch; the schema-deriving CALCITE-PHASE-2 branch depends on spec #6 and is not yet wired here. Optionally pass input_schemas to also flag unknown column references.",
                        Map.of("type", "object", "properties", Map.of(
                                "expression", Map.of("type", "string", "description", "The SQL expression / predicate / sql-model body text to validate"),
                                "kind", Map.of("type", "string", "enum", List.of("value", "predicate"), "description", "Optional: 'value' for a derived-column expression, 'predicate' for a filter/router condition. Both parse the same way."),
                                "input_schemas", validateSqlInputSchemasProp()
                        ), "required", List.of("expression"))),

                tool("evaluate_dq_readiness",
                        "Evaluate the Data Quality readiness score for a pipeline version. Returns a score 0-100, a list of recommendations for improving DQ coverage, and reasoning for the score. Use this before presenting a pipeline proposal to the user.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID to evaluate")
                        ), "required", List.of("pipeline_id", "version_id"))),

                tool("suggest_dq_expectations",
                        "Get AI-suggested Data Quality expectations for a specific pipeline step. Returns a list of GX expectations with type, kwargs, severity, and reasoning for each suggestion. Use this to auto-populate DQ checks based on schema and data classification.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "The specific instance ID to get DQ suggestions for")
                        ), "required", List.of("pipeline_id", "version_id", "instance_id"))),

	                tool("configure_step_params",
	                        "Update configuration parameters for an existing pipeline step. This directly applies the parameter changes (not a proposal). Use when you need to set or update params like merge keys, filter conditions, aggregation columns, etc.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "The pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "The instance ID to configure"),
	                                "params", Map.of("type", "object", "properties", Map.of(), "description", "Configuration parameters to set or update on the instance")
	                        ), "required", List.of("pipeline_id", "version_id", "instance_id", "params"))),

	                tool("update_pipeline_orchestration",
	                        "Update pipeline-level orchestration policy for a version, including schedule, catchup, max active runs, depends_on_past, and policy blueprint configs.",
	                        Map.of("type", "object", "properties", Map.of(
	                                "pipeline_id", Map.of("type", "string", "description", "Pipeline ID"),
	                                "version_id", Map.of("type", "string", "description", "Pipeline version ID"),
	                                "schedule_cron", Map.of("type", "string", "description", "Cron or preset schedule"),
	                                "catchup_enabled", Map.of("type", "boolean", "description", "Whether backfill catchup is enabled"),
	                                "max_active_runs", Map.of("type", "integer", "description", "Maximum active runs"),
	                                "depends_on_past", Map.of("type", "boolean", "description", "Whether each run depends on the previous one"),
	                                "policy_configs", Map.of("type", "object", "properties", Map.of(), "description", "Optional map of orchestration policy blueprint settings")
	                        ), "required", List.of("pipeline_id", "version_id"))),

                tool("wire_ports",
                        "Stage wiring an output port to an input port between two composition steps. In the orchestration graph this emits ONE additive (dedup'd) wiring op into the candidate graph addressed by step NAME; nothing is persisted until apply_plan. REQUIRED: reasoning (shown in the Plan Preview). Use EXACT blueprint port names.",
                        Map.of("type", "object", "properties", Map.of(
                                "source_instance_name", Map.of("type", "string", "description", "Source step NAME (the instanceRef on the output side)"),
                                "source_port", Map.of("type", "string", "description", "EXACT output port name on the source step"),
                                "target_instance_name", Map.of("type", "string", "description", "Target step NAME (the instanceRef on the input side)"),
                                "target_port", Map.of("type", "string", "description", "EXACT input port name on the target step"),
                                "reasoning", Map.of("type", "string", "description", "REQUIRED. Why this wiring is being added (shown in the Plan Preview)."),
                                "version_id", Map.of("type", "string", "description", "Legacy direct-write only: the pipeline version ID (ignored by the staging path)."),
                                "source_instance_id", Map.of("type", "string", "description", "Legacy direct-write only: source instance ID."),
                                "source_port_name", Map.of("type", "string", "description", "Legacy direct-write only: source port name."),
                                "target_instance_id", Map.of("type", "string", "description", "Legacy direct-write only: target instance ID."),
                                "target_port_name", Map.of("type", "string", "description", "Legacy direct-write only: target port name.")
                        ), "required", List.of("source_instance_name", "source_port", "target_instance_name", "target_port", "reasoning"))),

                tool("remove_step",
                        "Remove a step (blueprint instance) from the pipeline composition. Also removes any associated port wirings. Use when restructuring a pipeline.",
                        Map.of("type", "object", "properties", Map.of(
                                "version_id", Map.of("type", "string", "description", "The pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "The instance ID to remove")
                        ), "required", List.of("version_id", "instance_id"))),

                tool("list_sink_targets",
                        "List sink targets (registered data destinations) for the current tenant. Sink targets are Systems of Record where pipelines publish output (BigQuery, Snowflake, S3 lake, Kafka, etc.). Use this before proposing a sink step or discussing publish destinations.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),

                tool("create_sink_target",
                        "Create a new sink target (a System of Record with metadata.registry_type='TARGET') so pipelines can publish to it. Requires a name and a canonical domain_id. Returns the created target.",
                        Map.of("type", "object", "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Human-readable target name (e.g., 'Analytics Warehouse')"),
                                "description", Map.of("type", "string", "description", "Brief description"),
                                "domain_id", Map.of("type", "string", "description", "Canonical domain ID for the target")
                        ), "required", List.of("name", "domain_id"))),

                tool("view_code_examples",
                        "Return curated code examples for a specific blueprint, so the agent can explain what the generated code looks like. Examples are template files stored in the PULSE codegen library and mapped to blueprints via codegen_hints.example_keys.",
                        Map.of("type", "object", "properties", Map.of(
                                "blueprint_key", Map.of("type", "string", "description", "Blueprint key (e.g., 'FileIngestion', 'BronzeToSilverCleaning')")
                        ), "required", List.of("blueprint_key"))),

                tool("get_connector_type_schema",
                        "REQUIRED before calling create_connector for any new connector. Returns the connection_spec (JSON Schema) for a connector type — the canonical field names + required fields + types that codegen will read from configTemplate. Use this so you don't pass made-up field names like 'source_path' when the real connector vocabulary is 'bucket'+'path_prefix' (S3) or 'folder_path'+'file_pattern' (SFTP) or 'host'+'port'+'sid' (Oracle JDBC).",
                        Map.of("type", "object", "properties", Map.of(
                                "connector_type", Map.of("type", "string", "description", "Connector type token (e.g., 'S3', 'SFTP', 'JDBC', 'Kafka', 'REST'). Matched against the seeded connector_definitions catalog by name.")
                        ), "required", List.of("connector_type"))),

                tool("request_credential_attach",
                        "Open the credential dialog mid-conversation so the user can attach secret values to a connector. Use this INSTEAD of telling the user 'have your platform team attach credentials'. PULSE is the dev-developer's tool to push keys into Secret Manager (in dev) — the chat agent should drive that flow, not punt it. Pass either connector_instance_id or connector_name; if connector_instance_id is draft:connector:n, the dialog opens only after apply_plan resolves the real connector id. NOTE: this tool only applies to external-SOR connectors (JDBC, Kafka, SFTP, REST, etc.). Object-storage connectors (S3-compatible) do NOT take user-entered credentials — auth is storage_backend-managed (workload identity / Kerberos).",
                        Map.of("type", "object", "properties", Map.of(
                                "connector_instance_id", Map.of("type", "string", "description", "Connector instance ID (preferred when known). May be draft:connector:n only inside a PREVIEW plan; frontend must not navigate to draft ids."),
                                "connector_name", Map.of("type", "string", "description", "Connector instance name (alternative when ID isn't known)"),
                                "environment", Map.of("type", "string", "enum", List.of("DEV", "INTEGRATION", "UAT", "PRODUCTION"), "default", "DEV", "description", "Environment to attach credentials for. Default DEV — chat-driven design works in dev only.")
                        ), "required", List.of())),

                tool("get_storage_paths",
                        "REQUIRED for object-storage (S3-compatible) connector creation. Resolves the bucket name + SOR-level path prefix from the tenant's storage_backends row + naming convention. The agent surfaces these resolved values to the user transparently before calling create_connector. Use this whenever you would otherwise have asked the user for bucket / path / region / endpoint — those are platform-resolved, never user-elicited. Direction selects the lifecycle folder: 'source' uses SRC (incoming / vendor drops), 'sink' uses outgoing_extracts (PULSE-produced outputs).",
                        Map.of("type", "object", "properties", Map.of(
                                "sor_id", Map.of("type", "string", "description", "SOR ID this connector belongs to (e.g., the MSP SOR for an MSP file ingestion connector)"),
                                "environment", Map.of("type", "string", "enum", List.of("LOCAL", "DEV", "INTEGRATION", "UAT", "PRODUCTION"), "default", "DEV", "description", "Storage backend environment to resolve against"),
                                "backend", Map.of("type", "string", "enum", List.of("DPC", "GCP"), "description", "Storage backend choice for this pipeline leg (the DPC-or-GCP question)"),
                                "direction", Map.of("type", "string", "enum", List.of("source", "sink"), "default", "source", "description", "Connector direction. source → SRC folder under storage_root_files. sink → outgoing_extracts folder.")
                        ), "required", List.of("sor_id", "backend"))),

                tool("apply_dq_expectations",
                        "REQUIRED to commit DQ rules to a DQValidator instance. After calling `suggest_dq_expectations` and confirming the rules with the user, call THIS tool to persist them to the canonical `SubPipelineInstance.dqExpectations` JSONB column — that's where GxCodeGenerator reads from at codegen time. Without calling this tool, suggested DQ rules remain in chat history only and never reach runtime. NEVER use `remove_step` + `propose_add_instance` as a workaround for applying DQ rules — that destroys the instance + its wirings AND still doesn't apply the rules.",
                        Map.of("type", "object", "properties", Map.of(
                                "pipeline_id", Map.of("type", "string", "description", "Pipeline ID"),
                                "version_id", Map.of("type", "string", "description", "Pipeline version ID"),
                                "instance_id", Map.of("type", "string", "description", "DQValidator instance ID to attach rules to"),
                                "expectations", Map.of("type", "array",
                                        "description", "List of GX expectations. Each entry: {type, kwargs, severity}. Example: [{\"type\":\"ExpectColumnValuesToNotBeNull\",\"kwargs\":{\"column\":\"loan_id\"},\"severity\":\"critical\"}]",
                                        "items", Map.of("type", "object", "properties", Map.of()))
                        ), "required", List.of("pipeline_id", "version_id", "instance_id", "expectations"))),

                // ARCH-009: apply_plan is the sole generic mutating chat tool.
                // All plan-producing tools (plan_*, propose_*) leave product
                // state untouched until the user approves and apply_plan is
                // invoked with the approved plan id.
                tool("apply_plan",
                        "Apply an APPROVED chat plan by id. This is the only generic chat tool that mutates product state. " +
                        "It reads the persisted plannedCommands from the plan and executes them deterministically. " +
                        "Returns a structured tool_result with mutationApplied=true and the executed commandIds; on failure or " +
                        "non-APPROVED status the call is rejected without side effects.",
                        Map.of("type", "object", "properties", Map.of(
                                "plan_id", Map.of("type", "string",
                                        "description", "The plan id to apply. The plan must be in status=APPROVED and must reference the current chat session.")
                        ), "required", List.of("plan_id"))),

                // ARCH-018 dependency-backed read-only contract / preview tools.
                // All side-effect-free; do not create a plan and do not mutate
                // product state. The LLM may call any of these at any point.

                tool("preview_dataset_landing",
                        "Read-only preview of the canonical landing contract for a dataset, resolved against a deploy environment. " +
                        "Returns the landing / rejected / archive / outgoing relative paths and (when bindings are resolved) the absolute object-store URIs. " +
                        "Source of truth: ARCH-005 / 015 StorageAuthorityFacade.",
                        Map.of("type", "object", "properties", Map.of(
                                "dataset_id", Map.of("type", "string", "description", "Dataset id to inspect."),
                                "environment", Map.of("type", "string",
                                        "description", "Deploy environment for URI resolution (e.g. dev, integration, uat, prod).",
                                        "default", "dev")
                        ), "required", List.of("dataset_id"))),

                tool("preview_table_contract",
                        "Read-only preview of the table contracts produced by a SubPipelineInstance, resolved against a deploy environment. " +
                        "Returns each contract's logical metadata (layer, tableRole, tableName, catalogKind, tableFormat, relativeStoragePath) plus " +
                        "the resolved objectStoreUri, catalogIdentifier, and projectionHash. " +
                        "Source of truth: ARCH-005 / 015 TableContractService + TableContractProjectionService.",
                        Map.of("type", "object", "properties", Map.of(
                                "instance_id", Map.of("type", "string", "description", "Producing SubPipelineInstance id."),
                                "version_id", Map.of("type", "string", "description", "Pipeline version id."),
                                "environment", Map.of("type", "string",
                                        "description", "Deploy environment for URI / catalog resolution.",
                                        "default", "dev")
                        ), "required", List.of("instance_id", "version_id"))),

                tool("preview_runtime_projection",
                        "Read-only preview of the active runtime projection for a (package, target, environment), composed with a drift check. " +
                        "Surfaces projectionHash, runtimePersona, readinessBlockers, resolvedEntrypoints (including broker edge bindings), " +
                        "and whether the stored hash still matches the current state. " +
                        "Source of truth: ARCH-006 RuntimeProjectionService.",
                        Map.of("type", "object", "properties", Map.of(
                                "package_id", Map.of("type", "string", "description", "Package id to inspect."),
                                "target_id", Map.of("type", "string", "description", "Deployment target id."),
                                "environment", Map.of("type", "string", "description", "Deploy environment.",
                                        "default", "dev")
                        ), "required", List.of("package_id", "target_id"))),

                tool("preview_runtime_authority",
                        "Read-only preview of the active runtime authority: persona, allowed targets, storage backends, " +
                        "catalogs, materializations, and physical design authority (partition transforms, layout strategies, " +
                        "DDL executors/dialects, DDL limits). Source of truth: ARCH-004 RuntimeAuthorityService.",
                        Map.of("type", "object", "properties", Map.of(), "required", List.of())),

                tool("check_table_contract_readiness",
                        "Read-only check: are all table contracts for a pipeline version present and active? " +
                        "Returns {ready, activeContractCount, totalContracts, blockers: [{code, message}]}. " +
                        "Now includes physical design completeness checks (partitionSpec, catalogKind presence). " +
                        "Source of truth: ARCH-005 / 015 StorageAuthorityFacade.getContractReadiness.",
                        Map.of("type", "object", "properties", Map.of(
                                "version_id", Map.of("type", "string", "description", "Pipeline version id.")
                        ), "required", List.of("version_id"))),

                tool("get_package_contract",
                        "Read-only access to the canonical sourcePackageManifest.v2 stored on Package.metadata.packageManifest. " +
                        "Returns the manifest jsonb verbatim, including tableContractRefs, runtimeAuthority, entrypointCatalog (with brokerInvocations), " +
                        "git provenance, and the file inventory. " +
                        "Source of truth: ARCH-006 PackageService.buildManifest.",
                        Map.of("type", "object", "properties", Map.of(
                                "package_id", Map.of("type", "string", "description", "Package id to inspect.")
                        ), "required", List.of("package_id"))),

                tool("check_deploy_readiness",
                        "Read-only composition of every deploy preflight check (21 checks at the deployment-preflight-result.v1 schema). " +
                        "Returns {schemaVersion, status: PASS|FAIL, checks: [{code, status, message, evidence}], blockers: [code, ...]}. " +
                        "This is the ONLY canonical deploy-readiness chat read. " +
                        "Source of truth: ARCH-006 / 016 DeploymentPreflightService.check.",
                        Map.of("type", "object", "properties", Map.of(
                                "package_id", Map.of("type", "string", "description", "Package id to deploy."),
                                "target_id", Map.of("type", "string", "description", "Deployment target id.")
                        ), "required", List.of("package_id", "target_id"))),

                tool("get_workspace_context",
                        "Read-only WORKSPACE-scope lookup. Returns the resolved git repo + active workspace status (or 'none') for a pipeline version, " +
                        "plus a scopeHint ('WORKSPACE' when a workspace is active, 'PIPELINE' otherwise) to advise the LLM which scope's tools to use. " +
                        "Source of truth: ARCH-016 DeveloperWorkspaceService.getWorkspaceContext.",
                        Map.of("type", "object", "properties", Map.of(
                                "version_id", Map.of("type", "string", "description", "Pipeline version id.")
                        ), "required", List.of("version_id"))),

                tool("derive_contract_impact",
                        "Read-only ARCH-018 contractImpact hint helper. Inspects the version's table contracts and (optionally) the runtime projection " +
                        "for a (package, target, environment) and returns one of: NONE, SCHEMA_STALE, TABLE_CONTRACT_STALE, RUNTIME_PROJECTION_STALE, " +
                        "READINESS_RECHECK_REQUIRED. Use after a plan_set_step_params apply, or before deploy, to advise the user about freshness.",
                        Map.of("type", "object", "properties", Map.of(
                                "version_id", Map.of("type", "string", "description", "Pipeline version id."),
                                "package_id", Map.of("type", "string", "description", "Optional: package id to check projection drift against."),
                                "target_id", Map.of("type", "string", "description", "Optional: deployment target id for projection drift."),
                                "environment", Map.of("type", "string", "description", "Deploy environment.",
                                        "default", "dev")
                        ), "required", List.of("version_id"))),

                tool("derive_dataset_schema",
                        "Discover the schema of a dataset from a JDBC table, SQL query, or uploaded sample. " +
                        "Returns column names, types, nullability, and PII/confidential classification. " +
                        "Requires valid credentials for JDBC table/query discovery (not needed for sample upload). " +
                        "After discovery, use create_dataset_from_discovery to register the dataset.",
                        Map.of("type", "object", "properties", Map.of(
                                "connector_instance_id", Map.of("type", "string",
                                        "description", "The connector instance ID to discover schema from"),
                                "source_type", Map.of("type", "string",
                                        "enum", List.of("table", "query", "sample"),
                                        "description", "Discovery source: 'table' for JDBC table, 'query' for SQL query, 'sample' for uploaded data"),
                                "table_name", Map.of("type", "string",
                                        "description", "Table name (required when source_type=table)"),
                                "query", Map.of("type", "string",
                                        "description", "SQL query (required when source_type=query)"),
                                "sample_data", Map.of("type", "string",
                                        "description", "Sample data content (required when source_type=sample)"),
                                "sample_format", Map.of("type", "string",
                                        "enum", List.of("CSV", "JSON"),
                                        "description", "Format of sample data (required when source_type=sample)"),
                                "environment", Map.of("type", "string",
                                        "description", "Environment for credential lookup (default: dev)")
                        ), "required", List.of("connector_instance_id", "source_type"))),

                tool("create_dataset_from_discovery",
                        "Register a dataset using schema discovered by derive_dataset_schema. " +
                        "Pass the fields, classification, and discovery proof from the discovery result. " +
                        "This persists the dataset with full provenance metadata.",
                        Map.of("type", "object", "properties", createDatasetFromDiscoveryProps(),
                                "required", List.of("connector_instance_id", "name", "fields")))
        );
    }

    private static Map<String, Object> createDatasetFromDiscoveryProps() {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("connector_instance_id", Map.of("type", "string", "description", "Connector instance ID"));
        props.put("name", Map.of("type", "string", "description", "Dataset name (e.g., 'Loan_Master')"));
        props.put("description", Map.of("type", "string", "description", "Brief description"));
        props.put("fields", Map.of("type", "array",
                "description", "Schema fields from discovery result. Each entry: {name, type, nullable, pii, description}.",
                "items", Map.of("type", "object", "properties", Map.of())));
        props.put("classification", Map.of("type", "string", "description", "Data classification from discovery (PII, CONFIDENTIAL, INTERNAL)"));
        props.put("discovery_method", Map.of("type", "string", "description", "Discovery method (TABLE_DISCOVERY, QUERY_DISCOVERY, SAMPLE_UPLOAD)"));
        props.put("discovery_proof", Map.of("type", "object", "properties", Map.of(), "description", "Discovery provenance metadata"));
        props.put("time_grain", Map.of("type", "string", "description", "How often this dataset is produced"));
        props.put("asof_column_name", Map.of("type", "string", "description", "Column name used as the business as-of date"));
        props.put("current_asof", Map.of("type", "string", "description", "Current as-of date in YYYY-MM-DD format"));
        props.put("asof_timezone", Map.of("type", "string", "description", "Timezone for the as-of date"));
        props.put("custom_sql", Map.of("type", "string", "description", "SQL query for query-based discovery"));
        props.put("source_tables", Map.of("type", "array",
                "description", "Source table names for table-based discovery.",
                "items", Map.of("type", "string")));
        return props;
    }

    /**
     * The {@code input_schemas} array schema for {@code validate_sql_expression}.
     * Built explicitly (rather than deeply-nested inline {@code Map.of}) so the
     * column-schema nesting stays readable and the
     * {@link ChatToolDefinitionLintTest} object/array invariants are obvious:
     * every {@code type:"object"} has {@code properties}; every
     * {@code type:"array"} has {@code items}.
     */
    private static Map<String, Object> validateSqlInputSchemasProp() {
        Map<String, Object> columnObject = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Column name"),
                        "type", Map.of("type", "string", "description", "Column type")));
        Map<String, Object> columnsArray = Map.of(
                "type", "array",
                "description", "Columns available on this port.",
                "items", columnObject);
        Map<String, Object> schemaObject = Map.of(
                "type", "object",
                "properties", Map.of(
                        "port_name", Map.of("type", "string", "description", "Input port name"),
                        "columns", columnsArray));
        return Map.of(
                "type", "array",
                "description", "Optional input-port schemas to resolve column references against. "
                        + "Each entry: {port_name, columns:[{name, type}]}.",
                "items", schemaObject);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }
}
