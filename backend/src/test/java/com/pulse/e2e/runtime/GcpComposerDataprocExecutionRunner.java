package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the proof-harness GCP Composer -> Dataproc bridge plan through a
 * command-runner seam, then writes machine-readable runtime artifacts for the
 * cloud lane. This keeps live GCP orchestration in the e2e harness instead of
 * pushing gcloud/GCS/Composer assumptions into production code.
 */
public class GcpComposerDataprocExecutionRunner {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_OF_OBJECTS = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final GcpComposerDataprocBridgeAdapter bridgeAdapter;
    private final CommandRunner commandRunner;

    public GcpComposerDataprocExecutionRunner(ObjectMapper objectMapper) {
        this(objectMapper, new ShellCommandRunner());
    }

    GcpComposerDataprocExecutionRunner(ObjectMapper objectMapper, CommandRunner commandRunner) {
        this.objectMapper = objectMapper;
        this.bridgeAdapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        this.commandRunner = commandRunner;
    }

    public ExecutionResult execute(ExecutionRequest request) throws IOException, InterruptedException {
        Files.createDirectories(request.evidenceRoot());

        List<String> blockers = safetyBlockers(request);
        if (!blockers.isEmpty()) {
            Path blockedPath = request.evidenceRoot().resolve("gcp-execution-runner-blocked.json");
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("generatedAt", Instant.now().toString());
            blocked.put("runtimeAdapter", GcpComposerDataprocBridgeAdapter.RUNTIME_ADAPTER);
            blocked.put("scenarioId", request.adapterRequest().scenarioId());
            blocked.put("generationRunId", request.adapterRequest().generationRunId());
            blocked.put("blocked", true);
            blocked.put("reasons", blockers);
            blocked.put("authMode", request.adapterRequest().authMode());
            blocked.put("controlPlaneMode", request.adapterRequest().controlPlaneMode());
            blocked.put("callerPrincipal", request.adapterRequest().callerPrincipal());
            writeJson(blockedPath, blocked);
            return new ExecutionResult(true, blockers, List.of(blockedPath));
        }

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = bridgeAdapter
                .writeEvidence(request.adapterRequest(), request.evidenceRoot());
        GcpComposerDataprocBridgeAdapter.AdapterPlan plan = bridgeAdapter.plan(request.adapterRequest());

        List<Path> artifacts = new ArrayList<>();
        artifacts.add(written.adapterPlan());
        artifacts.add(written.composerEvidence());
        artifacts.add(written.dataprocEvidence());
        artifacts.add(written.dataprocSubmitRequest());
        artifacts.add(written.renderedDag());

        List<CommandRecord> composerPreflight = runAll(plan.composerPlan().preflightCommands());
        List<CommandRecord> composerUploads = runAll(plan.composerPlan().uploadCommands());
        List<CommandRecord> dataprocStage = runAll(plan.dataprocPlan().stageCommands());

        Path composerUploadEvidencePath = request.evidenceRoot().resolve("composer-upload-evidence.json");
        Map<String, Object> composerUploadEvidence = basePayload(request.adapterRequest());
        composerUploadEvidence.put("commands", commandMaps(composerUploads));
        composerUploadEvidence.put("uploadedObjects", storageListing(plan.composerPlan().namespacedDagGcsPrefix()));
        writeJson(composerUploadEvidencePath, composerUploadEvidence);
        artifacts.add(composerUploadEvidencePath);

        Path stagedArtifactManifestPath = request.evidenceRoot().resolve("staged-artifact-manifest.json");
        Map<String, Object> stagedArtifactManifest = basePayload(request.adapterRequest());
        stagedArtifactManifest.put("commands", commandMaps(dataprocStage));
        stagedArtifactManifest.put("stagedObjects", storageListing(plan.dataprocPlan().stagedNamespaceRoot()));
        writeJson(stagedArtifactManifestPath, stagedArtifactManifest);
        artifacts.add(stagedArtifactManifestPath);

        Path gcsInputListingPath = request.evidenceRoot().resolve("gcs-input-listing.json");
        Map<String, Object> gcsInputListing = basePayload(request.adapterRequest());
        gcsInputListing.put("stageRoot", plan.dataprocPlan().stageRoot());
        gcsInputListing.put("objects", storageListing(plan.dataprocPlan().stagedNamespaceRoot()));
        writeJson(gcsInputListingPath, gcsInputListing);
        artifacts.add(gcsInputListingPath);

        CommandRecord trigger = run(plan.composerPlan().triggerCommand());
        Path composerTriggerRequestPath = request.evidenceRoot().resolve("composer-trigger-request.json");
        Map<String, Object> composerTriggerRequest = basePayload(request.adapterRequest());
        composerTriggerRequest.put("command", commandMap(trigger));
        composerTriggerRequest.put("dagId", plan.composerPlan().dagId());
        composerTriggerRequest.put("dagRunId", plan.composerPlan().dagRunId());
        writeJson(composerTriggerRequestPath, composerTriggerRequest);
        artifacts.add(composerTriggerRequestPath);

        Path composerImportErrorsPath = request.evidenceRoot().resolve("composer-import-errors.json");
        Map<String, Object> composerImportErrors = basePayload(request.adapterRequest());
        composerImportErrors.put("command", commandMap(composerPreflight.getLast()));
        composerImportErrors.put("importErrors", parseListOfMaps(composerPreflight.getLast().stdout()));
        writeJson(composerImportErrorsPath, composerImportErrors);
        artifacts.add(composerImportErrorsPath);

        CommandRecord composerDagState = run(plan.composerPlan().statusCommands().getFirst());
        Path composerDagStatePath = request.evidenceRoot().resolve("composer-dag-state.json");
        Map<String, Object> composerDagStatePayload = basePayload(request.adapterRequest());
        composerDagStatePayload.put("dagId", plan.composerPlan().dagId());
        composerDagStatePayload.put("dagRunId", plan.composerPlan().dagRunId());
        composerDagStatePayload.put("command", commandMap(composerDagState));
        composerDagStatePayload.put("state", composerDagState.stdout().trim());
        writeJson(composerDagStatePath, composerDagStatePayload);
        artifacts.add(composerDagStatePath);

        CommandRecord composerTaskState = run(plan.composerPlan().statusCommands().get(1));
        Path composerTaskStatePath = request.evidenceRoot().resolve("composer-task-state.json");
        Map<String, Object> composerTaskStatePayload = basePayload(request.adapterRequest());
        composerTaskStatePayload.put("dagId", plan.composerPlan().dagId());
        composerTaskStatePayload.put("dagRunId", plan.composerPlan().dagRunId());
        composerTaskStatePayload.put("command", commandMap(composerTaskState));
        composerTaskStatePayload.put("taskStates", parseListOfMaps(composerTaskState.stdout()));
        writeJson(composerTaskStatePath, composerTaskStatePayload);
        artifacts.add(composerTaskStatePath);

        CommandRecord dataprocBatchState = run(plan.dataprocPlan().statusCommands().getFirst());
        Path dataprocBatchStatePath = request.evidenceRoot().resolve("dataproc-batch-state.json");
        Map<String, Object> dataprocBatchStatePayload = basePayload(request.adapterRequest());
        dataprocBatchStatePayload.put("batchId", plan.dataprocPlan().batchId());
        dataprocBatchStatePayload.put("command", commandMap(dataprocBatchState));
        dataprocBatchStatePayload.put("batchState", parseMap(dataprocBatchState.stdout()));
        writeJson(dataprocBatchStatePath, dataprocBatchStatePayload);
        artifacts.add(dataprocBatchStatePath);

        CommandRecord gcsOutputProbe = run("gcloud storage ls --recursive --json " + plan.dataprocPlan().outputProbeCommands()
                .getFirst()
                .substring(plan.dataprocPlan().outputProbeCommands().getFirst().lastIndexOf(' ') + 1));
        Path gcsOutputProbePath = request.evidenceRoot().resolve("gcs-output-probe.json");
        Map<String, Object> gcsOutputProbePayload = basePayload(request.adapterRequest());
        gcsOutputProbePayload.put("command", commandMap(gcsOutputProbe));
        gcsOutputProbePayload.put("objects", parseListOfMaps(gcsOutputProbe.stdout()));
        writeJson(gcsOutputProbePath, gcsOutputProbePayload);
        artifacts.add(gcsOutputProbePath);

        return new ExecutionResult(false, List.of(), List.copyOf(artifacts));
    }

