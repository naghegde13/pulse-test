package com.pulse.codegen.service;

import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.repository.DbtAssetRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Branch-aware cache sync for the dbt asset registry.
 *
 * <p>Reads the tenant's git working tree, parses dbt model/snapshot files, and upserts
 * {@link DbtAsset} rows scoped to the active branch. When the tenant has not yet
 * onboarded a git repo (Agent B work package), the service returns an empty result
 * so that reuse-candidate searches fall back to generate-from-scratch.
 */
@Service
public class DbtGitSyncService {

    private static final Pattern MODEL_CONFIG_PATTERN = Pattern.compile(
            "\\{\\{\\s*config\\(([^}]*)\\)\\s*}}", Pattern.DOTALL);
    private static final Pattern MATERIALIZED_PATTERN = Pattern.compile(
            "materialized\\s*=\\s*['\"]([a-z_]+)['\"]");
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "tags\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern TAG_ITEM_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]");

    private final GitRepoRepository gitRepoRepository;
    private final DbtAssetRepository dbtAssetRepository;
    private final DomainRepository domainRepository;

    public DbtGitSyncService(GitRepoRepository gitRepoRepository,
                             DbtAssetRepository dbtAssetRepository,
                             DomainRepository domainRepository) {
        this.gitRepoRepository = gitRepoRepository;
        this.dbtAssetRepository = dbtAssetRepository;
        this.domainRepository = domainRepository;
    }

    public SyncResult syncFromFileTree(String tenantId) {
        return syncFromFileTree(tenantId, null);
    }

    @Transactional
    public SyncResult syncFromFileTree(String tenantId, String branchOverride) {
        Optional<GitRepo> tenantRepoOpt = resolveTenantRepo(tenantId);
        if (tenantRepoOpt.isEmpty()) {
            return SyncResult.empty("no tenant repo onboarded");
        }
        GitRepo tenantRepo = tenantRepoOpt.get();

        String localPath = resolveLocalPath(tenantRepo);
        if (localPath == null || localPath.isBlank()) {
            return SyncResult.empty("tenant repo has no local working tree");
        }

        String branch = (branchOverride != null && !branchOverride.isBlank())
                ? branchOverride
                : resolveCurrentBranch(tenantRepo);
        if (branch == null || branch.isBlank()) {
            return SyncResult.empty("repo has no commits on any branch");
        }

        Path repoRoot = Path.of(localPath);
        if (!Files.isDirectory(repoRoot)) {
            return SyncResult.empty("local path does not exist: " + localPath);
        }

        String gitSha = resolveHeadSha(repoRoot);
        Map<String, Domain> domainsByName = new LinkedHashMap<>();
        for (Domain d : domainRepository.findAll()) {
            if (tenantId.equals(d.getTenantId()) && d.getName() != null) {
                domainsByName.put(slugify(d.getName()), d);
            }
        }

        List<ParsedAsset> parsed = new ArrayList<>();
        parsed.addAll(walkDbtDirectory(repoRoot.resolve("dbt_project/models"), "model"));
        parsed.addAll(walkDbtDirectory(repoRoot.resolve("dbt_project/snapshots"), "snapshot"));

        Set<String> touchedDomainIds = new HashSet<>();
        List<DbtAsset> upserted = new ArrayList<>();
        for (ParsedAsset asset : parsed) {
            String domainSlug = asset.domainSlug();
            Domain domain = domainsByName.get(domainSlug);
            if (domain == null) continue;
            touchedDomainIds.add(domain.getId());

            Optional<DbtAsset> existing = dbtAssetRepository
                    .findByDomainIdAndBranchAndAssetNameAndAssetType(
                            domain.getId(), branch, asset.assetName(), asset.assetType());
            DbtAsset row = existing.orElseGet(DbtAsset::new);
            row.setDomainId(domain.getId());
            row.setBranch(branch);
            row.setGitSha(gitSha);
            row.setProjectName("dbt_project");
            row.setAssetName(asset.assetName());
            row.setAssetType(asset.assetType());
            row.setPath(asset.relativePath());
            row.setTags(asset.tags());
            if (row.getMetadata() == null) row.setMetadata(Map.of());
            upserted.add(dbtAssetRepository.save(row));
        }

        Set<String> keepKeys = new HashSet<>();
        for (DbtAsset row : upserted) {
            keepKeys.add(row.getDomainId() + "|" + row.getAssetType() + "|" + row.getAssetName());
        }
        for (String domainId : touchedDomainIds) {
            List<DbtAsset> current = dbtAssetRepository
                    .findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc(domainId, branch);
            for (DbtAsset existing : current) {
                String key = existing.getDomainId() + "|" + existing.getAssetType() + "|" + existing.getAssetName();
                if (!keepKeys.contains(key)) {
                    dbtAssetRepository.delete(existing);
                }
            }
        }

        return new SyncResult(branch, gitSha, upserted.size(), touchedDomainIds.size(), "ok");
    }

    @Transactional
    public void invalidateBranch(String tenantId, String branch) {
        if (branch == null || branch.isBlank()) return;
        for (Domain domain : domainRepository.findAll()) {
            if (tenantId.equals(domain.getTenantId())) {
                dbtAssetRepository.deleteByDomainIdAndBranch(domain.getId(), branch);
            }
        }
    }

    private Optional<GitRepo> resolveTenantRepo(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        return gitRepoRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(DbtGitSyncService::isTenantScoped)
                .findFirst();
    }

    private static boolean isTenantScoped(GitRepo repo) {
        Map<String, Object> metadata = repo.getMetadata();
        if (metadata != null) {
            Object scope = metadata.get("scope");
            if (scope != null && "TENANT".equalsIgnoreCase(scope.toString())) {
                return true;
            }
        }
        return repo.getPipelineId() == null && repo.getDomainId() == null;
    }

    private String resolveLocalPath(GitRepo repo) {
        Map<String, Object> metadata = repo.getMetadata();
        if (metadata != null && metadata.get("localPath") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private String resolveCurrentBranch(GitRepo repo) {
        Map<String, Object> metadata = repo.getMetadata();
        if (metadata != null && metadata.get("currentBranch") instanceof String s && !s.isBlank()) {
            return s;
        }
        return repo.getDefaultBranch();
    }

    private String resolveHeadSha(Path repoRoot) {
        Path head = repoRoot.resolve(".git/HEAD");
        if (!Files.isRegularFile(head)) return null;
        try {
            String contents = Files.readString(head).trim();
            if (contents.startsWith("ref: ")) {
                Path ref = repoRoot.resolve(".git").resolve(contents.substring(5).trim());
                if (Files.isRegularFile(ref)) {
                    return Files.readString(ref).trim();
                }
                return null;
            }
            return contents.isBlank() ? null : contents;
        } catch (Exception e) {
            return null;
        }
    }

    private List<ParsedAsset> walkDbtDirectory(Path root, String assetType) {
        if (!Files.isDirectory(root)) return List.of();
        List<ParsedAsset> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .forEach(p -> {
                        ParsedAsset parsed = parseDbtFile(root, p, assetType);
                        if (parsed != null) out.add(parsed);
                    });
        } catch (Exception ignored) {
            // best-effort walk
        }
        return out;
    }

    private ParsedAsset parseDbtFile(Path root, Path file, String assetType) {
        try {
            String content = Files.readString(file);
            String assetName = file.getFileName().toString().replaceFirst("\\.sql$", "");
            Path relative = root.getParent().getParent().relativize(file);
            String relativePath = relative.toString().replace('\\', '/');
            String domainSlug = inferDomainSlugFromPath(root.relativize(file));
            List<String> tags = extractTags(content);
            return new ParsedAsset(assetName, assetType, relativePath, domainSlug, tags);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String inferDomainSlugFromPath(Path relativeToDbtRoot) {
        Path parent = relativeToDbtRoot.getParent();
        if (parent == null) return "";
        Path folder = parent.getFileName();
        return folder != null ? slugify(folder.toString()) : "";
    }

    private List<String> extractTags(String content) {
        Matcher configMatcher = MODEL_CONFIG_PATTERN.matcher(content);
        if (!configMatcher.find()) return List.of();
        String configBody = configMatcher.group(1);
        Matcher tagMatcher = TAG_PATTERN.matcher(configBody);
        if (!tagMatcher.find()) return List.of();
        List<String> tags = new ArrayList<>();
        Matcher itemMatcher = TAG_ITEM_PATTERN.matcher(tagMatcher.group(1));
        while (itemMatcher.find()) {
            tags.add(itemMatcher.group(1));
        }
        return tags;
    }

    private String slugify(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    public record ParsedAsset(
            String assetName,
            String assetType,
            String relativePath,
            String domainSlug,
            List<String> tags
    ) {
        public ParsedAsset {
            tags = tags == null ? List.of() : List.copyOf(tags);
            Objects.requireNonNull(assetName, "assetName");
            Objects.requireNonNull(assetType, "assetType");
        }
    }

    public record SyncResult(String branch, String gitSha, int assetCount, int domainCount, String message) {
        public static SyncResult empty(String message) {
            return new SyncResult(null, null, 0, 0, message);
        }
    }
}
