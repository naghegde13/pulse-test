package com.pulse.codegen.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads classpath-packaged codegen example files at startup and exposes them
 * for the chat-tool and blueprint-catalog surfaces.
 *
 * <p>Example files live at {@code codegen-examples/**\/*.{sql,py,yaml,yml}} under the
 * backend resources root. Each blueprint declares the example file names it maps to
 * via {@code blueprints.codegen_hints.example_keys}. The {@link Example} record carries
 * a Monaco-compatible {@code language} hint derived from the file extension so the
 * frontend code viewer can render proper syntax highlighting without language sniffing.
 */
@Service
public class CodegenExampleService {

    private static final int MAX_EXAMPLES = 3;
    private static final String LANG_PYTHON = "python";
    private static final String LANG_SQL = "sql";
    private static final String LANG_YAML = "yaml";
    private static final String LANG_PLAINTEXT = "plaintext";

    private final BlueprintRepository blueprintRepository;
    private final Map<String, Example> examplesByKey = new LinkedHashMap<>();

    public CodegenExampleService(BlueprintRepository blueprintRepository) {
        this.blueprintRepository = blueprintRepository;
    }

    /**
     * One example file. {@code key} is the file name without extension (e.g.
     * {@code bulk_backfill_date_range}). {@code language} is the Monaco language id
     * derived from the file extension. {@code content} is the raw file body.
     */
    public record Example(String key, String language, String content) {}

    @PostConstruct
    public void loadExamples() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(
                getClass().getClassLoader());
        loadMatching(resolver, "classpath*:codegen-examples/**/*.sql");
        loadMatching(resolver, "classpath*:codegen-examples/**/*.py");
        loadMatching(resolver, "classpath*:codegen-examples/**/*.yaml");
        loadMatching(resolver, "classpath*:codegen-examples/**/*.yml");
    }

    /**
     * Resolve the example_keys configured on this blueprint into rich {@link Example}
     * records (key + language + content).
     */
    public List<Example> getExamplesForBlueprint(String blueprintKey) {
        if (blueprintKey == null || blueprintKey.isBlank()) return List.of();
        Blueprint bp = blueprintRepository.findByBlueprintKey(blueprintKey).orElse(null);
        if (bp == null || bp.getCodegenHints() == null) return List.of();

        Object exampleKeys = bp.getCodegenHints().get("example_keys");
        if (!(exampleKeys instanceof List<?> keys) || keys.isEmpty()) return List.of();

        List<Example> out = new ArrayList<>();
        for (Object raw : keys) {
            if (raw == null) continue;
            Example example = examplesByKey.get(raw.toString());
            if (example != null) {
                out.add(example);
                if (out.size() >= MAX_EXAMPLES) break;
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Map<String, Example> getLoadedExamples() {
        return Collections.unmodifiableMap(examplesByKey);
    }

    private void loadMatching(PathMatchingResourcePatternResolver resolver, String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank()) continue;
                String key = stripExtension(filename);
                String language = languageFor(filename);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String content = reader.lines().collect(Collectors.joining("\n"));
                    examplesByKey.put(key, new Example(key, language, content));
                }
            }
        } catch (Exception ignored) {
            // best-effort: missing classpath entries are fine in tests
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String languageFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".py")) return LANG_PYTHON;
        if (lower.endsWith(".sql")) return LANG_SQL;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return LANG_YAML;
        return LANG_PLAINTEXT;
    }
}
