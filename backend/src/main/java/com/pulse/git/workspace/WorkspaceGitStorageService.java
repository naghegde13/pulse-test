package com.pulse.git.workspace;

import com.pulse.git.service.LocalGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class WorkspaceGitStorageService {

    private final LocalGitService localGitService;
    private final Path workspaceRoot;

    public WorkspaceGitStorageService(LocalGitService localGitService,
                                      @Value("${pulse.git.workspace-root:/tmp/pulse-workspaces}") String workspaceRoot) {
        this.localGitService = localGitService;
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public Path checkoutPath(String workspaceId) {
        return workspaceRoot.resolve("workspaces").resolve(workspaceId).normalize();
    }

    public String branchName(String pipelineName, int revision, String actorUserId) {
        return "pulse/%s/r%d-%s".formatted(slug(pipelineName), revision, slug(actorUserId));
    }

    public String branchNameWithWorkspaceSuffix(String pipelineName, int revision, String actorUserId, String workspaceId) {
        String suffix = workspaceId == null || workspaceId.length() < 8
                ? "workspace"
                : workspaceId.substring(workspaceId.length() - 8).toLowerCase(Locale.ROOT);
        return branchName(pipelineName, revision, actorUserId) + "-" + suffix;
    }

    public void prepareCheckout(DeveloperWorkspace workspace) {
        Path checkout = Path.of(workspace.getCheckoutPath()).normalize();
        if (!checkout.startsWith(workspaceRoot.normalize())) {
            throw new IllegalArgumentException("Workspace checkout path escapes workspace root");
        }
        try {
            Files.createDirectories(checkout);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create workspace checkout " + checkout, e);
        }
        boolean initialized = !Files.exists(checkout.resolve(".git"));
        if (initialized) {
            localGitService.initRepo(checkout.toString(), workspace.getBranchName());
        }
        if (!initialized) {
            localGitService.checkoutBranch(checkout.toString(), workspace.getBranchName());
        }
        refreshStatus(workspace);
    }

    public void refreshStatus(DeveloperWorkspace workspace) {
        Path checkout = Path.of(workspace.getCheckoutPath());
        if (!Files.exists(checkout) || !Files.exists(checkout.resolve(".git"))) {
            workspace.setWorkingTreeStatus("missing");
            workspace.setDirtyFileCount(0);
            workspace.setHeadSha(null);
            workspace.setHeadTreeSha(null);
            return;
        }
        LocalGitService.WorkingTreeStatus status = localGitService.getWorkingTreeStatus(checkout.toString());
        workspace.setWorkingTreeStatus(status.status());
        workspace.setDirtyFileCount(status.dirtyFileCount());
        workspace.setHeadSha(localGitService.getHeadSha(checkout.toString()));
        workspace.setHeadTreeSha(localGitService.getHeadTreeSha(checkout.toString()));
    }

    public List<String> changedPaths(DeveloperWorkspace workspace) {
        Path checkout = Path.of(workspace.getCheckoutPath());
        if (!Files.exists(checkout.resolve(".git"))) {
            return List.of();
        }
        try (Git git = Git.open(new File(checkout.toString()))) {
            var status = git.status().call();
            List<String> paths = new ArrayList<>();
            paths.addAll(status.getAdded());
            paths.addAll(status.getChanged());
            paths.addAll(status.getModified());
            paths.addAll(status.getMissing());
            paths.addAll(status.getRemoved());
            paths.addAll(status.getUntracked());
            return paths.stream().distinct().sorted().toList();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git diff status failed at " + checkout, e);
        }
    }

    public static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
