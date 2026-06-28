package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import com.pulse.codegen.service.CodegenExampleService;
import com.pulse.codegen.service.DbtAssetRegistryService;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.DqReadinessService;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.service.SchemaDiscoveryService;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutor.class);

    private final SystemOfRecordRepository sorRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final DomainRepository domainRepo;
    private final ConnectorDefinitionRepository connDefRepo;
    private final CredentialProfileRepository credRepo;
    private final DatasetRepository datasetRepo;
    private final BlueprintRepository blueprintRepo;
    private final PipelineRepository pipelineRepo;
    private final CompositionService compositionService;
    private final DqReadinessService dqReadinessService;
    private final ObjectMapper objectMapper;
    private final com.pulse.pipeline.service.PipelineService pipelineService;
    private final ChatService chatService;
    private final DbtAssetRegistryService dbtAssetRegistryService;
    private final CodegenExampleService codegenExampleService;
    private final StorageBackendRepository storageBackendRepo;
    private final SubPipelineInstanceRepository instanceRepo;
    private final AuthorizationPolicyService authPolicy;
    private final ActorResolverService actorResolver;
    private final com.pulse.command.service.PlanService planService;
    private final com.pulse.pipeline.service.DqExpectationService dqExpectationService;
    private final ChatReadToolHandler readToolHandler;
    private final SchemaDiscoveryService schemaDiscoveryService;
    private final ChatValidationToolService validationToolService;

    public ChatToolExecutor(SystemOfRecordRepository sorRepo,
                            ConnectorInstanceRepository ciRepo,
                            DomainRepository domainRepo,
                            ConnectorDefinitionRepository connDefRepo,
                            CredentialProfileRepository credRepo,
                            DatasetRepository datasetRepo,
                            BlueprintRepository blueprintRepo,
                            PipelineRepository pipelineRepo,
                            CompositionService compositionService,
                            DqReadinessService dqReadinessService,
                            ObjectMapper objectMapper,
                            com.pulse.pipeline.service.PipelineService pipelineService,
                            DbtAssetRegistryService dbtAssetRegistryService,
                            CodegenExampleService codegenExampleService,
                            StorageBackendRepository storageBackendRepo,
                            SubPipelineInstanceRepository instanceRepo,
                            @org.springframework.context.annotation.Lazy ChatService chatService,
                            AuthorizationPolicyService authPolicy,
                            ActorResolverService actorResolver,
                            com.pulse.command.service.PlanService planService,
                            com.pulse.pipeline.service.DqExpectationService dqExpectationService,
                            ChatReadToolHandler readToolHandler,
                            SchemaDiscoveryService schemaDiscoveryService,
                            ChatValidationToolService validationToolService) {
        this.sorRepo = sorRepo;
        this.ciRepo = ciRepo;
        this.domainRepo = domainRepo;
        this.connDefRepo = connDefRepo;
        this.credRepo = credRepo;
        this.datasetRepo = datasetRepo;
        this.blueprintRepo = blueprintRepo;
        this.pipelineRepo = pipelineRepo;
        this.compositionService = compositionService;
        this.dqReadinessService = dqReadinessService;
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.dbtAssetRegistryService = dbtAssetRegistryService;
        this.codegenExampleService = codegenExampleService;
        this.storageBackendRepo = storageBackendRepo;
        this.instanceRepo = instanceRepo;
        this.chatService = chatService;
        this.authPolicy = authPolicy;
        this.actorResolver = actorResolver;
        this.planService = planService;
        this.dqExpectationService = dqExpectationService;
        this.readToolHandler = readToolHandler;
        this.schemaDiscoveryService = schemaDiscoveryService;
        this.validationToolService = validationToolService;
    }

    /**
     * Phase 3 helper: route the chat tool's mutation through the same
     * {@link AuthorizationPolicyService} the UI controllers use, with
     * the AGENT caller surface so audit can attribute the decision.
     * Returns null when allowed; returns the error string the agent
     * should surface to the user when denied.
     */
    private String enforceAgentMutation(PulseAction action, String tenantId, String environment) {
        CallerContext caller = actorResolver.resolve(CallerSurface.AGENT, tenantId);
        ActionContext target = environment == null
                ? ActionContext.forTenant(tenantId)
                : ActionContext.forTenantAndEnv(tenantId, environment);
        PolicyDecision decision = authPolicy.check(caller, action, target);
        if (decision.allowed()) {
            return null;
        }
        return "Agent action denied (" + decision.denyReason() + "). "
                + "The active user is not authorized for " + action
                + " in tenant " + tenantId + ".";
    }

    private String navigateUi(Map<String, Object> args) {
        String page = (String) args.get("page");
        String resourceId = (String) args.get("resource_id");
        return "Navigated to " + page + (resourceId != null ? " (" + resourceId + ")" : "");
    }

    public String getNavigationPath(String toolName, Map<String, Object> toolArgs, String result) {
        if ("navigate_ui".equals(toolName)) {
            return null; // Handled separately via SSE in ChatService
        }

        // --- Data source tools → /producers or /producers/{id} ---
        if ("list_domains".equals(toolName) || "create_domain".equals(toolName)) {
            return "/producers";
        }
        if ("list_data_sources".equals(toolName)) {
            return "/producers";
        }
        if ("create_data_source".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_id: (\\S+)]").matcher(result);
            return matcher.find() ? "/producers/" + matcher.group(1) : "/producers";
        }
        if ("create_dataset".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_sor_id: (\\S+)]").matcher(result);
            return matcher.find() ? "/producers/" + matcher.group(1) : "/producers";
        }
        if ("create_connector".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_sor_id: (\\S+)]").matcher(result);
            return matcher.find() ? "/producers/" + matcher.group(1) : "/producers";
        }
        if ("list_connectors".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_sor_id: (\\S+)]").matcher(result);
            return matcher.find() ? "/producers/" + matcher.group(1) : "/producers";
        }
        if ("list_datasets".equals(toolName)) {
            String ciId = toolArgs != null ? (String) toolArgs.get("connector_instance_id") : null;
            if (ciId != null) {
                var ci = ciRepo.findById(ciId).orElse(null);
                if (ci != null) return "/producers/" + ci.getSorId();
            }
            String sorName = toolArgs != null ? (String) toolArgs.get("sor_name") : null;
            if (sorName != null) {
                var matcher = java.util.regex.Pattern.compile("\\[internal_sor_id: (\\S+)]").matcher(result);
                if (matcher.find()) return "/producers/" + matcher.group(1);
            }
            return "/producers";
        }

        if ("request_credential_attach".equals(toolName) || "apply_plan".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile(
                    "\\[internal_attach_credential: sor_id=(\\S+) connector_instance_id=(\\S+) environment=(\\S+)]")
                    .matcher(result);
            if (matcher.find()) {
                if (matcher.group(2).startsWith("draft:")) {
                    return null;
                }
                return "/producers/" + matcher.group(1)
                        + "?attach_credential=" + matcher.group(2)
                        + "&env=" + matcher.group(3);
            }
            return null;
        }

        // --- Blueprint introspection — DO NOT navigate. These tools are
        //     informational; the agent uses them to look up blueprint
        //     specs while the user is mid-pipeline-build. Auto-navigating
        //     to /blueprints would yank the user off the DAG view in the
        //     middle of construction (observed during the #11 E2E run:
        //     6 sequential get_blueprint_detail calls cleared the live DAG
        //     view 6 times). Stay where the user is.
        if ("list_blueprints".equals(toolName) || "get_blueprint_detail".equals(toolName)) {
            return null;
        }

        // --- Pipeline tools → /pipelines/{id} ---
        if ("propose_create_pipeline".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_id: (\\S+)]").matcher(result);
            return matcher.find() ? "/pipelines/" + matcher.group(1) : "/pipelines";
        }
        if ("propose_add_instance".equals(toolName) || "propose_wiring".equals(toolName) || "propose_set_params".equals(toolName)) {
            var matcher = java.util.regex.Pattern.compile("\\[internal_pipeline_id: (\\S+)]").matcher(result);
            if (matcher.find()) return "/pipelines/" + matcher.group(1);
        }
        if ("get_composition".equals(toolName) || "configure_step_params".equals(toolName)) {
            String pid = toolArgs != null ? (String) toolArgs.get("pipeline_id") : null;
            if (pid != null) return "/pipelines/" + pid;
        }
        if ("evaluate_dq_readiness".equals(toolName) || "suggest_dq_expectations".equals(toolName)
                || "get_step_schema".equals(toolName) || "get_upstream_schema".equals(toolName)) {
            String pid = toolArgs != null ? (String) toolArgs.get("pipeline_id") : null;
            if (pid != null) return "/pipelines/" + pid;
        }
        if ("list_dbt_assets".equals(toolName) || "find_dbt_reuse_candidate".equals(toolName)) {
            String pid = toolArgs != null ? (String) toolArgs.get("pipeline_id") : null;
            if (pid != null) return "/pipelines/" + pid;
            return "/pipelines";
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    /**
     * Read-tools-see-staging seam (IMPL-ui-composition Phase 3; 06 §B.3). When
     * the graph driver passes a non-null {@code staging} graph (= canonical clone
     * + the ops queued so far this turn), composition read tools answer from the
     * STAGING view rather than canonical, so a multi-tool turn stays
     * self-consistent. Non-composition tools are unaffected and delegate to
     * {@link #execute(String, Map, String)}.
     */
    public String executeWithStaging(String toolName, Map<String, Object> args, String tenantId,
                                     com.pulse.chat.orchestration.StagingGraph staging) {
        if (staging != null && "get_composition".equals(toolName)) {
            return renderStagingComposition(staging);
        }
        return execute(toolName, args, tenantId);
    }

    private String renderStagingComposition(com.pulse.chat.orchestration.StagingGraph staging) {
        StringBuilder sb = new StringBuilder();
        sb.append("Candidate (STAGING) composition — not yet applied.\n\n");
        sb.append("Steps (").append(staging.instances().size()).append("):\n");
        for (var i : staging.instances()) {
            sb.append("- ").append(i.ref()).append(" [").append(i.blueprintKey()).append("]");
            if (i.lakeLayer() != null) sb.append(" layer=").append(i.lakeLayer());
            sb.append("\n");
        }
        sb.append("\nWirings (").append(staging.wirings().size()).append("):\n");
        for (var w : staging.wirings()) {
            sb.append("- ").append(w.sourceRef()).append(".").append(w.sourcePort())
                    .append(" -> ").append(w.targetRef()).append(".").append(w.targetPort()).append("\n");
        }
        return sb.toString();
    }

    public String execute(String toolName, Map<String, Object> args, String tenantId) {
        log.info(">>> TOOL CALL [{}] tenant={} args={}", toolName, tenantId, args);
        try {
            String result = switch (toolName) {
                case "navigate_ui" -> navigateUi(args);
                case "list_data_sources" -> listDataSources(tenantId);
                case "create_data_source" -> createDataSource(tenantId, args);
                case "list_domains" -> listDomains(tenantId);
                case "create_domain" -> createDomain(tenantId, args);
                case "create_connector" -> createConnector(tenantId, args);
                case "list_connectors" -> listConnectors(tenantId, (String) args.get("sor_name"));
                case "create_dataset" -> createDataset(tenantId, args);
                case "list_datasets" -> listDatasets(tenantId, (String) args.get("connector_instance_id"), (String) args.get("sor_name"));
                case "list_blueprints" -> listBlueprints(
                        (String) args.get("category"),
                        (String) args.get("surface"),
                        Boolean.TRUE.equals(args.get("include_deprecated")));
                case "get_blueprint_detail" -> getBlueprintDetail((String) args.get("blueprint_key"));
                case "list_dbt_assets" -> listDbtAssets(tenantId, args);
                case "find_dbt_reuse_candidate" -> findDbtReuseCandidate(tenantId, args);
                case "get_composition" -> getComposition((String) args.get("pipeline_id"));
                // ARCH-018 canonical plan_* names (propose_* aliases follow).
                case "plan_create_pipeline" -> proposeCreatePipeline(tenantId, args);
                case "plan_add_step" -> planAddStep(args);
                case "plan_wire_ports" -> proposeWiring(args);
                case "plan_set_step_params" -> planSetStepParams(args);
                case "plan_configure_remote_pipeline_invocation" -> planConfigureRemotePipelineInvocation(tenantId, args);
                case "propose_create_pipeline" -> proposeCreatePipeline(tenantId, args);
                case "propose_add_instance" -> planAddStep(args);
                case "propose_wiring" -> proposeWiring(args);
                case "propose_set_params" -> planSetStepParams(args);
                // T10: get_step_schema is canonical; get_upstream_schema is the
                // deprecated alias routing to the same handler.
                case "get_step_schema", "get_upstream_schema" -> getStepSchema(args);
                // Phase 9 discovery + validation read tools (all read-only).
                case "get_composition_overview" -> validationToolService.getCompositionOverview((String) args.get("pipeline_id"));
                case "get_blueprint_op_list" -> validationToolService.getBlueprintOpList((String) args.get("blueprint_key"));
                case "validate_structure" -> validationToolService.validateStructure(args);
                case "validate_configuration" -> validationToolService.validateConfiguration(args, tenantId);
                case "validate_plan" -> validationToolService.validatePlan(args, tenantId);
                case "validate_sql_expression" -> validationToolService.validateSqlExpression(args);
                case "evaluate_dq_readiness" -> evaluateDqReadiness(args);
                case "suggest_dq_expectations" -> suggestDqExpectations(args);
                case "configure_step_params" -> configureStepParams(args);
                case "update_pipeline_orchestration" -> updatePipelineOrchestration(tenantId, args);
                case "wire_ports" -> wirePorts(args);
                case "remove_step" -> removeStep(args);
                case "list_sink_targets" -> listSinkTargets(tenantId);
                case "create_sink_target" -> createSinkTarget(tenantId, args);
                case "view_code_examples" -> viewCodeExamples((String) args.get("blueprint_key"));
                case "get_connector_type_schema" -> getConnectorTypeSchema((String) args.get("connector_type"));
                case "request_credential_attach" -> requestCredentialAttach(tenantId, args);
                case "get_storage_paths" -> getStoragePaths(tenantId, args);
                case "apply_dq_expectations" -> applyDqExpectations(args);
                case "apply_plan" -> applyPlan((String) args.get("plan_id"));
                // ARCH-018 dependency-backed read-only tools. All side-effect-free.
                case "preview_dataset_landing" -> readToolHandler.previewDatasetLanding(tenantId, args);
                case "preview_table_contract" -> readToolHandler.previewTableContract(tenantId, args);
                case "preview_runtime_projection" -> readToolHandler.previewRuntimeProjection(tenantId, args);
                case "preview_runtime_authority" -> readToolHandler.previewRuntimeAuthority(tenantId, args);
                case "check_table_contract_readiness" -> readToolHandler.checkTableContractReadiness(tenantId, args);
                case "get_package_contract" -> readToolHandler.getPackageContract(tenantId, args);
                case "check_deploy_readiness" -> readToolHandler.checkDeployReadiness(tenantId, args);
                case "get_workspace_context" -> readToolHandler.getWorkspaceContext(tenantId, args);
                case "derive_contract_impact" -> readToolHandler.deriveContractImpact(tenantId, args);
                case "derive_dataset_schema" -> deriveDatasetSchema(tenantId, args);
                case "create_dataset_from_discovery" -> createDatasetFromDiscovery(tenantId, args);
                default -> "Unknown tool: " + toolName;
            };
            log.info("<<< TOOL RESULT [{}] status=OK result={}", toolName,
                    result.length() > 300 ? result.substring(0, 300) + "..." : result);
            return result;
        } catch (Exception e) {
            log.error("<<< TOOL RESULT [{}] status=ERROR error={}", toolName, e.getMessage(), e);
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }

    private String listDataSources(String tenantId) {
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        StringBuilder sb = new StringBuilder();
        for (var sor : sors) {
            var connectors = ciRepo.findBySorIdOrderByNameAsc(sor.getId());
            sb.append("## ").append(sor.getName()).append(" (").append(sor.getDomainName()).append(")\n");
            sb.append("[internal_id: ").append(sor.getId()).append("]\n");
            if (sor.getDescription() != null) sb.append(sor.getDescription()).append("\n");
            sb.append("Connectors:\n");
            for (var ci : connectors) {
                long dsCount = datasetRepo.countByConnectorInstanceId(ci.getId());
                sb.append("  - ").append(ci.getName()).append(" [internal_id: ").append(ci.getId()).append("] ")
                        .append(dsCount).append(" dataset(s)\n");
            }
            sb.append("\n");
        }
        if (sors.isEmpty()) sb.append("No data sources registered for this tenant.\n");
        sb.append("\n[NOTE: internal_id values are for your tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private String listDomains(String tenantId) {
        var domains = domainRepo.findByTenantIdOrderByNameAsc(tenantId);
        if (domains.isEmpty()) return "No domains found for this tenant.";
        StringBuilder sb = new StringBuilder();
        sb.append("**Domains:**\n\n");
        for (var d : domains) {
            sb.append("- **").append(d.getName()).append("**");
            if (d.getDescription() != null) sb.append(" — ").append(d.getDescription());
            if (d.getCurrentBusinessDate() != null) sb.append(" (business date: ").append(d.getCurrentBusinessDate()).append(")");
            else sb.append(" (no business date set)");
            sb.append(" [internal_id: ").append(d.getId()).append("]\n");
        }
        sb.append("\n[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private String createDomain(String tenantId, Map<String, Object> args) {
        String name = (String) args.get("name");
        String description = (String) args.get("description");
        String businessDateStr = (String) args.get("current_business_date");
        String grain = (String) args.get("business_date_grain");

        // Check if domain already exists
        var existing = domainRepo.findByTenantIdAndName(tenantId, name);
        if (existing.isPresent()) {
            var d = existing.get();
            return "Domain '" + name + "' already exists." +
                    (d.getCurrentBusinessDate() != null ? " Business date: " + d.getCurrentBusinessDate() : " WARNING: No business date set — this may cause pipeline creation to fail.") +
                    "\n[internal_id: " + d.getId() + "]" +
                    "\n[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]";
        }

        String slug = com.pulse.common.text.Slugify.slugify(name);
        if (slug.isBlank()) {
            return "Domain '" + name + "' could not be created: the name has no alphanumeric "
                    + "characters to derive a slug from. Use a name with at least one letter or digit.";
        }
        if (domainRepo.findByTenantIdAndSlug(tenantId, slug).isPresent()) {
            return "Domain slug '" + slug + "' (derived from '" + name
                    + "') is already in use under this tenant. Pick a different name.";
        }

        Domain domain = new Domain();
        domain.setTenantId(tenantId);
        domain.setName(name);
        domain.setSlug(slug);
        domain.setDescription(description);
        if (businessDateStr != null) {
            domain.setCurrentBusinessDate(java.time.LocalDate.parse(businessDateStr));
        }
        domain.setBusinessDateGrain(grain != null ? grain : "DAILY");
        domain.setBusinessDateTimezone("UTC");
        try {
            domain = domainRepo.save(domain);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return "Domain '" + name + "' could not be created: " + e.getMostSpecificCause().getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Domain **").append(domain.getName()).append("** created.\n");
        if (domain.getCurrentBusinessDate() != null) {
            sb.append("Business date: ").append(domain.getCurrentBusinessDate()).append("\n");
        } else {
            sb.append("WARNING: No business date was set. Pipelines in this domain may fail until a business date is configured.\n");
        }
        sb.append("[internal_id: ").append(domain.getId()).append("]\n");
        sb.append("[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private String createDataSource(String tenantId, Map<String, Object> args) {
        String name = (String) args.get("name");
        String domainName = (String) args.getOrDefault("domain_name", "Default");
        String domainId = (String) args.get("domain_id");
        String description = (String) args.get("description");

        // Check for duplicate
        var existing = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        for (var s : existing) {
            if (s.getName().equalsIgnoreCase(name)) {
                return "Data source '" + name + "' already exists.\n" +
                        "Name: " + s.getName() + "\n" +
                        "Domain: " + s.getDomainName() + "\n" +
                        "[internal_id: " + s.getId() + "]\n" +
                        "[NOTE: internal_id is for your tool calls only. NEVER show it to the user.]";
            }
        }

        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName(name);
        if (domainId != null && !domainId.isBlank()) {
            var domain = domainRepo.findById(domainId)
                    .filter(d -> tenantId.equals(d.getTenantId()))
                    .orElse(null);
            if (domain != null) {
                sor.setDomainId(domain.getId());
                sor.setDomainName(domain.getName());
            } else {
                sor.setDomainName(domainName);
            }
        } else {
            sor.setDomainName(domainName);
        }
        sor.setDescription(description);
        sor.setOwnerId("01JUSER00000000000000000");
        sor.setMetadata(new HashMap<>());
        sor = sorRepo.save(sor);

        return "Data source created successfully.\n" +
                "Name: " + sor.getName() + "\n" +
                "Domain: " + sor.getDomainName() + "\n" +
                "[internal_id: " + sor.getId() + "]\n" +
                (description != null ? "Description: " + description + "\n" : "") +
                "\n[NOTE: internal_id is for your tool calls only. NEVER show it to the user. Tell the user it appeared on screen.]";
    }

    @SuppressWarnings("unchecked")
    private String createConnector(String tenantId, Map<String, Object> args) {
        String sorName = (String) args.get("sor_name");
        String connectorName = (String) args.get("connector_name");
        String connectorType = (String) args.get("connector_type");
        String description = (String) args.get("description");
        Map<String, Object> config = args.get("config") instanceof Map ? (Map<String, Object>) args.get("config") : new java.util.HashMap<>();
        Map<String, Object> credentialRefs = args.get("credential_refs") instanceof Map ? (Map<String, Object>) args.get("credential_refs") : null;
        Map<String, Object> legacyCredentials = args.get("credentials") instanceof Map ? (Map<String, Object>) args.get("credentials") : null;
        // Phase 1: tool schema exposes uppercase tokens (DEV / INTEGRATION /
        // UAT / PRODUCTION) for agent readability, but the persisted
        // credential_profiles.environment column is canonical lowercase.
        // Normalize at the chat-tool boundary so direct credRepo.save below
        // can never strand a row at legacy uppercase.
        String credentialEnvironmentRaw = args.get("credentials_environment") instanceof String environment
                && !environment.isBlank()
                ? environment
                : "DEV";
        String credentialEnvironment;
        try {
            credentialEnvironment = DeploymentEnvironment.normalize(credentialEnvironmentRaw);
        } catch (IllegalArgumentException badEnv) {
            return "Unknown credentials_environment: '" + credentialEnvironmentRaw
                    + "'. Allowed values: DEV, INTEGRATION, UAT, PRODUCTION (or canonical local/dev/integration/uat/prod).";
        }

        // Resolve SOR
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        var sor = sors.stream()
                .filter(s -> s.getName().equalsIgnoreCase(sorName))
                .findFirst()
                .orElse(null);
        if (sor == null) {
            return "No SOR found with name '" + sorName + "'. Use list_data_sources to see available SORs.";
        }

        // Find connector definition by type name
        String connDefId = null;
        if (connectorType != null) {
            var allDefs = connDefRepo.findAll();
            for (var def : allDefs) {
                if (def.getName().equalsIgnoreCase(connectorType)
                        || def.getName().toLowerCase().contains(connectorType.toLowerCase())) {
                    connDefId = def.getId();
                    break;
                }
            }
        }
        if (connDefId == null) {
            // Use the first available definition as fallback, or create with a generic one
            var allDefs = connDefRepo.findAll();
            if (!allDefs.isEmpty()) {
                connDefId = allDefs.get(0).getId();
            } else {
                return "No connector definitions available. The system needs at least one connector definition configured.";
            }
        }

        // Phase 3: precompute credential-persistence intent BEFORE any
        // repository write, so a denied SECRET_METADATA gate leaves no
        // partial state (no orphan ConnectorInstance row, no credential
        // row). Only after the gate passes do we save the connector
        // instance + credential profile.
        Map<String, Object> credentialsToPersist = credentialRefs;
        boolean legacySecretRefsOnly = credentialsToPersist == null
                && legacyCredentials != null
                && !legacyCredentials.isEmpty()
                && containsOnlySecretRefs(legacyCredentials);
        if (legacySecretRefsOnly) {
            credentialsToPersist = legacyCredentials;
        }
        boolean willPersistCredentials =
                credentialsToPersist != null && !credentialsToPersist.isEmpty();
        if (willPersistCredentials) {
            String denied = enforceAgentMutation(PulseAction.SECRET_METADATA, tenantId, null);
            if (denied != null) {
                // Hard denial: do not create the connector either. The
                // agent reply explains why and the user can decide
                // whether to retry without credential refs.
                return denied;
            }
        }

        // Create the connector instance (now safe — either no credentials
        // requested, or the SECRET_METADATA gate passed).
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(sor.getId());
        ci.setConnectorDefinitionId(connDefId);
        ci.setName(connectorName);
        ci.setDescription(description);
        ci.setConfigTemplate(config);
        ci.setEnabled(true);
        ci = ciRepo.save(ci);

        // Save credential refs if provided (gate already passed above).
        if (willPersistCredentials) {
            CredentialProfile cred = new CredentialProfile();
            cred.setConnectorInstanceId(ci.getId());
            cred.setEnvironment(credentialEnvironment);
            cred.setConnectionConfig(credentialsToPersist);
            cred.setStatus(CredentialStatus.UNTESTED);
            credRepo.save(cred);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Connector **").append(ci.getName()).append("** created and assigned to SOR **").append(sor.getName()).append("**.\n");
        if (credentialsToPersist != null && !credentialsToPersist.isEmpty()) {
            sb.append("Credential references saved for ").append(credentialEnvironment)
                    .append(" with status UNTESTED.\n");
        } else if (legacyCredentials != null && !legacyCredentials.isEmpty()) {
            sb.append("WARNING: Connector metadata was created, but raw credential values were not persisted. ")
                    .append("Use the dedicated credential flow or provide secret references before calling the connection ready.\n");
        } else {
            sb.append("Credential readiness still needs to be configured separately if this connector requires secrets.\n");
        }
        sb.append("[internal_id: ").append(ci.getId()).append("]\n");
        sb.append("[internal_sor_id: ").append(sor.getId()).append("]\n");
        sb.append("[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String createDataset(String tenantId, Map<String, Object> args) {
        String sorName = (String) args.get("sor_name");
        String name = (String) args.get("name");
        String description = (String) args.get("description");
        String classification = (String) args.get("classification");
        String timeGrain = (String) args.get("time_grain");
        String currentAsof = (String) args.get("current_asof");
        String fileNamingPattern = (String) args.get("file_naming_pattern");
        String processingDatetimeSource = (String) args.get("processing_datetime_source");
        String connectorInstanceId = (String) args.get("connector_instance_id");
        Map<String, Object> schemaSnapshot = args.get("schema_snapshot") instanceof Map
                ? (Map<String, Object>) args.get("schema_snapshot") : null;

        // Resolve SOR
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        var sor = sors.stream()
                .filter(s -> s.getName().equalsIgnoreCase(sorName))
                .findFirst()
                .orElse(null);
        if (sor == null) {
            return "No SOR found with name '" + sorName + "'. Use list_data_sources to see available SORs.";
        }

        Dataset ds = new Dataset();
        ds.setTenantId(tenantId);
        ds.setSorId(sor.getId());
        ds.setConnectorInstanceId(connectorInstanceId);
        ds.setName(name);
        ds.setDescription(description);
        ds.setClassification(classification);
        ds.setSchemaFormat("JSON_SCHEMA");
        ds.setDefinitionType("MANUAL_DEFINITION");

        String domain = sor.getDomainName() != null ? sor.getDomainName().toLowerCase().replace(" ", "-") : "default";
        ds.setQualifiedName(tenantId + "." + domain + ".raw." + name);

        if (schemaSnapshot != null) {
            ds.setSchemaSnapshot(schemaSnapshot);
            ds.setStatus("SCHEMA_DEFINED");
        } else {
            ds.setStatus("DRAFT");
        }

        if (timeGrain != null) ds.setTimeGrain(timeGrain);
        if (fileNamingPattern != null) ds.setFileNamingPattern(fileNamingPattern);
        if (processingDatetimeSource != null && !processingDatetimeSource.isBlank()) {
            // V101: persist the user's "where does processing_datetime come from?"
            // decision so codegen reads it instead of guessing.
            ds.setProcessingDatetimeSource(processingDatetimeSource);
        }
        if (currentAsof != null) {
            ds.setCurrentAsof(java.time.LocalDate.parse(currentAsof).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        }

        // PKT-0023: Physical design metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> partitionStrategy = args.get("partition_strategy") instanceof Map
                ? (Map<String, Object>) args.get("partition_strategy") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> clusterStrategy = args.get("cluster_strategy") instanceof Map
                ? (Map<String, Object>) args.get("cluster_strategy") : null;
        String writeMode = (String) args.get("write_mode");
        String tableFormatHint = (String) args.get("table_format_hint");

        if (partitionStrategy != null) ds.setPartitionStrategy(partitionStrategy);
        if (clusterStrategy != null) ds.setClusterStrategy(clusterStrategy);
        if (writeMode != null && !writeMode.isBlank()) ds.setWriteMode(writeMode);
        if (tableFormatHint != null && !tableFormatHint.isBlank()) ds.setTableFormatHint(tableFormatHint);

        // Generate dataset slug from name for landing contract generation
        String datasetSlug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        ds.setDatasetSlug(datasetSlug);
        String domainSlug = domain.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        ds.setDomainSlug(domainSlug);

        // Increment physical design version if any physical design field is set
        if (partitionStrategy != null || clusterStrategy != null
                || writeMode != null || tableFormatHint != null) {
            ds.setPhysicalDesignVersion(1);
        }

        ds = datasetRepo.save(ds);

        StringBuilder sb = new StringBuilder();
        sb.append("Dataset **").append(ds.getName()).append("** created on SOR **").append(sor.getName()).append("**.\n");
        sb.append("Qualified name: `").append(ds.getQualifiedName()).append("`\n");
        if (ds.getSchemaSnapshot() != null) sb.append("Schema: defined (").append(countFields(ds.getSchemaSnapshot())).append(" fields)\n");
        if (ds.getTimeGrain() != null) sb.append("Time grain: ").append(ds.getTimeGrain()).append("\n");
        if (ds.getCurrentAsof() != null) sb.append("Current as-of: ").append(ds.getCurrentAsof().atZone(java.time.ZoneOffset.UTC).toLocalDate()).append("\n");
        sb.append("[internal_id: ").append(ds.getId()).append("]\n");
        sb.append("[internal_sor_id: ").append(sor.getId()).append("]\n");
        sb.append("[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private int countFields(Map<String, Object> schemaSnapshot) {
        Object fields = schemaSnapshot.get("fields");
        if (fields instanceof List<?> list) return list.size();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private String deriveDatasetSchema(String tenantId, Map<String, Object> args) {
        String ciId = (String) args.get("connector_instance_id");
        String sourceType = (String) args.get("source_type");
        String environment = (String) args.get("environment");

        if (ciId == null || sourceType == null) {
            return "Error: connector_instance_id and source_type are required.";
        }

        // Tenant isolation: verify connector belongs to the caller's tenant
        ConnectorInstance ciCheck = ciRepo.findById(ciId).orElse(null);
        if (ciCheck == null) return "Error: connector instance '" + ciId + "' not found.";
        SystemOfRecord sorCheck = sorRepo.findById(ciCheck.getSorId()).orElse(null);
        if (sorCheck == null) return "Error: SOR not found for connector instance.";
        if (!tenantId.equals(sorCheck.getTenantId())) {
            return "Error: connector instance does not belong to current tenant.";
        }

        try {
            SchemaDiscoveryService.DiscoveryResult result = switch (sourceType) {
                case "table" -> {
                    String tableName = (String) args.get("table_name");
                    if (tableName == null) yield null;
                    yield schemaDiscoveryService.discoverFromTable(ciId, tableName, environment);
                }
                case "query" -> {
                    String query = (String) args.get("query");
                    if (query == null) yield null;
                    yield schemaDiscoveryService.discoverFromQuery(ciId, query, environment);
                }
                case "sample" -> {
                    String sampleData = (String) args.get("sample_data");
                    String sampleFormat = (String) args.get("sample_format");
                    if (sampleData == null || sampleFormat == null) yield null;
                    yield schemaDiscoveryService.discoverFromSample(ciId, sampleData, sampleFormat);
                }
                default -> null;
            };

            if (result == null) {
                return "Error: missing required parameters for source_type='" + sourceType + "'. " +
                       "For 'table': table_name. For 'query': query. For 'sample': sample_data and sample_format.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("**Schema Discovery Result** (").append(result.discoveryMethod()).append(")\n\n");
            sb.append("Classification: **").append(result.classification()).append("**\n");
            sb.append("Fields (").append(result.fields().size()).append("):\n\n");
            for (Map<String, Object> field : result.fields()) {
                sb.append("| `").append(field.get("name")).append("` | ")
                  .append(field.get("type")).append(" | ")
                  .append(Boolean.TRUE.equals(field.get("nullable")) ? "nullable" : "not null");
                if (Boolean.TRUE.equals(field.get("pii"))) sb.append(" | **PII**");
                else if ("CONFIDENTIAL".equals(field.get("classification"))) sb.append(" | CONFIDENTIAL");
                sb.append(" |\n");
            }
            sb.append("\n[discovery_method: ").append(result.discoveryMethod()).append("]\n");
            sb.append("[discovery_proof: ").append(result.discoveryProof()).append("]\n");
            sb.append("[connector_instance_id: ").append(ciId).append("]\n");
            sb.append("\nUse `create_dataset_from_discovery` to register this schema as a dataset.");
            return sb.toString();

        } catch (IllegalStateException e) {
            return "Schema discovery blocked: " + e.getMessage();
        } catch (Exception e) {
            return "Error during schema discovery: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String createDatasetFromDiscovery(String tenantId, Map<String, Object> args) {
        String ciId = (String) args.get("connector_instance_id");
        String name = (String) args.get("name");
        List<Map<String, Object>> fields = args.get("fields") instanceof List
                ? (List<Map<String, Object>>) args.get("fields") : null;

        if (ciId == null || name == null || fields == null || fields.isEmpty()) {
            return "Error: connector_instance_id, name, and fields are required.";
        }

        ConnectorInstance ci = ciRepo.findById(ciId).orElse(null);
        if (ci == null) return "Error: connector instance '" + ciId + "' not found.";

        SystemOfRecord sor = sorRepo.findById(ci.getSorId()).orElse(null);
        if (sor == null) return "Error: SOR not found for connector instance.";

        // Tenant isolation: verify connector belongs to the caller's tenant
        if (!tenantId.equals(sor.getTenantId())) {
            return "Error: connector instance does not belong to current tenant.";
        }

        Dataset ds = new Dataset();
        ds.setTenantId(tenantId);
        ds.setSorId(sor.getId());
        ds.setConnectorInstanceId(ciId);
        ds.setName(name);
        ds.setDescription((String) args.get("description"));
        ds.setClassification((String) args.get("classification"));

        String domain = sor.getDomainName() != null ? sor.getDomainName().toLowerCase().replace(" ", "-") : "default";
        ds.setQualifiedName(tenantId + "." + domain + ".raw." + name);

        Map<String, Object> schemaSnapshot = new java.util.LinkedHashMap<>();
        schemaSnapshot.put("fields", fields);
        ds.setSchemaSnapshot(schemaSnapshot);
        ds.setSchemaFormat("JSON_SCHEMA");
        ds.setStatus("SCHEMA_DEFINED");

        String discoveryMethod = (String) args.get("discovery_method");
        ds.setDiscoveryMethod(discoveryMethod);
        if (args.get("discovery_proof") instanceof Map) {
            ds.setDiscoveryProof((Map<String, Object>) args.get("discovery_proof"));
        }

        String defType = switch (discoveryMethod != null ? discoveryMethod : "MANUAL") {
            case "TABLE_DISCOVERY" -> "TABLE_SELECTION";
            case "QUERY_DISCOVERY" -> "CUSTOM_SQL";
            default -> "MANUAL_DEFINITION";
        };
        ds.setDefinitionType(defType);

        String timeGrain = (String) args.get("time_grain");
        if (timeGrain != null) ds.setTimeGrain(timeGrain);
        String asofColumnName = (String) args.get("asof_column_name");
        if (asofColumnName != null) ds.setAsofColumnName(asofColumnName);
        String currentAsof = (String) args.get("current_asof");
        if (currentAsof != null) {
            ds.setCurrentAsof(java.time.LocalDate.parse(currentAsof)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        }
        String asofTimezone = (String) args.get("asof_timezone");
        if (asofTimezone != null) ds.setAsofTimezone(asofTimezone);
        if (args.get("custom_sql") != null) ds.setCustomSql((String) args.get("custom_sql"));
        if (args.get("source_tables") instanceof List) {
            ds.setSourceTables((List<String>) args.get("source_tables"));
        }

        ds = datasetRepo.save(ds);

        StringBuilder sb = new StringBuilder();
        sb.append("Dataset **").append(ds.getName()).append("** created from discovery on SOR **")
          .append(sor.getName()).append("**.\n");
        sb.append("Qualified name: `").append(ds.getQualifiedName()).append("`\n");
        sb.append("Classification: ").append(ds.getClassification()).append("\n");
        sb.append("Schema: ").append(fields.size()).append(" fields\n");
        sb.append("Discovery method: ").append(ds.getDiscoveryMethod()).append("\n");
        if (ds.getAsofColumnName() != null) sb.append("As-of column: ").append(ds.getAsofColumnName()).append("\n");
        if (ds.getCurrentAsof() != null) sb.append("Current as-of: ").append(
                ds.getCurrentAsof().atZone(java.time.ZoneOffset.UTC).toLocalDate()).append("\n");
        sb.append("[internal_id: ").append(ds.getId()).append("]\n");
        sb.append("[internal_sor_id: ").append(sor.getId()).append("]\n");
        sb.append("[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private String listConnectors(String tenantId, String sorName) {
        var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
        var sor = sors.stream()
                .filter(s -> s.getName().equalsIgnoreCase(sorName))
                .findFirst()
                .orElse(null);
        if (sor == null) {
            return "No SOR found with name '" + sorName + "'. Use list_data_sources to see available SORs.";
        }
        var connectors = ciRepo.findBySorIdOrderByNameAsc(sor.getId());
        if (connectors.isEmpty()) {
            return "SOR '" + sor.getName() + "' has no connectors configured.\n[internal_sor_id: " + sor.getId() + "]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Connectors for **").append(sor.getName()).append("**:\n\n");
        for (var ci : connectors) {
            long dsCount = datasetRepo.countByConnectorInstanceId(ci.getId());
            sb.append("- **").append(ci.getName()).append("**");
            if (ci.getConnectorDefinitionId() != null) {
                connDefRepo.findById(ci.getConnectorDefinitionId())
                        .ifPresent(def -> sb.append(" (").append(def.getName()).append(")"));
            }
            sb.append(" — ").append(dsCount).append(" dataset(s)");
            sb.append(" [internal_id: ").append(ci.getId()).append("]\n");
        }
        sb.append("\n[internal_sor_id: ").append(sor.getId()).append("]\n");
        sb.append("[NOTE: internal IDs are for tool calls only. NEVER show them to the user.]");
        return sb.toString();
    }

    private String listDatasets(String tenantId, String connectorInstanceId, String sorName) {
        List<Dataset> datasets;
        String resolvedSorId = null;
        if (connectorInstanceId != null) {
            datasets = datasetRepo.findByConnectorInstanceIdOrderByNameAsc(connectorInstanceId);
        } else if (sorName != null) {
            var sors = sorRepo.findByTenantIdOrderByNameAsc(tenantId);
            var sor = sors.stream()
                    .filter(s -> s.getName().equalsIgnoreCase(sorName))
                    .findFirst()
                    .orElse(null);
            if (sor == null) {
                return "No SOR found with name '" + sorName + "'. Use list_data_sources to see available SORs.";
            }
            resolvedSorId = sor.getId();
            datasets = datasetRepo.findBySorIdOrderByNameAsc(resolvedSorId);
        } else {
            datasets = datasetRepo.findByTenantIdOrderByQualifiedNameAsc(tenantId);
        }
        StringBuilder sb = new StringBuilder();
        for (var ds : datasets) {
            sb.append("- **").append(ds.getName()).append("** (`").append(ds.getQualifiedName()).append("`)\n");
            sb.append("  Format: ").append(ds.getSchemaFormat());
            if (ds.getClassification() != null) sb.append(" | Classification: ").append(ds.getClassification());
            if (ds.getDefinitionType() != null) sb.append(" | Defined via: ").append(ds.getDefinitionType());
            sb.append(" [internal_id: ").append(ds.getId()).append("]\n");
            if (ds.getSchemaSnapshot() != null) {
                var fields = ds.getSchemaSnapshot().get("fields");
                if (fields instanceof List<?> fieldList) {
                    sb.append("  Fields: ");
                    int count = 0;
                    for (var f : fieldList) {
                        if (f instanceof Map<?, ?> fm) {
                            if (count > 0) sb.append(", ");
                            sb.append(fm.get("name"));
                            if (count >= 5) { sb.append(", ..."); break; }
                            count++;
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        if (datasets.isEmpty()) sb.append("No datasets found.\n");
        if (resolvedSorId != null) sb.append("[internal_sor_id: ").append(resolvedSorId).append("]\n");
        return sb.toString();
    }

    private String listBlueprints(String category, String surface, boolean includeDeprecated) {
        // ARCH-011 / ARCH-012: chat defaults to surface=composition so mutation
        // planning is composition-safe. Callers may opt into orchestration_policy
        // or 'all' for read-only audit.
        com.pulse.blueprint.model.BlueprintSurface surfaceFilter;
        try {
            surfaceFilter = (surface == null || surface.isBlank())
                    ? com.pulse.blueprint.model.BlueprintSurface.COMPOSITION
                    : com.pulse.blueprint.model.BlueprintSurface.fromWire(surface);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        com.pulse.blueprint.model.BlueprintCategory cat = null;
        if (category != null && !category.isBlank()) {
            try {
                cat = com.pulse.blueprint.model.BlueprintCategory.valueOf(category);
            } catch (IllegalArgumentException e) {
                return "Invalid category. Valid values: INGESTION, TRANSFORM, MODELING, DATA_QUALITY, ORCHESTRATION, DESTINATION";
            }
        }

        // Base set: active + non-deferred rows.
        List<Blueprint> base = (cat != null)
                ? blueprintRepo.findByStatusAndCategoryAndDeferredFalseOrderByNameAsc("active", cat)
                : blueprintRepo.findByStatusAndDeferredFalseOrderByCategoryAscNameAsc("active");

        java.util.List<Blueprint> blueprints;
        if (includeDeprecated) {
            // ARCH-014: include deprecated rows (with or without deferred), keep
            // non-deprecated deferred rows hidden.
            List<Blueprint> deprecated = (cat != null
                    ? blueprintRepo.findByCategoryOrderByNameAsc(cat)
                    : blueprintRepo.findAllByOrderByCategoryAscNameAsc()).stream()
                    .filter(bp -> "deprecated".equalsIgnoreCase(bp.getStatus()))
                    .toList();
            java.util.List<Blueprint> combined = new java.util.ArrayList<>(base);
            for (Blueprint bp : deprecated) {
                if (combined.stream().noneMatch(b -> b.getBlueprintKey().equals(bp.getBlueprintKey()))) {
                    combined.add(bp);
                }
            }
            blueprints = combined;
        } else {
            blueprints = base;
        }

        // Apply surface filter unless caller asked for 'all'.
        if (surfaceFilter != com.pulse.blueprint.model.BlueprintSurface.ALL) {
            String wire = surfaceFilter.wire();
            blueprints = blueprints.stream()
                    .filter(bp -> wire.equalsIgnoreCase(bp.getAddSurface()))
                    .toList();
        }

        StringBuilder sb = new StringBuilder();
        String currentCat = "";
        for (var bp : blueprints) {
            if (!bp.getCategory().name().equals(currentCat)) {
                currentCat = bp.getCategory().name();
                sb.append("\n### ").append(currentCat).append("\n");
            }
            sb.append("- **").append(blueprintLabel(bp)).append("** (`").append(bp.getBlueprintKey()).append("`): ")
                    .append(bp.getDescription(), 0, Math.min(120, bp.getDescription().length()));
            if (bp.getDescription().length() > 120) sb.append("...");
            if (bp.getValidLayers() != null && !bp.getValidLayers().isEmpty()) {
                sb.append(" | layers=").append(bp.getValidLayers());
            }
            if (bp.getEmitStrategy() != null) {
                sb.append(" | emit=").append(bp.getEmitStrategy());
            }
            if (bp.getCompositionRole() != null) {
                sb.append(" | role=").append(bp.getCompositionRole());
            }
            if (bp.isSupportsReuse()) {
                sb.append(" | reuse=true");
            }
            if ("deprecated".equals(bp.getStatus())) {
                String replacement = bp.getReplacementBlueprintKey();
                sb.append(" [DEPRECATED → ").append(replacement != null ? replacement : "no replacement").append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Appends '(dbt)' to a blueprint name when the blueprint emits a dbt artifact. */
    private String blueprintLabel(Blueprint bp) {
        if (bp == null) return "";
        boolean isDbt = bp.getArtifactTypes() != null
                && (bp.getArtifactTypes().contains("dbt_model")
                || bp.getArtifactTypes().contains("dbt_snapshot"));
        return bp.getName() + (isDbt ? " (dbt)" : "");
    }

    private String getBlueprintDetail(String blueprintKey) {
        var bpOpt = blueprintRepo.findByBlueprintKey(blueprintKey);
        if (bpOpt.isEmpty()) return "Blueprint not found: " + blueprintKey;
        var bp = bpOpt.get();

        StringBuilder sb = new StringBuilder();
        if ("deprecated".equals(bp.getStatus())) {
            String replacement = bp.getReplacementBlueprintKey();
            sb.append("> **Deprecated.** This blueprint has been retired. Replacement: `")
                    .append(replacement != null ? replacement : "no replacement")
                    .append("`. Do not propose this blueprint in new pipelines.\n\n");
        }
        sb.append("## ").append(blueprintLabel(bp)).append(" (").append(bp.getCategory()).append(")\n");
        sb.append("Key: ").append(bp.getBlueprintKey()).append("\n\n");
        sb.append(bp.getDescription()).append("\n\n");
        if (bp.getValidLayers() != null && !bp.getValidLayers().isEmpty()) {
            sb.append("### Valid Layers\n");
            sb.append(String.join(", ", bp.getValidLayers())).append("\n\n");
        }
        if (bp.getCompositionRole() != null) {
            sb.append("Composition role: ").append(bp.getCompositionRole()).append("\n");
        }
        if (bp.getEmitStrategy() != null) {
            sb.append("Emit strategy: ").append(bp.getEmitStrategy()).append("\n");
        }
        sb.append("Supports reuse: ").append(bp.isSupportsReuse() ? "yes" : "no").append("\n\n");

        if (bp.getParamsSchema() != null && !bp.getParamsSchema().isEmpty()) {
            sb.append("### Parameters\n");
            for (var param : bp.getParamsSchema()) {
                sb.append("- **").append(param.get("name")).append("** (").append(param.get("type")).append(")");
                if (param.get("description") != null) sb.append(": ").append(param.get("description"));
                sb.append("\n");
            }
        }
        if (bp.getInputPorts() != null && !bp.getInputPorts().isEmpty()) {
            sb.append("\n### Input Ports\n");
            for (var port : bp.getInputPorts()) {
                sb.append("- **").append(port.get("name")).append("** (").append(port.get("type")).append(")\n");
            }
        }
        if (bp.getOutputPorts() != null && !bp.getOutputPorts().isEmpty()) {
            sb.append("\n### Output Ports\n");
            for (var port : bp.getOutputPorts()) {
                sb.append("- **").append(port.get("name")).append("** (").append(port.get("type")).append(")\n");
            }
        }
        return sb.toString();
    }

    /**
     * Returns the connection_spec for a connector type so the agent uses the right
     * vocabulary at create_connector time.
     *
     * <p>Two connector families:
     * <ul>
     *   <li><b>Object storage</b> (S3-compatible): connection_spec is empty; bucket,
     *       path, region, endpoint and auth all resolve from storage_backends + the
     *       PULSE naming convention via the {@code get_storage_paths} tool. The
     *       agent must NOT elicit any of these from the user — it calls
     *       {@code get_storage_paths} and surfaces the resolved values for the
     *       user to confirm before {@code create_connector}.</li>
     *   <li><b>External SOR</b> (JDBC, Kafka, SFTP, REST, etc.): connection_spec
     *       lists user-input fields. Each property carries a {@code pulse_role}
     *       annotation: {@code credential} (user enters via credential dialog, lands
     *       in CredentialProfile.secret_references), {@code env_metadata} (per-env
     *       non-secret config). Properties marked {@code secret: true} are treated
     *       as secret-shaped values.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private String getConnectorTypeSchema(String connectorType) {
        if (connectorType == null || connectorType.isBlank()) {
            return "connector_type is required.";
        }
        var defs = connDefRepo.findByNameContainingIgnoreCaseOrderByNameAsc(connectorType);
        if (defs.isEmpty()) {
            return "No connector definition matched type: " + connectorType
                    + ". Call list_connectors or list_data_sources to see what's registered, or pick a more specific token (S3, SFTP, JDBC, Kafka, REST).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Connector type schema for: ").append(connectorType).append("\n\n");
        for (var def : defs) {
            sb.append("### ").append(def.getName()).append(" (").append(def.getConnectorType()).append(")\n");
            Map<String, Object> spec = def.getConnectionSpec();
            Object props = spec == null ? null : spec.get("properties");
            boolean hasProperties = props instanceof Map<?, ?> propMap && !propMap.isEmpty();

            // Object-storage connectors have empty connection_spec — the entire
            // configuration resolves from storage_backends + naming convention.
            // Steer the agent toward `get_storage_paths` instead of asking the
            // user for any S3-style fields.
            if (!hasProperties) {
                sb.append("**Platform-resolved connector.** This connector reads/writes the tenant ")
                        .append("storage_backend (DPC or GCP). All connection details — bucket, path, region, ")
                        .append("endpoint, and credentials — resolve from the storage_backends row + naming ")
                        .append("convention.\n\n")
                        .append("**Do NOT ask the user for any field values.** Instead:\n")
                        .append("1. Confirm storage_backend choice (DPC or GCP) at the pipeline level.\n")
                        .append("2. Call `get_storage_paths(sor_id, env, backend, direction)` to resolve the bucket + path.\n")
                        .append("3. Surface the resolved values to the user transparently with reasoning.\n")
                        .append("4. After user confirmation, call `create_connector` with explicit config.\n\n");
                continue;
            }

            Object required = spec.get("required");
            if (required instanceof List<?> reqList && !reqList.isEmpty()) {
                sb.append("**Required fields:** ").append(reqList).append("\n\n");
            }
            sb.append("| Field | Type | Default | pulse_role | Notes |\n|---|---|---|---|---|\n");
            for (var entry : ((Map<?, ?>) props).entrySet()) {
                String fieldName = String.valueOf(entry.getKey());
                Map<String, Object> field = (Map<String, Object>) entry.getValue();
                String type = String.valueOf(field.getOrDefault("type", ""));
                String def0 = field.containsKey("default") ? String.valueOf(field.get("default")) : "";
                String role = field.get("pulse_role") instanceof String r ? r : "";
                StringBuilder notes = new StringBuilder();
                if (Boolean.TRUE.equals(field.get("secret"))) notes.append("**SECRET** ");
                if (field.get("enum") instanceof List<?> enumList) notes.append("enum: ").append(enumList).append(" ");
                if (field.get("description") != null) notes.append(field.get("description"));
                sb.append("| ").append(fieldName).append(" | ").append(type).append(" | ")
                        .append(def0).append(" | ").append(role).append(" | ")
                        .append(notes.toString().trim()).append(" |\n");
            }
            sb.append("\n");
            sb.append("**Elicitation guidance:** for `pulse_role: env_metadata` fields, ask the user; for `pulse_role: credential` fields, defer to the credential dialog via `request_credential_attach` after `create_connector` lands.\n\n");
            if (def.getSupportedModes() != null && !def.getSupportedModes().isEmpty()) {
                sb.append("**Supported modes:** ").append(String.join(", ", def.getSupportedModes())).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * ARCH-009: apply an APPROVED chat plan. This is the only generic chat
     * tool that mutates product state; the plan's persisted
     * {@code plannedCommands} are reconstructed and executed deterministically.
     * The structured tool_result envelope is built by {@link ChatService} from
     * the returned plan; this method returns a human-readable summary string
     * for the LLM-facing conversation history.
     */
    private String applyPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            return "Error: plan_id is required";
        }
        try {
            com.pulse.command.model.Plan plan = planService.apply(planId);
            int n = plan.getCommandIds() == null ? 0 : plan.getCommandIds().size();
            StringBuilder out = new StringBuilder("Applied plan " + planId + " (" + n + " command"
                    + (n == 1 ? "" : "s") + "). Status: " + plan.getStatus().name());
            appendAppliedUiIntents(out, plan);
            return out.toString();
        } catch (IllegalArgumentException ex) {
            return "Error applying plan " + planId + ": " + ex.getMessage();
        } catch (Exception ex) {
            log.error("apply_plan failed for plan {}", planId, ex);
            return "Error applying plan " + planId + ": " + ex.getMessage();
        }
    }

    private void appendAppliedUiIntents(StringBuilder out, com.pulse.command.model.Plan plan) {
        Object rawIntents = plan.getPreviewData() == null ? null : plan.getPreviewData().get("appliedUiIntents");
        List<Map<String, Object>> intents = coerceMapList(rawIntents);
        if (intents.isEmpty()) {
            return;
        }
        for (Map<String, Object> intent : intents) {
            Object kind = intent.get("kind");
            if (!"credential_attach".equals(kind)) {
                continue;
            }
            Object rawConnectorId = intent.get("connectorInstanceId");
            String connectorId = rawConnectorId instanceof String value ? value : null;
            if (connectorId == null || connectorId.isBlank() || connectorId.startsWith("draft:")) {
                continue;
            }
            String env = intent.get("environment") instanceof String value && !value.isBlank()
                    ? value
                    : "DEV";
            var ciOpt = ciRepo.findById(connectorId);
            if (ciOpt.isEmpty()) {
                continue;
            }
            var ci = ciOpt.get();
            if (isFamilyAObjectStorage(ci)) {
                continue;
            }
            out.append("\nCredential dialog ready for connector ").append(connectorId)
                    .append(" (").append(env).append(").")
                    .append("\n[internal_attach_credential: sor_id=").append(ci.getSorId())
                    .append(" connector_instance_id=").append(connectorId)
                    .append(" environment=").append(env).append("]");
        }
    }

    /**
     * Persists a set of GX expectations to a DQValidator instance's
     * {@code dqExpectations} JSONB column — the canonical column read by
     * GxCodeGenerator at codegen time. Without this tool, suggested DQ
     * rules from {@code suggest_dq_expectations} live only in chat history
     * and never reach runtime.
     *
     * <p>Pairs with #86 prompt rule: never use {@code remove_step} +
     * {@code propose_add_instance} as a workaround for applying DQ rules.
     */
    @SuppressWarnings("unchecked")
    private String applyDqExpectations(Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        String versionId = (String) args.get("version_id");
        String instanceId = (String) args.get("instance_id");
        Object expectationsObj = args.get("expectations");

        if (instanceId == null || instanceId.isBlank()) {
            return "instance_id is required.";
        }
        if (versionId == null || versionId.isBlank()) {
            return "version_id is required.";
        }
        if (!(expectationsObj instanceof List<?> rawList)) {
            return "expectations must be a list of {type, kwargs, severity} objects.";
        }

        // Pipeline scope hint is informational only; the service is the
        // authoritative validator (version + instance + deprecated guard).
        if (pipelineId != null && !pipelineId.isBlank()) {
            var instOpt = instanceRepo.findById(instanceId);
            if (instOpt.isPresent() && !pipelineId.equals(instOpt.get().getPipelineId())) {
                return "Instance " + instanceId + " does not belong to pipeline " + pipelineId + ".";
            }
        }

        List<Map<String, Object>> expectations = new ArrayList<>();
        for (Object entry : rawList) {
            if (entry instanceof Map<?, ?> entryMap) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (var e : entryMap.entrySet()) {
                    if (e.getKey() instanceof String key) {
                        normalized.put(key, e.getValue());
                    }
                }
                expectations.add(normalized);
            }
        }

        // ARCH-013 parity: route through the shared DqExpectationService so
        // panel and chat persist via the same authority.
        try {
            SubPipelineInstance inst = dqExpectationService.save(versionId, instanceId, expectations);
            return "✅ Applied " + expectations.size() + " DQ expectation(s) to **" + inst.getName()
                    + "** [" + inst.getBlueprintKey() + "]. Stored on `instance.dqExpectations` — "
                    + "GxCodeGenerator will read these at codegen time.\n"
                    + "[internal_dq_applied: instance_id=" + instanceId + " count=" + expectations.size() + "]";
        } catch (com.pulse.common.exception.ResourceNotFoundException nf) {
            return "Instance not found: " + instanceId;
        } catch (IllegalArgumentException ia) {
            return ia.getMessage();
        }
    }

    /**
     * Resolves bucket + SOR-level path prefix for an object-storage connector
     * from the tenant's storage_backends row + naming convention. Returns
     * formatted text the agent can surface to the user transparently before
     * calling {@code create_connector} with explicit config.
     *
     * <p>Direction selects the lifecycle folder: {@code source} → {@code SRC}
     * (incoming / vendor drops); {@code sink} → {@code outgoing_extracts}
     * (PULSE-produced outputs).
     *
     * <p>Path layout (see {@link com.pulse.storage.PathConventionService}):
     * <pre>
     * {scheme}://{storage_root_files}/{domain_slug}/{sor_slug}/{pipeline_slug}/{lifecycle}/
     * </pre>
     * At create_connector time the pipeline_slug is unknown, so the SOR-level
     * path prefix returned here stops at {sor_slug}/. Pipeline-level scope is
     * appended at codegen time when the pipeline context is available.
     */
    private String getStoragePaths(String tenantId, Map<String, Object> args) {
        String sorId = (String) args.get("sor_id");
        String env = (String) args.getOrDefault("environment", "DEV");
        String backend = (String) args.get("backend");
        String direction = (String) args.getOrDefault("direction", "source");
        if (sorId == null || sorId.isBlank()) {
            return "sor_id is required.";
        }
        if (backend == null || backend.isBlank()) {
            return "backend is required (DPC or GCP).";
        }
        var sorOpt = sorRepo.findById(sorId);
        if (sorOpt.isEmpty()) {
            return "SOR not found: " + sorId;
        }
        SystemOfRecord sor = sorOpt.get();
        if (!tenantId.equals(sor.getTenantId())) {
            return "SOR " + sorId + " does not belong to the current tenant.";
        }

        // storage_backends.environment is stored lowercase per V96/V98 seeds
        // ('local', 'dev', 'integration', 'uat', 'prod'). The tool's enum
        // exposes uppercase tokens (LOCAL/DEV/INTEGRATION/UAT/PRODUCTION) for
        // agent readability; canonicalize via DeploymentEnvironment so e.g.
        // PRODUCTION resolves to 'prod' (a naive toLowerCase() would yield
        // 'production', which never matches a storage_backends row).
        String envLower;
        try {
            envLower = DeploymentEnvironment.normalize(env);
        } catch (IllegalArgumentException badEnv) {
            return "Unknown environment: '" + env
                    + "'. Allowed values: LOCAL, DEV, INTEGRATION, UAT, PRODUCTION (or canonical local/dev/integration/uat/prod).";
        }
        var sbOpt = storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(tenantId, envLower, backend);
        if (sbOpt.isEmpty()) {
            return "No storage_backend row for tenant=" + tenantId + " environment=" + envLower + " backend=" + backend
                    + ". The platform team must provision the storage_backend before pipelines can use it.";
        }
        StorageBackend sb = sbOpt.get();

        String scheme = "GCP".equals(backend) ? "gs"
                : (sb.getDpcScheme() != null ? sb.getDpcScheme() : "s3a");

        String domainSlug = "default";
        if (sor.getDomainId() != null && !sor.getDomainId().isBlank()) {
            domainSlug = domainRepo.findById(sor.getDomainId())
                    .map(Domain::getSlug)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse("default");
        }
        String sorSlug = sor.getName() == null ? "unknown" : com.pulse.common.text.Slugify.slugify(sor.getName());

        // Lifecycle folder per direction. Pipeline scope is appended at codegen time.
        String lifecycle = "sink".equalsIgnoreCase(direction) ? "outgoing_extracts" : "SRC";

        String bucket = sb.getStorageRootFiles();
        String pathPrefix = domainSlug + "/" + sorSlug + "/";
        String fullSorScopedPath = scheme + "://" + bucket + "/" + pathPrefix
                + "{pipeline_slug}/" + lifecycle + "/";

        // Object-storage connectors are identity-only at the chat layer (#80).
        // get_storage_paths is informational: it tells the user/agent WHERE the
        // data will live at runtime, but the agent does NOT push these values
        // into create_connector config. Path resolution happens at codegen time
        // via PathConventionService when the pipeline context is fully known.
        StringBuilder sb2 = new StringBuilder();
        sb2.append("## Storage path preview for ").append(sor.getName()).append(" (").append(direction).append(")\n\n");
        sb2.append("**Tenant:** ").append(tenantId).append(" · ");
        sb2.append("**Environment:** ").append(env).append(" · ");
        sb2.append("**Backend:** ").append(backend).append("\n\n");
        sb2.append("| Component | Resolved value | Source |\n|---|---|---|\n");
        sb2.append("| Bucket | `").append(bucket).append("` | storage_backends.storage_root_files |\n");
        sb2.append("| Path under bucket | `").append(pathPrefix).append("<pipeline>/").append(lifecycle).append("/` | naming convention `<domain>/<sor>/<pipeline>/<lifecycle>/` |\n");
        sb2.append("| URI scheme | `").append(scheme).append("://` | backend=").append(backend).append(" |\n\n");
        sb2.append("**Full path at runtime:** `").append(fullSorScopedPath).append("`\n");
        sb2.append("(`<pipeline>` resolves once you've added the connector to a pipeline; codegen fills it in via PathConventionService.)\n\n");
        sb2.append("**Object-storage connectors are identity-only — no config needed.**\n");
        sb2.append("Surface these resolved values to the user as confirmation context (\"this is where MSP files will land at runtime\"), then call `create_connector` with NO `config` argument. The connector_instance.config_template stays empty; codegen resolves the full path when the pipeline is built.\n\n");
        sb2.append("[internal_storage_resolved: bucket=").append(bucket)
            .append(" path_prefix=").append(pathPrefix)
            .append(" lifecycle=").append(lifecycle).append("]");
        return sb2.toString();
    }

    /**
     * Resolves the target connector and emits an [internal_attach_credential] marker
     * that getNavigationPath consumes to send the user to /producers/{sorId} with
     * query params that auto-open the credential dialog.
     */
    private String requestCredentialAttach(String tenantId, Map<String, Object> args) {
        String ciId = (String) args.get("connector_instance_id");
        String ciName = (String) args.get("connector_name");
        String envRaw = (String) args.getOrDefault("environment", "DEV");
        // Phase 1: surface the canonical key in the user-facing message and
        // in the [internal_attach_credential] marker so the frontend URL is
        // consistent across chat- and UI-driven flows.
        String env;
        try {
            env = DeploymentEnvironment.normalize(envRaw);
        } catch (IllegalArgumentException badEnv) {
            return "Unknown environment: '" + envRaw
                    + "'. Allowed values: DEV, INTEGRATION, UAT, PRODUCTION (or canonical local/dev/integration/uat/prod).";
        }
        if ((ciId == null || ciId.isBlank()) && (ciName == null || ciName.isBlank())) {
            return "Provide connector_instance_id or connector_name.";
        }
        if (ciId != null && ciId.startsWith("draft:")) {
            return "Credential attach is planned for " + ciId + ". The credential dialog will open only after apply_plan resolves it to a real connector instance id. Never collect credential values in chat.";
        }
        var ciOpt = (ciId != null && !ciId.isBlank())
                ? ciRepo.findById(ciId)
                : ciRepo.findByTenantIdOrderByNameAsc(tenantId).stream()
                        .filter(c -> ciName.equalsIgnoreCase(c.getName()))
                        .findFirst();
        if (ciOpt.isEmpty()) {
            return "Connector not found: " + (ciId != null ? ciId : ciName);
        }
        var ci = ciOpt.get();
        if (isFamilyAObjectStorage(ci)) {
            return "Credential attach is not required for **" + ci.getName()
                    + "**. S3-compatible object-storage connectors use the tenant storage_backend identity; do not collect or store user credentials for this connector.";
        }
        return "Opening the credential dialog for **" + ci.getName() + "** (" + env + "). "
                + "Enter the secret values or paste existing secret references; the dialog writes them via Secret Manager.\n"
                + "[internal_attach_credential: sor_id=" + ci.getSorId()
                + " connector_instance_id=" + ci.getId()
                + " environment=" + env + "]";
    }

    private boolean isFamilyAObjectStorage(ConnectorInstance ci) {
        if (ci == null || ci.getConnectorDefinitionId() == null) {
            return false;
        }
        return connDefRepo.findById(ci.getConnectorDefinitionId())
                .map(def -> {
                    String name = def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
                    String repo = def.getDockerRepository() == null
                            ? ""
                            : def.getDockerRepository().toLowerCase(Locale.ROOT);
                    Map<String, Object> spec = def.getConnectionSpec() == null ? Map.of() : def.getConnectionSpec();
                    Object properties = spec.get("properties");
                    boolean emptyProperties = properties instanceof Map<?, ?> props && props.isEmpty();
                    return (name.contains("object storage")
                            || name.contains("s3-compatible")
                            || repo.contains("source-s3")
                            || repo.contains("destination-s3"))
                            && emptyProperties;
                })
                .orElse(false);
    }

    private List<Map<String, Object>> coerceMapList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (var entry : map.entrySet()) {
                        if (entry.getKey() instanceof String key) {
                            row.put(key, entry.getValue());
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    row.put(key, entry.getValue());
                }
            }
            return List.of(row);
        }
        if (raw instanceof com.fasterxml.jackson.databind.JsonNode node) {
            try {
                if (node.isArray()) {
                    return objectMapper.convertValue(node,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                }
                if (node.isObject()) {
                    Map<String, Object> row = objectMapper.convertValue(node,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    return List.of(row);
                }
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                if (text.trim().startsWith("{")) {
                    Map<String, Object> row = objectMapper.readValue(text,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    return List.of(row);
                }
                return objectMapper.readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (raw != null) {
            List<Map<String, Object>> rendered = parseRenderedMapList(String.valueOf(raw));
            if (!rendered.isEmpty()) {
                return rendered;
            }
            try {
                return objectMapper.convertValue(raw,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } catch (IllegalArgumentException ignored) {
                try {
                    String encoded = objectMapper.writeValueAsString(raw);
                    if (encoded.trim().startsWith("{")) {
                        Map<String, Object> row = objectMapper.readValue(encoded,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        return List.of(row);
                    }
                    return objectMapper.readValue(encoded,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                } catch (Exception nestedIgnored) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> parseRenderedMapList(String rendered) {
        if (rendered == null || !rendered.startsWith("List(Map(") || !rendered.endsWith("))")) {
            return List.of();
        }
        String body = rendered.substring("List(".length(), rendered.length() - 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String rawMap : body.split("\\), Map\\(")) {
            String mapBody = rawMap;
            if (mapBody.startsWith("Map(")) {
                mapBody = mapBody.substring(4);
            }
            if (mapBody.endsWith(")")) {
                mapBody = mapBody.substring(0, mapBody.length() - 1);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String part : mapBody.split(", ")) {
                int sep = part.indexOf(" -> ");
                if (sep > 0) {
                    row.put(part.substring(0, sep), part.substring(sep + 4));
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private String listDbtAssets(String tenantId, Map<String, Object> args) {
        String domainId = resolveDomainId(tenantId, args);
        if (domainId == null) {
            return "No domain could be resolved. Provide domain_id or domain_name first.";
        }
        var assets = dbtAssetRegistryService.listDomainAssets(domainId);
        if (assets.isEmpty()) {
            return "No dbt assets are indexed for this domain yet.";
        }
        StringBuilder sb = new StringBuilder("## Indexed dbt assets\n\n");
        for (var asset : assets) {
            sb.append("- **").append(asset.getAssetName()).append("** (").append(asset.getAssetType()).append(")");
            if (asset.getBusinessConcept() != null) sb.append(" | concept=").append(asset.getBusinessConcept());
            if (asset.getGrain() != null) sb.append(" | grain=").append(asset.getGrain());
            if (asset.getAccessLevel() != null) sb.append(" | access=").append(asset.getAccessLevel());
            sb.append("\n  path: ").append(asset.getPath()).append("\n");
        }
        sb.append("\n[internal_domain_id: ").append(domainId).append("]\n");
        return sb.toString();
    }

    private String findDbtReuseCandidate(String tenantId, Map<String, Object> args) {
        String domainId = resolveDomainId(tenantId, args);
        if (domainId == null) {
            return "No domain could be resolved. Provide domain_id or domain_name first.";
        }
        String businessConcept = (String) args.get("business_concept");
        String assetType = (String) args.get("asset_type");
        String grain = (String) args.get("grain");
        String accessLevel = (String) args.get("access_level");
        String schemaSignature = (String) args.get("schema_signature");
        String emitStrategy = (String) args.get("emit_strategy");
        Map<String, Object> planningContext = args.get("planning_context") instanceof Map<?, ?> context
                ? new LinkedHashMap<>((Map<String, Object>) context)
                : Map.of();

        var match = dbtAssetRegistryService.findReuseCandidate(
                domainId, businessConcept, assetType, grain, accessLevel, schemaSignature, emitStrategy, planningContext);
        if (match.isEmpty()) {
            StringBuilder sb = new StringBuilder("No compatible dbt reuse candidate found.\n");
            sb.append("Decision: generate\n");
            sb.append("Why:\n");
            sb.append("- Reuse was evaluated before generation.\n");
            sb.append("- No indexed dbt asset met the requested semantic compatibility for this concept/type combination.\n");
            if (grain != null && !grain.isBlank()) {
                sb.append("- Requested grain: ").append(grain).append("\n");
            }
            if (schemaSignature != null && !schemaSignature.isBlank()) {
                sb.append("- Requested schema signature: ").append(schemaSignature).append("\n");
            }
            if (!planningContext.isEmpty()) {
                sb.append("- Planning context was considered: ").append(summarizePlanningContext(planningContext)).append("\n");
            }
            sb.append("Recommended emit strategy: generate");
            return sb.toString();
        }
        var asset = match.get().asset();
        StringBuilder sb = new StringBuilder("Found reusable dbt asset.\n");
        sb.append("Decision: ").append(match.get().emitStrategy()).append("\n");
        sb.append("Asset: ").append(asset.getAssetName()).append("\n");
        sb.append("Type: ").append(asset.getAssetType()).append("\n");
        sb.append("Path: ").append(asset.getPath()).append("\n");
        appendStringLine(sb, "Business concept", asset.getBusinessConcept());
        appendStringLine(sb, "Grain", asset.getGrain());
        appendStringLine(sb, "Access level", asset.getAccessLevel());
        sb.append("Why:\n");
        appendBulletLines(sb, match.get().reasons());
        if (!match.get().warnings().isEmpty()) {
            sb.append("Warnings:\n");
            appendBulletLines(sb, match.get().warnings());
        }
        sb.append("Compatibility:\n");
        appendCompatibilityLine(sb, "Business concept", match.get().compatibility().get("businessConcept"));
        appendCompatibilityLine(sb, "Access level", match.get().compatibility().get("accessLevel"));
        appendCompatibilityLine(sb, "Grain", match.get().compatibility().get("grain"));
        appendCompatibilityLine(sb, "Schema signature", match.get().compatibility().get("schemaSignature"));
        Object semanticOverlap = match.get().compatibility().get("semanticOverlap");
        if (semanticOverlap instanceof Number number) {
            sb.append("- Semantic overlap: ").append(String.format(Locale.ROOT, "%.2f", number.doubleValue())).append("\n");
        }
        Object referenceSafe = match.get().compatibility().get("referenceSafe");
        if (referenceSafe instanceof Boolean safe) {
            sb.append("- Reference-safe direct reuse: ").append(safe ? "yes" : "no").append("\n");
        }
        sb.append("Recommended emit strategy: ").append(match.get().emitStrategy());
        return sb.toString();
    }

    private String getComposition(String pipelineId) {
        if (isDraftRef(pipelineId)) {
            return draftPipelineRequiresApplyMessage("get_composition", pipelineId);
        }
        var pipeline = pipelineRepo.findById(pipelineId).orElse(null);
        if (pipeline == null) return "Pipeline not found: " + pipelineId;
        if (pipeline.getActiveVersionId() == null) return "Pipeline has no active version.";

        var comp = compositionService.getComposition(pipeline.getActiveVersionId());
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline: ").append(pipeline.getName()).append("\n");
        sb.append("[internal_version_id: ").append(pipeline.getActiveVersionId()).append("]\n\n");

        if (comp.instances().isEmpty()) {
            sb.append("Composition is empty — no blueprint instances yet.\n");
        } else {
            sb.append("### Instances (").append(comp.instances().size()).append(")\n");
            for (var inst : comp.instances()) {
                sb.append("- [").append(inst.getExecutionOrder()).append("] **").append(inst.getName())
                        .append("** (").append(inst.getBlueprintKey()).append(") [internal_id: ").append(inst.getId()).append("]\n");
                if (inst.getParams() != null && !inst.getParams().isEmpty()) {
                    sb.append("  Params: ").append(inst.getParams()).append("\n");
                }
            }
            if (!comp.wirings().isEmpty()) {
                sb.append("\n### Wirings (").append(comp.wirings().size()).append(")\n");
                // Build a name lookup for readable wiring display
                var nameMap = new java.util.HashMap<String, String>();
                for (var inst : comp.instances()) nameMap.put(inst.getId(), inst.getName());
                for (var w : comp.wirings()) {
                    String srcName = nameMap.getOrDefault(w.getSourceInstanceId(), w.getSourceInstanceId());
                    String tgtName = nameMap.getOrDefault(w.getTargetInstanceId(), w.getTargetInstanceId());
                    sb.append("- ").append(srcName).append(".").append(w.getSourcePortName())
                            .append(" → ").append(tgtName).append(".").append(w.getTargetPortName()).append("\n");
                }
            }
        }
        sb.append("\n[NOTE: internal_id/internal_version_id are for tool calls only. NEVER show to user.]");
        return sb.toString();
    }

    private String proposeCreatePipeline(String tenantId, Map<String, Object> args) {
        String name = (String) args.get("name");
        String description = (String) args.get("description");
        String sessionId = (String) args.get("_session_id");
        try {
            String scope = firstNonBlank((String) args.get("scope"), "DOMAIN");
            if (!"DOMAIN".equalsIgnoreCase(scope)) {
                return "Error: plan_create_pipeline supports scope=DOMAIN only";
            }
            if (sessionId == null || sessionId.isBlank()) {
                return "Error: session context is required to create a pipeline plan";
            }
            String requestedStorageBackend = firstNonBlank(
                    (String) args.get("default_storage_backend"),
                    (String) args.get("storage_backend"));
            String defaultStorageBackend = pipelineService.resolveDefaultStorageBackendForCreate(requestedStorageBackend);
            if (!"DPC".equals(defaultStorageBackend) && !"GCP".equals(defaultStorageBackend)) {
                return "Error: default_storage_backend must be DPC or GCP";
            }

            Domain domain = resolvePipelinePlanDomain(tenantId, args);
            if (domain == null) {
                List<String> available = domainRepo.findByTenantIdOrderByNameAsc(tenantId).stream()
                        .map(Domain::getName)
                        .toList();
                return "Error: a valid pipeline domain is required. Available domains: " + available;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("description", description != null ? description : "");
            payload.put("domainName", domain.getName());
            payload.put("domainId", domain.getId());
            payload.put("defaultStorageBackend", defaultStorageBackend);

            var plan = planService.createForSession(
                    tenantId,
                    null,
                    sessionId,
                    "agent",
                    "Create pipeline: " + name,
                    List.of(new com.pulse.command.service.PlanService.PlannedCommand(
                            com.pulse.pipeline.service.PipelineCommandHandlers.CREATE_PIPELINE,
                            "Pipeline",
                            "draft:pipeline:1",
                            "Create pipeline: " + name,
                            payload,
                            List.of(new com.pulse.command.service.PlanService.CommandOutput(
                                    "draft:pipeline:1",
                                    "aggregateId")),
                            Map.of())));

            return "Created pipeline plan " + plan.getId()
                    + " for pending pipeline draft:pipeline:1. Review and approve it before calling apply_plan; no product state was changed.\n"
                    + "[plan_id: " + plan.getId() + "]\n"
                    + "[declared_draft_ref: draft:pipeline:1]\n"
                    + "[preview_command: index=0 type=" + com.pulse.pipeline.service.PipelineCommandHandlers.CREATE_PIPELINE
                    + " aggregate=draft:pipeline:1]";
        } catch (Exception e) {
            log.error("Failed to create pipeline plan: {}", e.getMessage());
            return "Error creating pipeline plan: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String proposeAddInstance(Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        String blueprintKey = (String) args.get("blueprint_key");
        String instanceName = (String) args.get("instance_name");
        Map<String, Object> params = args.get("params") instanceof Map ? (Map<String, Object>) args.get("params") : null;
        try {
            if (isDraftRef(pipelineId)) {
                return draftPipelineRequiresApplyMessage("plan_add_step", pipelineId);
            }
            var pipeline = pipelineRepo.findById(pipelineId).orElse(null);
            if (pipeline == null) return "Error: Pipeline not found: " + pipelineId;
            String versionId = pipeline.getActiveVersionId();
            if (versionId == null) return "Error: Pipeline has no active version.";

            // Medallion + deprecated guards (Agent D redesign-v2 §9e, §9f).
            var bpOpt = blueprintRepo.findByBlueprintKey(blueprintKey);
            if (bpOpt.isPresent()) {
                var guardBp = bpOpt.get();
                if ("deprecated".equals(guardBp.getStatus())) {
                    String replacement = guardBp.getReplacementBlueprintKey();
                    return "Error: Blueprint '" + blueprintKey + "' is deprecated. Use '"
                            + (replacement != null ? replacement : "a current equivalent")
                            + "' instead. I will not propose adding a deprecated blueprint.";
                }
                List<String> validLayers = guardBp.getValidLayers();
                String requestedLayer = inferRequestedLayer(guardBp, params);
                if (validLayers != null && !validLayers.isEmpty() && requestedLayer != null
                        && !validLayers.contains(requestedLayer)) {
                    return "Error: Blueprint '" + blueprintKey + "' is not valid for layer '"
                            + requestedLayer + "'. Valid layers: " + validLayers
                            + ". Place this step in one of the valid layers and re-propose.";
                }
            }

            var instance = compositionService.addInstance(pipelineId, versionId, blueprintKey, instanceName, params);
            var bp = blueprintRepo.findByBlueprintKey(blueprintKey).orElse(null);
            StringBuilder sb = new StringBuilder();
            sb.append("Instance added successfully.\n");
            sb.append("Name: ").append(instance.getName()).append("\n");
            sb.append("Blueprint: ").append(instance.getBlueprintKey()).append("\n");
            if (bp != null) {
                var inPorts = bp.getInputPorts();
                var outPorts = bp.getOutputPorts();
                if (inPorts != null && !inPorts.isEmpty()) {
                    sb.append("Input ports: ");
                    for (int i = 0; i < inPorts.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(inPorts.get(i).get("name"));
                    }
                    sb.append("\n");
                } else {
                    sb.append("Input ports: (none — this is a source node)\n");
                }
                if (outPorts != null && !outPorts.isEmpty()) {
                    sb.append("Output ports: ");
                    for (int i = 0; i < outPorts.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(outPorts.get(i).get("name"));
                    }
                    sb.append("\n");
                } else {
                    sb.append("Output ports: (none — this is a sink/terminal node)\n");
                }
            }
            sb.append("[internal_id: ").append(instance.getId()).append("]\n");
            sb.append("[internal_pipeline_id: ").append(pipelineId).append("]");
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to add instance: {}", e.getMessage());
            return "Error adding instance: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String proposeWiring(Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        String sourceInstanceName = (String) args.get("source_instance_name");
        String sourcePort = (String) args.get("source_port");
        String targetInstanceName = (String) args.get("target_instance_name");
        String targetPort = (String) args.get("target_port");
        try {
            if (isDraftRef(pipelineId)) {
                return draftPipelineRequiresApplyMessage("plan_wire_ports", pipelineId);
            }
            var pipeline = pipelineRepo.findById(pipelineId).orElse(null);
            if (pipeline == null) return "Error: Pipeline not found: " + pipelineId;
            String versionId = pipeline.getActiveVersionId();
            var instances = compositionService.getComposition(versionId).instances();
            var sourceInst = instances.stream().filter(i -> i.getName().equals(sourceInstanceName)).findFirst().orElse(null);
            var targetInst = instances.stream().filter(i -> i.getName().equals(targetInstanceName)).findFirst().orElse(null);
            if (sourceInst == null) return "Error: Instance not found: " + sourceInstanceName;
            if (targetInst == null) return "Error: Instance not found: " + targetInstanceName;

            // Resolve generic port names to actual blueprint port names
            String resolvedSourcePort = resolvePortName(sourceInst.getBlueprintKey(), sourcePort, false);
            String resolvedTargetPort = resolvePortName(targetInst.getBlueprintKey(), targetPort, true);

            var wiring = compositionService.wirePort(versionId, sourceInst.getId(), resolvedSourcePort, targetInst.getId(), resolvedTargetPort);
            return "Wired successfully: " + sourceInstanceName + "." + resolvedSourcePort + " -> " + targetInstanceName + "." + resolvedTargetPort +
                    "\n[internal_pipeline_id: " + pipelineId + "]";
        } catch (Exception e) {
            log.error("Failed to wire ports: {}", e.getMessage());
            return "Error wiring ports: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String resolvePortName(String blueprintKey, String portName, boolean isInput) {
        if (blueprintKey == null || portName == null) return portName;
        var bp = blueprintRepo.findByBlueprintKey(blueprintKey).orElse(null);
        if (bp == null) return portName;

        List<Map<String, Object>> ports = isInput ? bp.getInputPorts() : bp.getOutputPorts();
        if (ports == null || ports.isEmpty()) return portName;

        // Check if the provided port name already matches an actual port
        for (var p : ports) {
            if (portName.equals(p.get("name"))) return portName;
        }

        // Generic names like "output", "input" -> resolve to the first port
        String generic = isInput ? "input" : "output";
        if (portName.equalsIgnoreCase(generic) || portName.equalsIgnoreCase("default")) {
            return (String) ports.get(0).get("name");
        }

        // Fallback: return as-is and let the service layer handle the error
        return portName;
    }

    /**
     * ARCH-018 plan_add_step. Wraps the legacy {@link #proposeAddInstance(Map)}
     * with an upfront policy-blueprint rejection: when the requested blueprint
     * has {@code add_surface=orchestration_policy} (or {@code pipelineConfig=true}),
     * the tool returns {@code STEP_REQUIRES_PIPELINE_ORCHESTRATION} without
     * touching {@link CompositionService}. CompositionService still enforces
     * the same rule at the service layer with {@code BLUEPRINT_NOT_ADDABLE},
     * but the chat-level code is more specific and routes the LLM to
     * {@code update_pipeline_orchestration}.
     */
    private String planAddStep(Map<String, Object> args) {
        String blueprintKey = (String) args.get("blueprint_key");
        if (blueprintKey != null && !blueprintKey.isBlank()) {
            var bpOpt = blueprintRepo.findByBlueprintKey(blueprintKey);
            if (bpOpt.isPresent()) {
                var bp = bpOpt.get();
                boolean policyBlueprint = bp.isPipelineConfig()
                        || "orchestration_policy".equalsIgnoreCase(bp.getAddSurface());
                if (policyBlueprint) {
                    return "Error: " + com.pulse.chat.plan.ChatPlanErrorCodes.STEP_REQUIRES_PIPELINE_ORCHESTRATION
                            + " — blueprint '" + blueprintKey
                            + "' is a pipeline orchestration policy (add_surface="
                            + bp.getAddSurface() + ", pipelineConfig=" + bp.isPipelineConfig()
                            + "). Use update_pipeline_orchestration / plan_set_pipeline_orchestration "
                            + "to configure version policy fields instead. Composition steps are "
                            + "limited to add_surface=composition blueprints.";
                }
            }
        }
        return proposeAddInstance(args);
    }

    /**
     * ARCH-018 plan_set_step_params. Generic-params-only update path with
     * stable forbidden-key blockers (STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN,
     * STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION, STEP_PARAMS_REQUIRES_DQ_TOOL,
     * STEP_PARAMS_EMPTY_AFTER_NORMALIZATION) and ARCH-014 compatibility guard
     * (BLUEPRINT_COMPAT_READ_ONLY).
     */
    @SuppressWarnings("unchecked")
    private String planSetStepParams(Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        String instanceName = (String) args.get("instance_name");
        Map<String, Object> params = args.get("params") instanceof Map
                ? (Map<String, Object>) args.get("params") : null;

        // ARCH-018 forbidden-key validation runs BEFORE any product-state lookup
        // so the LLM gets a deterministic routing hint without consuming
        // pipeline/version reads on rejected calls.
        com.pulse.chat.plan.StepParamsForbiddenKeyValidator.Result check =
                com.pulse.chat.plan.StepParamsForbiddenKeyValidator.validate(params);
        if (!check.allowed()) {
            return "Error: " + check.errorCode() + " — " + check.message();
        }

        try {
            if (isDraftRef(pipelineId)) {
                return draftPipelineRequiresApplyMessage("plan_set_step_params", pipelineId);
            }
            var pipeline = pipelineRepo.findById(pipelineId).orElse(null);
            if (pipeline == null) return "Error: Pipeline not found: " + pipelineId;
            String versionId = pipeline.getActiveVersionId();
            var instances = compositionService.getComposition(versionId).instances();
            var inst = instances.stream()
                    .filter(i -> i.getName().equals(instanceName))
                    .findFirst().orElse(null);
            if (inst == null) return "Error: Instance not found: " + instanceName;

            // CompositionService.updateInstanceParams runs the ARCH-014 compat
            // guard, which surfaces BLUEPRINT_COMPAT_READ_ONLY via
            // BlueprintCompatReadOnlyException. We catch it here so the chat
            // tool returns the same stable code shape as the other plan_*
            // forbidden-key rejections.
            try {
                compositionService.updateInstanceParams(
                        versionId, inst.getId(), check.sanitizedParams());
            } catch (com.pulse.blueprint.exception.BlueprintCompatReadOnlyException compat) {
                return "Error: " + com.pulse.chat.plan.ChatPlanErrorCodes.BLUEPRINT_COMPAT_READ_ONLY
                        + " — " + compat.getMessage();
            }
            return "Parameters planned for " + instanceName + ". Generic params updated; "
                    + "canonical / orchestration / DQ keys were stripped at validation. "
                    + "[contractImpact: NONE]";
        } catch (Exception e) {
            log.error("Failed to set step params: {}", e.getMessage());
            return "Error setting params: " + e.getMessage();
        }
    }

    private static boolean isDraftRef(String value) {
        return value != null && value.startsWith("draft:");
    }

    private static String draftPipelineRequiresApplyMessage(String toolName, String pipelineId) {
        return "Pipeline draft " + pipelineId + " is a PREVIEW label, not a persisted pipeline id yet. "
                + "Do not call " + toolName + " against draft refs. Ask the user to approve/apply "
                + "the pipeline creation plan first with apply_plan(plan_id); after apply_plan returns, "
                + "use the resolved real pipeline id for composition steps.\n"
                + "[draft_ref_waiting_for_apply: " + pipelineId + "]";
    }

    @SuppressWarnings("unchecked")
    private String planConfigureRemotePipelineInvocation(String tenantId, Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        if (pipelineId == null || pipelineId.isBlank()) {
            pipelineId = (String) args.get("_pipeline_id");
        }
        String sessionId = (String) args.get("_session_id");
        if (pipelineId == null || pipelineId.isBlank()) {
            return "Error: pipeline_id is required";
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "Error: session context is required to create a broker configuration plan";
        }
        Map<String, Object> payloadTemplate = args.get("payload_template") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
        List<String> secretOffenders = new ArrayList<>();
        scanForPlaintextSecrets(payloadTemplate, "payload_template", secretOffenders);
        if (!secretOffenders.isEmpty()) {
            return "Error: BROKER_PAYLOAD_TEMPLATE_SECRET_FORBIDDEN — payload_template may carry only non-secret "
                    + "business values or secret references. Offenders: " + secretOffenders;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", pipelineId);
        payload.put("versionId", args.get("version_id"));
        payload.put("instanceId", args.get("instance_id"));
        payload.put("instanceName", args.getOrDefault("instance_name", "Invoke remote pipeline"));
        payload.put("federatedTenantKey", requiredArg(args, "federated_tenant_key"));
        payload.put("remoteTargetRef", requiredArg(args, "remote_target_ref"));
        payload.put("environment", requiredArg(args, "environment"));
        payload.put("airflowConnectionId", requiredArg(args, "airflow_connection_id"));
        payload.put("remoteDagId", args.get("remote_dag_id"));
        payload.put("pollIntervalSeconds", args.get("poll_interval_seconds"));
        payload.put("timeoutSeconds", args.get("timeout_seconds"));
        payload.put("payloadTemplate", payloadTemplate);

        com.pulse.command.model.Plan plan = planService.createForSession(
                tenantId,
                pipelineId,
                sessionId,
                "agent",
                "Configure remote pipeline invocation",
                List.of(new com.pulse.command.service.PlanService.PlannedCommand(
                        com.pulse.broker.runtime.BrokerInvocationCommandHandlers.CONFIGURE_REMOTE_INVOCATION,
                        "BROKER_REMOTE_INVOCATION",
                        pipelineId,
                        "Configure RemotePipelineInvocation composition step",
                        payload)));
        return "Created broker invocation plan " + plan.getId()
                + ". Review and approve it before calling apply_plan; no product state was changed.";
    }

    private static String requiredArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    // Retained for any external caller (no longer wired into the dispatcher).
    @SuppressWarnings({"unchecked", "unused"})
    private String proposeSetParams(Map<String, Object> args) {
        return planSetStepParams(args);
    }

    // --- AI Requirements Gathering tool implementations ---

    private String getStepSchema(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        String instanceId = (String) args.get("instance_id");

        Map<String, Object> schema = compositionService.getUpstreamSchema(versionId, instanceId);
        if (schema == null || schema.isEmpty()) {
            return "No upstream schema found for instance " + instanceId +
                    ". The upstream step may not have an output schema yet, or there may be no wiring to this instance.";
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## Upstream Schema for Instance ").append(instanceId).append("\n\n");
            sb.append("```json\n");
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
            sb.append("\n```\n");

            // Provide a human-readable column summary if fields are present
            Object fields = schema.get("fields");
            if (fields instanceof List<?> fieldList && !fieldList.isEmpty()) {
                sb.append("\n**Columns (").append(fieldList.size()).append("):**\n");
                for (Object f : fieldList) {
                    if (f instanceof Map<?, ?> fm) {
                        sb.append("- `").append(fm.get("name")).append("` (").append(fm.get("type")).append(")\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Upstream schema found but failed to format: " + e.getMessage();
        }
    }

    private String evaluateDqReadiness(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");

        Map<String, Object> result = dqReadinessService.evaluate(versionId);

        StringBuilder sb = new StringBuilder();
        sb.append("## DQ Readiness Evaluation\n\n");
        sb.append("**Score: ").append(result.get("score")).append("/100**\n\n");

        Object reasoning = result.get("reasoning");
        if (reasoning != null && !reasoning.toString().isBlank()) {
            sb.append("**Reasoning:** ").append(reasoning).append("\n\n");
        }

        Object recommendations = result.get("recommendations");
        if (recommendations instanceof List<?> recList && !recList.isEmpty()) {
            sb.append("**Recommendations:**\n");
            for (Object rec : recList) {
                if (rec instanceof Map<?, ?> recMap) {
                    sb.append("- **").append(recMap.get("instance_name")).append("**\n");
                    Object expectations = recMap.get("expectations");
                    if (expectations instanceof List<?> expList) {
                        for (Object exp : expList) {
                            if (exp instanceof Map<?, ?> expMap) {
                                sb.append("  - `").append(expMap.get("type")).append("`");
                                if (expMap.get("kwargs") != null) sb.append(" ").append(expMap.get("kwargs"));
                                sb.append(" (").append(expMap.get("severity")).append(")");
                                if (expMap.get("reason") != null) sb.append(": ").append(expMap.get("reason"));
                                sb.append("\n");
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private String suggestDqExpectations(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        String instanceId = (String) args.get("instance_id");

        // Use the DQ readiness service to evaluate the full pipeline, then extract
        // suggestions for the specific instance
        Map<String, Object> result = dqReadinessService.evaluate(versionId);

        // Find the instance name to match recommendations
        SubPipelineInstance instance = null;
        try {
            var comp = compositionService.getComposition(versionId);
            for (var inst : comp.instances()) {
                if (inst.getId().equals(instanceId)) {
                    instance = inst;
                    break;
                }
            }
        } catch (Exception e) {
            return "Error finding instance " + instanceId + ": " + e.getMessage();
        }

        if (instance == null) {
            return "Instance not found: " + instanceId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## DQ Suggestions for: ").append(instance.getName())
                .append(" [").append(instance.getBlueprintKey()).append("]\n\n");

        // Extract recommendations for this specific instance
        Object recommendations = result.get("recommendations");
        boolean found = false;
        if (recommendations instanceof List<?> recList) {
            for (Object rec : recList) {
                if (rec instanceof Map<?, ?> recMap) {
                    String recName = String.valueOf(recMap.get("instance_name"));
                    if (recName.equals(instance.getName()) || recName.equals(instanceId)) {
                        found = true;
                        Object expectations = recMap.get("expectations");
                        if (expectations instanceof List<?> expList) {
                            sb.append("**Suggested expectations (").append(expList.size()).append("):**\n\n");
                            for (Object exp : expList) {
                                if (exp instanceof Map<?, ?> expMap) {
                                    sb.append("- **").append(expMap.get("type")).append("**\n");
                                    if (expMap.get("kwargs") != null) sb.append("  - kwargs: `").append(expMap.get("kwargs")).append("`\n");
                                    if (expMap.get("severity") != null) sb.append("  - severity: ").append(expMap.get("severity")).append("\n");
                                    if (expMap.get("reason") != null) sb.append("  - reason: ").append(expMap.get("reason")).append("\n");
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!found) {
            // Fall back to o4-mini reasoning model for DQ suggestions
            try {
                String upstreamSchema = getStepSchema(Map.of("version_id", versionId, "instance_id", instanceId));
                String reasoningResult = chatService.reasoningCall(
                        "You are a data quality expert. Given a dataset schema, suggest Great Expectations (GX) " +
                        "data quality rules. For each rule, provide: the GX expectation type, the column it applies to, " +
                        "kwargs, severity (Critical or Warning), and a brief reason. " +
                        "Format as a markdown table with columns: Column, Expectation, Kwargs, Severity, Reason. " +
                        "Be thorough — check for nulls, uniqueness, formats, ranges, and referential patterns.",
                        "Suggest DQ expectations for this schema:\n\n" + upstreamSchema
                );
                sb.append(reasoningResult).append("\n");
            } catch (Exception e) {
                sb.append("No specific DQ suggestions generated for this instance.\n");
                sb.append("This may mean the instance has adequate DQ coverage already, or the evaluation could not map recommendations to this step.\n");
            }
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String configureStepParams(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        String instanceId = (String) args.get("instance_id");
        Map<String, Object> params = (Map<String, Object>) args.get("params");

        if (params == null || params.isEmpty()) {
            return "Error: No params provided.";
        }

        String blocked = stagedPlanBlockMessage(versionId);
        if (blocked != null) return blocked;

        SubPipelineInstance updated = compositionService.updateInstanceParams(versionId, instanceId, params);
        StringBuilder sb = new StringBuilder();
        sb.append("✅ Parameters updated for **").append(updated.getName())
                .append("** [").append(updated.getBlueprintKey()).append("]\n\n");
        sb.append("Updated params:\n```json\n");
        try {
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(updated.getParams()));
        } catch (Exception e) {
            sb.append(updated.getParams());
        }
        sb.append("\n```\n");

        if (updated.getOutputSchema() != null && !updated.getOutputSchema().isEmpty()) {
            sb.append("\nOutput schema was auto-inferred after param update.\n");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String updatePipelineOrchestration(String tenantId, Map<String, Object> args) {
        String pipelineId = (String) args.get("pipeline_id");
        String versionId = (String) args.get("version_id");
        String scheduleCron = (String) args.get("schedule_cron");
        Boolean catchupEnabled = (Boolean) args.get("catchup_enabled");
        Integer maxActiveRuns = args.get("max_active_runs") instanceof Number n ? n.intValue() : null;
        Boolean dependsOnPast = (Boolean) args.get("depends_on_past");
        Map<String, Object> policyConfigs = args.get("policy_configs") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        var request = new com.pulse.pipeline.controller.PipelineController.UpdateOrchestrationRequest(
                scheduleCron, catchupEnabled, maxActiveRuns, dependsOnPast, policyConfigs);
        var version = pipelineService.updateOrchestration(tenantId, pipelineId, versionId, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> orchestrationPolicy = version.getMetadata() != null
                && version.getMetadata().get("orchestrationPolicy") instanceof Map<?, ?> policy
                ? (Map<String, Object>) policy
                : Map.of();
        String scheduleType = orchestrationPolicy.get("scheduleType") instanceof String type
                ? type
                : (version.getScheduleCron() != null ? "cron" : "manual");
        String scheduleSummary;
        if ("event".equals(scheduleType)) {
            String triggerDataset = orchestrationPolicy.get("triggerDataset") instanceof String dataset
                    ? dataset
                    : "";
            scheduleSummary = triggerDataset.isBlank()
                    ? "Event trigger"
                    : "Event trigger: " + triggerDataset;
        } else if ("manual".equals(scheduleType)) {
            scheduleSummary = "Manual only";
        } else {
            scheduleSummary = version.getScheduleCron() != null ? version.getScheduleCron() : "@daily";
        }
        return "Pipeline orchestration updated.\n" +
                "Schedule: " + scheduleSummary + "\n" +
                "Catchup: " + Boolean.TRUE.equals(version.getCatchupEnabled()) + "\n" +
                "Max active runs: " + (version.getMaxActiveRuns() != null ? version.getMaxActiveRuns() : 1) + "\n" +
                "Depends on past: " + Boolean.TRUE.equals(version.getDependsOnPast());
    }

    /**
     * BLOCKED concurrency while a Plan Preview is staged (IMPL-ui-composition
     * Phase 4 / §7.12). While a PREVIEW plan exists for the pipeline owning this
     * version, direct canonical mutation is rejected with a clear message — the
     * Customer must Apply or discard the staged plan first. Returns a non-null
     * rejection message when blocked, or null when the mutation may proceed.
     */
    private String stagedPlanBlockMessage(String versionId) {
        if (versionId == null) return null;
        String pipelineId = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId).stream()
                .map(SubPipelineInstance::getPipelineId)
                .findFirst().orElse(null);
        if (pipelineId == null) return null;
        boolean staged = planService.listByPipeline(pipelineId).stream()
                .anyMatch(p -> p.getStatus() == com.pulse.command.model.PlanStatus.PREVIEW);
        if (staged) {
            return "Error: a Plan Preview is currently staged for this pipeline. "
                    + "Apply or discard it before making further direct changes (BLOCKED_PLAN_STAGED).";
        }
        return null;
    }

    private String wirePorts(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        String sourceInstanceId = (String) args.get("source_instance_id");
        String sourcePortName = (String) args.get("source_port_name");
        String targetInstanceId = (String) args.get("target_instance_id");
        String targetPortName = (String) args.get("target_port_name");

        String blocked = stagedPlanBlockMessage(versionId);
        if (blocked != null) return blocked;

        // Resolve generic port names to actual blueprint port names
        var composition = compositionService.getComposition(versionId);
        var sourceInst = composition.instances().stream().filter(i -> i.getId().equals(sourceInstanceId)).findFirst().orElse(null);
        var targetInst = composition.instances().stream().filter(i -> i.getId().equals(targetInstanceId)).findFirst().orElse(null);
        if (sourceInst != null) sourcePortName = resolvePortName(sourceInst.getBlueprintKey(), sourcePortName, false);
        if (targetInst != null) targetPortName = resolvePortName(targetInst.getBlueprintKey(), targetPortName, true);

        PortWiring wiring = compositionService.wirePort(
                versionId, sourceInstanceId, sourcePortName, targetInstanceId, targetPortName);

        return "Port wired successfully.\n" +
                "- Source: " + sourceInstanceId + "." + sourcePortName + "\n" +
                "- Target: " + targetInstanceId + "." + targetPortName + "\n" +
                "- Wiring ID: " + wiring.getId() + "\n";
    }

    private String removeStep(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        String instanceId = (String) args.get("instance_id");

        String blocked = stagedPlanBlockMessage(versionId);
        if (blocked != null) return blocked;

        compositionService.removeInstance(versionId, instanceId);

        return "✅ Instance " + instanceId + " removed from the pipeline.\n" +
                "Any associated port wirings have been cleaned up.";
    }

    private String resolveDomainId(String tenantId, Map<String, Object> args) {
        String domainId = (String) args.get("domain_id");
        if (domainId != null && !domainId.isBlank()) {
            var domain = domainRepo.findById(domainId)
                    .filter(d -> tenantId.equals(d.getTenantId()))
                    .orElse(null);
            return domain != null ? domain.getId() : null;
        }
        String domainName = (String) args.get("domain_name");
        if (domainName != null && !domainName.isBlank()) {
            return domainRepo.findByTenantIdAndName(tenantId, domainName)
                    .map(Domain::getId)
                    .orElse(null);
        }
        String pipelineId = (String) args.get("pipeline_id");
        if (pipelineId != null) {
            return pipelineRepo.findById(pipelineId)
                    .map(Pipeline::getDomainId)
                    .orElse(null);
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Domain resolvePipelinePlanDomain(String tenantId, Map<String, Object> args) {
        String domainId = (String) args.get("domain_id");
        if (domainId != null && !domainId.isBlank()) {
            return domainRepo.findById(domainId)
                    .filter(domain -> tenantId.equals(domain.getTenantId()))
                    .orElse(null);
        }
        String domainName = firstNonBlank(
                (String) args.get("domain_name"),
                (String) args.get("domain"));
        if (domainName != null && !domainName.isBlank()) {
            return domainRepo.findByTenantIdAndName(tenantId, domainName).orElse(null);
        }
        return null;
    }

    private boolean containsOnlySecretRefs(Map<String, Object> values) {
        return values.values().stream()
                .filter(Objects::nonNull)
                .allMatch(this::isSecretRefValue);
    }

    private boolean isSecretRefValue(Object value) {
        return value instanceof String stringValue
                && (stringValue.startsWith("vault://") || stringValue.startsWith("gcp-sm://"));
    }

    private void appendStringLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private void appendBulletLines(StringBuilder sb, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            sb.append("- No specific reasons were returned.\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
    }

    private void appendCompatibilityLine(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }
        sb.append("- ").append(label).append(": ").append(value).append("\n");
    }

    private String summarizePlanningContext(Map<String, Object> planningContext) {
        List<String> parts = new ArrayList<>();
        for (var entry : planningContext.entrySet()) {
            if (entry.getValue() instanceof Collection<?> collection && !collection.isEmpty()) {
                parts.add(entry.getKey() + "=" + collection.stream()
                        .map(String::valueOf)
                        .limit(3)
                        .collect(java.util.stream.Collectors.joining(", ")));
            } else if (entry.getValue() instanceof Map<?, ?> map && !map.isEmpty()) {
                parts.add(entry.getKey() + "=" + map.keySet().stream()
                        .map(String::valueOf)
                        .limit(3)
                        .collect(java.util.stream.Collectors.joining(", ")));
            } else if (entry.getValue() != null) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return parts.isEmpty() ? "(none)" : String.join("; ", parts);
    }

    // ------------------------------------------------------------
    // Agent D redesign-v2 additions
    // ------------------------------------------------------------

    /**
     * Resolve the requested medallion layer for a blueprint instance. Priority:
     * explicit {@code params.layer} / {@code params.medallion_layer}, then a
     * category-based default. Returns null when no sensible default applies —
     * layer validation is soft-skipped in that case.
     */
    private String inferRequestedLayer(Blueprint bp, Map<String, Object> params) {
        if (params != null) {
            Object layer = params.get("layer");
            if (layer instanceof String s && !s.isBlank()) return s;
            Object medallion = params.get("medallion_layer");
            if (medallion instanceof String s && !s.isBlank()) return s;
        }
        if (bp == null || bp.getCategory() == null) return null;
        return switch (bp.getCategory().name()) {
            case "INGESTION" -> "bronze";
            case "ORCHESTRATION" -> "control_plane";
            default -> null;
        };
    }

    private String listSinkTargets(String tenantId) {
        List<SystemOfRecord> targets = sorRepo.findTargetsByTenantId(tenantId);
        if (targets.isEmpty()) {
            return "No sink targets registered for this tenant yet. Use create_sink_target to register one.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| Target | Domain | Connectors | ID |\n");
        sb.append("|--------|--------|------------|-----|\n");
        for (SystemOfRecord sor : targets) {
            long connectorCount = ciRepo.countBySorId(sor.getId());
            sb.append("| ").append(sor.getName())
                    .append(" | ").append(sor.getDomainName() != null ? sor.getDomainName() : "—")
                    .append(" | ").append(connectorCount)
                    .append(" | [internal_id: ").append(sor.getId()).append("] |\n");
        }
        return sb.toString();
    }

    private String createSinkTarget(String tenantId, Map<String, Object> args) {
        String name = args.get("name") != null ? args.get("name").toString().trim() : "";
        String description = args.get("description") != null ? args.get("description").toString() : null;
        String domainId = args.get("domain_id") != null ? args.get("domain_id").toString().trim() : "";
        if (name.isBlank() || domainId.isBlank()) {
            return "Error: name and domain_id are required";
        }
        Domain domain = domainRepo.findById(domainId).orElse(null);
        if (domain == null || !tenantId.equals(domain.getTenantId())) {
            return "Error: Domain " + domainId + " not found for tenant";
        }
        SystemOfRecord sor = new SystemOfRecord();
        sor.setTenantId(tenantId);
        sor.setName(name);
        sor.setDescription(description);
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("01JUSER00000000000000000");
        sor.setMetadata(Map.of("registry_type", "TARGET"));
        SystemOfRecord saved = sorRepo.save(sor);
        return "**Target created:** " + saved.getName() + "\n"
                + "Domain: " + saved.getDomainName() + "\n"
                + "[internal_id: " + saved.getId() + "]";
    }

    private String viewCodeExamples(String blueprintKey) {
        if (blueprintKey == null || blueprintKey.isBlank()) {
            return "Error: blueprint_key is required";
        }
        var bpOpt = blueprintRepo.findByBlueprintKey(blueprintKey);
        if (bpOpt.isEmpty()) {
            return "Error: Blueprint " + blueprintKey + " not found";
        }
        Blueprint bp = bpOpt.get();
        if ("deprecated".equals(bp.getStatus())) {
            String replacement = bp.getReplacementBlueprintKey();
            return "The blueprint '" + blueprintKey + "' has been deprecated. Use '"
                    + (replacement != null ? replacement : "a current equivalent")
                    + "' instead. I will not surface examples for deprecated blueprints.";
        }
        var examples = codegenExampleService.getExamplesForBlueprint(blueprintKey);
        if (examples == null || examples.isEmpty()) {
            return "No code examples indexed for '" + blueprintKey
                    + "'. The example_keys map in blueprints.codegen_hints is empty for this blueprint.";
        }

        String header = "**Code examples for " + blueprintLabel(bp) + ":**\n\n";
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < examples.size(); i++) {
            var example = examples.get(i);
            // Filename + language label gives the LLM the context it needs to pattern-match
            // (e.g., it sees "stg_dedupe_merge.sql" and emits dbt-style staging models).
            sb.append("Example ").append(i + 1).append(" — `")
                    .append(example.key()).append("` (").append(example.language()).append("):\n");
            sb.append("```").append(example.language()).append("\n")
                    .append(example.content()).append("\n```\n\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void scanForPlaintextSecrets(Object node, String path, List<String> offenders) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                String child = path + "." + key;
                if (value instanceof String s && !s.isBlank()
                        && isSecretShapedKey(key) && !isSecretReference(s) && !isMasked(s)) {
                    offenders.add(child);
                }
                scanForPlaintextSecrets(value, child, offenders);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanForPlaintextSecrets(list.get(i), path + "[" + i + "]", offenders);
            }
        }
    }

    private static boolean isSecretShapedKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("secret")
                || lower.endsWith("_token") || lower.equals("token")
                || lower.equals("api_key") || lower.equals("private_key")
                || lower.equals("refresh_token") || lower.equals("credentials_json")
                || lower.equals("service_account") || lower.equals("sasl_password")
                || lower.equals("client_secret");
    }

    private static boolean isSecretReference(String value) {
        return value.startsWith("gcp-sm://") || value.startsWith("vault://");
    }

    private static boolean isMasked(String value) {
        return "••••••••".equals(value);
    }
}
