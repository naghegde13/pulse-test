package com.pulse.git.identity;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SU-6 / BUG-59 backend assertion: the previously-merged
 * {@code requireActor()} guard now produces TWO distinct error messages
 * — one for "no authenticated user" and one for "no tenant header" — so
 * the operator can tell at a glance which header is missing.
 */
class UserGitIdentityServiceRequireActorTest {

    private static Method requireActorMethod() throws NoSuchMethodException {
        Method m = UserGitIdentityService.class.getDeclaredMethod(
                "requireActor", CallerContext.class);
        m.setAccessible(true);
        return m;
    }

    private static void invoke(CallerContext caller) throws Throwable {
        try {
            requireActorMethod().invoke(null, caller);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static CallerContext withUserAndTenant(String userId, String tenantId) {
        return new CallerContext(userId, tenantId, java.util.Set.of(), CallerSurface.UI);
    }

    @Test
    void requireActor_throwsUserMessage_whenUserIsMissing() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(withUserAndTenant(null, "tenant-acme")));
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(
                msg.toLowerCase().contains("authenticated user")
                        || msg.toLowerCase().contains("user could not be resolved"),
                "Expected user-specific error message, got: " + msg);
        assertEquals(false, msg.toLowerCase().contains("tenant context"),
                "User-missing message should not mention 'tenant context': " + msg);
    }

    @Test
    void requireActor_throwsUserMessage_whenUserIsBlank() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(withUserAndTenant("", "tenant-acme")));
        assertTrue(ex.getMessage().toLowerCase().contains("authenticated user")
                        || ex.getMessage().toLowerCase().contains("user could not be resolved"),
                "Expected user-specific message, got: " + ex.getMessage());
    }

    @Test
    void requireActor_throwsTenantMessage_whenTenantIsMissing() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(withUserAndTenant("user-alice", null)));
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.toLowerCase().contains("tenant"),
                "Expected tenant-specific error message, got: " + msg);
        assertTrue(msg.toLowerCase().contains("x-tenant-id")
                        || msg.toLowerCase().contains("tenant context"),
                "Expected explicit X-Tenant-ID guidance in the message, got: " + msg);
    }

    @Test
    void requireActor_throwsTenantMessage_whenTenantIsBlank() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(withUserAndTenant("user-alice", "")));
        assertTrue(ex.getMessage().toLowerCase().contains("tenant"),
                "Expected tenant-specific message, got: " + ex.getMessage());
    }

    @Test
    void requireActor_throwsUserMessageFirst_whenBothMissing() {
        // Implementation contract: user check runs first so a request that
        // is missing BOTH still points the operator at the auth issue
        // (without resolving auth, the tenant header is meaningless).
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(withUserAndTenant(null, null)));
        assertTrue(ex.getMessage().toLowerCase().contains("authenticated user")
                        || ex.getMessage().toLowerCase().contains("user could not be resolved"),
                "Expected user-first message when both missing, got: " + ex.getMessage());
    }

    @Test
    void requireActor_throwsUserMessage_whenCallerIsNull() {
        Throwable ex = assertThrows(Throwable.class, () -> invoke(null));
        assertInstanceOf(IllegalArgumentException.class, ex);
        assertTrue(ex.getMessage().toLowerCase().contains("authenticated user")
                        || ex.getMessage().toLowerCase().contains("user could not be resolved"),
                "Expected user-specific message for null caller, got: " + ex.getMessage());
    }

    @Test
    void requireActor_passes_whenBothUserAndTenantPresent() {
        assertDoesNotThrow(() -> invoke(withUserAndTenant("user-alice", "tenant-acme")));
    }
}
