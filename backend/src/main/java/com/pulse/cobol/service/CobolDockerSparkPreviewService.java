package com.pulse.cobol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.jar.JarFile;

@Service
public class CobolDockerSparkPreviewService {

    private static final String SPARK_CONTAINER = "pulse-spark-master-1";
    private static final String SPARK_MASTER_URL = "local[*]";
    private static final String COBRIX_PACKAGE = "za.co.absa.cobrix:spark-cobol_2.12:2.10.2";
    private static final String CONTAINER_IVY_DIR = "/tmp/pulse-ivy";
    private static final String CONTAINER_IVY_CACHE_DIR = "/tmp/pulse-ivy/cache";
    private static final String CONTAINER_HOME_DIR = "/tmp/pulse-home";

    private final CobolSparkPreviewService previewService;
    private final ObjectMapper objectMapper;

    public CobolDockerSparkPreviewService(CobolSparkPreviewService previewService, ObjectMapper objectMapper) {
        this.previewService = previewService;
        this.objectMapper = objectMapper;
    }

    public CobolSparkPreviewService.PreviewOutcome execute(
            String copybookContent,
            byte[] dataSample,
            Path dataFile,
            Map<String, Object> optionOverrides,
            int sampleRows,
            Consumer<Map<String, Object>> progressSink) throws Exception {
        CobolSparkPreviewService.CandidatePlan plan = previewService.prepareCandidates(copybookContent, dataSample, optionOverrides);
        Exception lastError = null;

        for (Map<String, Object> candidate : plan.candidateOptions()) {
            String label = String.valueOf(candidate.getOrDefault("_candidate_label", "candidate"));
            progressSink.accept(event("candidate", "Trying candidate " + label, candidate));
            Path hostStage = Files.createTempDirectory("pulse-cobol-docker-" + UUID.randomUUID());
            String containerStage = "/tmp/pulse-cobol-discovery/" + UUID.randomUUID();
            try {
                Path copybook = hostStage.resolve("copybook.cob");
                Path options = hostStage.resolve("options.properties");
                Path submitProperties = hostStage.resolve("submit.properties");
                Path outputDir = hostStage.resolve("output");
                Files.createDirectories(outputDir);
                Files.writeString(copybook, copybookContent, StandardCharsets.UTF_8);
                writeOptions(candidate, sampleRows, options);
                writeSubmitProperties(submitProperties);

                Path runnerJar = ensureRunnerJar();
                progressSink.accept(event("docker", "Preparing Docker Spark stage", Map.of("candidate", label, "stage", containerStage)));
                runCommand(hostStage, progressSink, "docker", "exec", SPARK_CONTAINER, "/bin/sh", "-lc",
                        "mkdir -p " + containerStage + "/output " + CONTAINER_IVY_CACHE_DIR + " " + CONTAINER_HOME_DIR);
                runCommand(hostStage, progressSink, "docker", "cp", copybook.toString(), SPARK_CONTAINER + ":" + containerStage + "/copybook.cob");
                runCommand(hostStage, progressSink, "docker", "cp", dataFile.toString(), SPARK_CONTAINER + ":" + containerStage + "/data.ebc");
                runCommand(hostStage, progressSink, "docker", "cp", options.toString(), SPARK_CONTAINER + ":" + containerStage + "/options.properties");
                runCommand(hostStage, progressSink, "docker", "cp", submitProperties.toString(), SPARK_CONTAINER + ":" + containerStage + "/submit.properties");
                runCommand(hostStage, progressSink, "docker", "cp", runnerJar.toString(), SPARK_CONTAINER + ":" + containerStage + "/runner.jar");

                progressSink.accept(event("spark", "Submitting Spark job to Docker Spark", Map.of("candidate", label)));
                runCommand(hostStage, progressSink,
                        "docker", "exec", SPARK_CONTAINER, "/bin/sh", "-lc",
                        "export LD_PRELOAD=$LIBNSS_WRAPPER_PATH; " +
                                "export HOME=" + CONTAINER_HOME_DIR + "; " +
                                "export USER=spark; " +
                                "export LOGNAME=spark; " +
                                "export HADOOP_USER_NAME=spark; " +
                                "export SPARK_SUBMIT_OPTS=\"-Duser.home=" + CONTAINER_HOME_DIR + " -Duser.name=spark -Divy.home=" + CONTAINER_IVY_DIR + " -Divy.cache.dir=" + CONTAINER_IVY_CACHE_DIR + "\"; " +
                                "/opt/bitnami/spark/bin/spark-submit " +
                                "--master " + SPARK_MASTER_URL + " " +
                                "--properties-file " + containerStage + "/submit.properties " +
                                "--conf spark.jars.ivy=" + CONTAINER_IVY_DIR + " " +
                                "--files " + containerStage + "/copybook.cob," + containerStage + "/data.ebc," + containerStage + "/options.properties " +
                                "--packages " + COBRIX_PACKAGE + " " +
                                "--class com.pulse.cobol.runner.CobolDiscoverySparkJobRunner " +
                                containerStage + "/runner.jar " +
                                "copybook.cob " +
                                "data.ebc " +
                                "options.properties " +
                                containerStage + "/output");

                progressSink.accept(event("docker", "Copying Spark results back to backend", Map.of("candidate", label)));
                runCommand(hostStage, progressSink, "docker", "cp", SPARK_CONTAINER + ":" + containerStage + "/output/.", outputDir.toString());

                CobolSparkPreviewService.PreviewOutcome outcome = readOutput(plan.analysis().summary(), candidate, outputDir);
                progressSink.accept(event("success", "Candidate " + label + " parsed successfully", Map.of(
                        "rowCount", outcome.profilingSummary().get("rowCount"),
                        "columnCount", outcome.profilingSummary().get("columnCount")
                )));
                return outcome;
            } catch (Exception ex) {
                lastError = ex;
                progressSink.accept(event("failure", "Candidate " + label + " failed", Map.of("error", ex.getMessage())));
            } finally {
                try {
                    runCommand(hostStage, progressSink, "docker", "exec", SPARK_CONTAINER, "/bin/sh", "-lc", "rm -rf " + containerStage);
                } catch (Exception ignored) {
                }
                deleteRecursively(hostStage);
            }
        }

        throw new IllegalStateException(
                "Docker Spark Cobrix preview failed for all candidates"
                        + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    private CobolSparkPreviewService.PreviewOutcome readOutput(
            Map<String, Object> copybookSummary,
            Map<String, Object> chosenConfig,
            Path outputDir) throws IOException {
        Map<String, Object> schemaSnapshot = readSchema(outputDir.resolve("schema.tsv"));
        List<Map<String, Object>> mappingSpec = readMappings(outputDir.resolve("mapping.tsv"));
        List<Map<String, Object>> previewRows = readPreview(outputDir.resolve("preview.tsv"));
        Properties props = new Properties();
        try (var in = Files.newInputStream(outputDir.resolve("metadata.properties"))) {
            props.load(in);
        }
        long rowCount = Long.parseLong(props.getProperty("rowCount", "0"));
        Map<String, Object> flattenSpec = Map.of("mode", "flatten_structs_jsonify_complex", "columns", mappingSpec);
        return previewService.buildOutcome(
                chosenConfig,
                copybookSummary,
                schemaSnapshot,
                previewRows,
                mappingSpec,
                flattenSpec,
                rowCount,
                new ArrayList<>()
        );
    }

    private Map<String, Object> readSchema(Path path) throws IOException {
        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length < 3) continue;
            fields.add(Map.of(
                    "name", parts[0],
                    "type", parts[1],
                    "nullable", Boolean.parseBoolean(parts[2])
            ));
        }
        return Map.of("fields", fields);
    }

