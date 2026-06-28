package com.pulse.git.workspace;

import com.pulse.git.service.LocalGitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceGitStorageServiceTest {

    @TempDir Path tempDir;

    @Test
    void branchNamesAreDeterministicAndCollisionSuffixUsesWorkspaceId() {
        WorkspaceGitStorageService service = new WorkspaceGitStorageService(new LocalGitService(), tempDir.toString());

        assertEquals("pulse/customer-orders/r7-amy-builder",
                service.branchName("Customer Orders", 7, "Amy Builder"));
        assertEquals("pulse/customer-orders/r7-amy-builder-12345678",
                service.branchNameWithWorkspaceSuffix("Customer Orders", 7, "Amy Builder", "01ABCDEF12345678"));
    }

    @Test
    void prepareCheckoutCreatesIsolatedRepoAndReportsUnbornStatus() {
        WorkspaceGitStorageService service = new WorkspaceGitStorageService(new LocalGitService(), tempDir.toString());
        DeveloperWorkspace workspace = new DeveloperWorkspace();
        workspace.setId("workspace-1");
        workspace.setBaseBranch("main");
        workspace.setBranchName("pulse/customer-orders/r1-builder");
        workspace.setCheckoutPath(service.checkoutPath("workspace-1").toString());

        service.prepareCheckout(workspace);

        assertTrue(Files.exists(Path.of(workspace.getCheckoutPath()).resolve(".git")));
        assertEquals("unborn", workspace.getWorkingTreeStatus());
        assertEquals(0, workspace.getDirtyFileCount());
    }
}