    private List<String> safetyBlockers(ExecutionRequest request) {
        List<String> blockers = new ArrayList<>();
        var adapterRequest = request.adapterRequest();
        if (request.requiredReadinessVerdict() != null
                && !"PASS".equalsIgnoreCase(request.requiredReadinessVerdict())) {
            blockers.add("readiness_verdict_not_pass:" + request.requiredReadinessVerdict());
        }
        if (request.approvedProjectId() != null
                && !request.approvedProjectId().equals(adapterRequest.projectId())) {
            blockers.add("project_mismatch");
        }
        if (request.approvedComposerEnvironment() != null
                && !request.approvedComposerEnvironment().equals(adapterRequest.composerEnvironment())) {
            blockers.add("composer_environment_mismatch");
        }
        if (request.approvedStagingBucket() != null
                && !request.approvedStagingBucket().equals(adapterRequest.stagingBucket())) {
            blockers.add("staging_bucket_mismatch");
        }
        if (request.approvedDataprocServiceAccount() != null
                && !request.approvedDataprocServiceAccount().equals(adapterRequest.dataprocServiceAccount())) {
            blockers.add("dataproc_service_account_mismatch");
        }
        if (request.maxConcurrentCloudJobs() != null && request.maxConcurrentCloudJobs() > 1) {
            blockers.add("max_concurrent_cloud_jobs_exceeded");
        }
        return blockers;
    }