    private List<Map<String, Object>> readMappings(Path path) throws IOException {
        List<Map<String, Object>> mappings = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length < 4) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourcePath", parts[0]);
            row.put("outputColumn", parts[1]);
            row.put("strategy", parts[2]);
            row.put("dataType", parts[3]);
            mappings.add(row);
        }
        return mappings;
    }

    private List<Map<String, Object>> readPreview(Path path) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return rows;
        String[] headers = lines.get(0).split("\t", -1);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                String value = j < parts.length ? parts[j] : "";
                row.put(headers[j], value.isEmpty() ? null : unescape(value));
            }
            rows.add(row);
        }
        return rows;
    }

    private String unescape(String value) {
        return value.replace("\\t", "\t").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\");
    }

    private void writeOptions(Map<String, Object> candidate, int sampleRows, Path path) throws IOException {
        Properties props = new Properties();
        props.setProperty("_sample_rows", String.valueOf(sampleRows));
        Map<String, String> expanded = new LinkedHashMap<>();
        previewService.appendCobrixOptions(expanded, candidate);
        for (Map.Entry<String, String> entry : expanded.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }
        try (var out = Files.newOutputStream(path)) {
            props.store(out, "Cobol Discovery Spark Options");
        }
    }

    private void writeSubmitProperties(Path path) throws IOException {
        Properties props = new Properties();
        props.setProperty("spark.ui.enabled", "false");
        try (var out = Files.newOutputStream(path)) {
            props.store(out, "Cobol Discovery Spark Submit Properties");
        }
    }

    private Path ensureRunnerJar() throws IOException, InterruptedException {
        Path jar = Path.of(System.getProperty("user.dir")).resolve("build/libs/pulse-cobol-runner.jar");
        if (Files.exists(jar) && jarContainsRunner(jar)) {
            return jar;
        }
        ProcessBuilder pb = new ProcessBuilder("./gradlew", "cobolRunnerJar");
        pb.directory(Path.of(System.getProperty("user.dir")).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        int exit = process.waitFor();
        if (exit != 0 || !Files.exists(jar) || !jarContainsRunner(jar)) {
            throw new IllegalStateException("Unable to build plain runner jar for Docker Spark preview.");
        }
        return jar;
    }

    private boolean jarContainsRunner(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry("com/pulse/cobol/runner/CobolDiscoverySparkJobRunner.class") != null;
        } catch (IOException ex) {
            return false;
        }
    }

    private void runCommand(Path workDir, Consumer<Map<String, Object>> progressSink, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    progressSink.accept(event("process", line, Map.of("command", command[0])));
                }
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed (" + exit + "): " + String.join(" ", command));
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> event(String type, String message, Map<String, Object> detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("message", message);
        event.put("detail", detail);
        event.put("timestamp", Instant.now().toString());
        return event;
    }
}
