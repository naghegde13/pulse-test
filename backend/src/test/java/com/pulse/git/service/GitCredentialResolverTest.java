package com.pulse.git.service;

import com.pulse.git.identity.UserGitIdentity;
import com.pulse.secret.service.GcpSecretManagerService;
import com.pulse.secret.service.SecretManagerException;
import com.pulse.secret.service.SecretReferenceService;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitCredentialResolverTest {

    private final SecretReferenceService secretReferenceService = new SecretReferenceService();

    private GitCredentialResolver resolver(GcpSecretManagerService bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<GcpSecretManagerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return new GitCredentialResolver(provider, secretReferenceService, "dev");
    }

    @Test
    void validateReference_null_allowed() {
        assertDoesNotThrow(() -> resolver(null).validateReference(null));
    }

    @Test
    void validateReference_gcpSmReference_allowed() {
        assertDoesNotThrow(() -> resolver(null).validateReference(
                "gcp-sm://projects/pulse-dev/secrets/pulse-dev-home-lending-git-pat/versions/latest"));
    }

    @Test
    void validateReference_plainId_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver(null).validateReference("some-secret-id"));
    }

    @Test
    void validateReference_plaintext_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver(null).validateReference("ghp_abcd1234"));
    }

    @Test
    void resolveHttpsCredentials_nullCredentialReference_returnsEmpty() {
        UserGitIdentity identity = new UserGitIdentity();
        identity.setCredentialReference(null);
        Optional<UsernamePasswordCredentialsProvider> result = resolver(null).resolveHttpsCredentials(identity);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveHttpsCredentials_validRef_callsGcpSm_returnsProvider() throws Exception {
        GcpSecretManagerService mgr = mock(GcpSecretManagerService.class);
        // Phase 6 closeout: resolver now honors the FULL gcp-sm:// URI.
        String fullRef = "gcp-sm://projects/pulse-dev/secrets/pulse-dev-home-lending-git-pat/versions/latest";
        when(mgr.getSecretValueByReference(fullRef)).thenReturn("ghp_token");
        UserGitIdentity identity = new UserGitIdentity();
        identity.setCredentialReference(fullRef);

        UsernamePasswordCredentialsProvider provider = resolver(mgr).resolveHttpsCredentials(identity).orElseThrow();

        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.get(new URIish("https://github.com/home-lending/repo.git"), username, password));
        assertEquals("token", username.getValue());
        assertArrayEquals("ghp_token".toCharArray(), password.getValue());
        verify(mgr).getSecretValueByReference(fullRef);
    }

    @Test
    void resolveHttpsCredentials_gcpSmThrows_wrapsInGitAuthException() {
        GcpSecretManagerService mgr = mock(GcpSecretManagerService.class);
        when(mgr.getSecretValueByReference(anyString()))
                .thenThrow(new SecretManagerException("boom"));
        UserGitIdentity identity = new UserGitIdentity();
        identity.setCredentialReference("gcp-sm://projects/pulse-dev/secrets/pulse-dev-home-lending-git-pat/versions/latest");

        assertThrows(GitAuthenticationException.class,
                () -> resolver(mgr).resolveHttpsCredentials(identity));
    }
}