    private List<Map<String, Object>> storageListing(String uri) throws IOException, InterruptedException {
        return parseListOfMaps(run("gcloud storage ls --recursive --json " + uri).stdout());
    }

    private List<CommandRecord> runAll(List<String> commands) throws IOException, InterruptedException {
        List<CommandRecord> records = new ArrayList<>();
        for (String command : commands) {
            records.add(run(command));
        }
        return records;
    }

    private CommandRecord run(String command) throws IOException, InterruptedException {
        return new CommandRecord(command, commandRunner.run(command));
    }

    private Map<String, Object> basePayload(GcpComposerDataprocBridgeAdapter.AdapterRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("runtimeAdapter", GcpComposerDataprocBridgeAdapter.RUNTIME_ADAPTER);
        payload.put("scenarioId", request.scenarioId());
        payload.put("generationRunId", request.generationRunId());
        payload.put("projectId", request.projectId());
        payload.put("composerEnvironment", request.composerEnvironment());
        payload.put("region", request.dataprocRegion());
        payload.put("authMode", request.authMode());
        payload.put("controlPlaneMode", request.controlPlaneMode());
        payload.put("callerPrincipal", request.callerPrincipal());
        return payload;
    }

    private List<Map<String, Object>> commandMaps(List<CommandRecord> records) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (CommandRecord record : records) {
            payload.add(commandMap(record));
        }
        return payload;
    }

    private Map<String, Object> commandMap(CommandRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", record.command());
        payload.put("stdout", record.stdout());
        payload.put("stdoutSha256", sha256(record.stdout()));
        return payload;
    }

    private List<Map<String, Object>> parseListOfMaps(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(raw, LIST_OF_MAPS);
        } catch (Exception ignored) {
            return List.of(Map.of("raw", raw.trim()));
        }
    }

    private Map<String, Object> parseMap(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(raw, MAP_OF_OBJECTS);
        } catch (Exception ignored) {
            return Map.of("raw", raw.trim());
        }
    }

    private void writeJson(Path path, Map<String, Object> payload) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash command output", e);
        }
    }

    record CommandRecord(String command, String stdout) {
    }

    interface CommandRunner {
        String run(String command) throws IOException, InterruptedException;
    }

    private static final class ShellCommandRunner implements CommandRunner {
        @Override
        public String run(String command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder("bash", "-lc", command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Command failed (" + exitCode + "): " + command + "\n" + stderr);
            }
            return stdout;
        }
    }

    public record ExecutionRequest(
            GcpComposerDataprocBridgeAdapter.AdapterRequest adapterRequest,
            Path evidenceRoot,
            String requiredReadinessVerdict,
            String approvedProjectId,
            String approvedComposerEnvironment,
            String approvedStagingBucket,
            String approvedDataprocServiceAccount,
            Integer maxConcurrentCloudJobs
    ) {
        public ExecutionRequest {
            if (adapterRequest == null) {
                throw new IllegalArgumentException("adapterRequest is required");
            }
            if (evidenceRoot == null) {
                throw new IllegalArgumentException("evidenceRoot is required");
            }
        }
    }

    public record ExecutionResult(
            boolean blocked,
            List<String> blockers,
            List<Path> artifacts
    ) {
    }
}
