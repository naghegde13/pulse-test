package com.pulse.auth.controller;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.UserRole;
import com.pulse.auth.repository.UserRepository;
import com.pulse.auth.service.JwtService;
import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private TenantService tenantService;
    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;

    @InjectMocks
    private AuthController controller;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // -----------------------------------------------------------------------
    //  login tests
    // -----------------------------------------------------------------------

    @Test
    void login_withValidCredentials_returnsToken() {
        // Given
        String rawPassword = "securePassword123";
        PulseUser user = buildUser("user-1", "builder@pulse.dev", rawPassword, UserRole.DATA_ENGINEER);

        when(userRepository.findByEmail("builder@pulse.dev")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(eq("user-1"), eq("builder@pulse.dev"), eq("Test User"), eq("tenant-1"),
                eq("DATA_ENGINEER"), anyList()))
                .thenReturn("jwt-token-123");

        Map<String, String> request = Map.of("email", "builder@pulse.dev", "password", rawPassword);

        // When
        ResponseEntity<Map<String, Object>> response = controller.login(request);

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("jwt-token-123", response.getBody().get("accessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) response.getBody().get("user");
        assertEquals("user-1", userMap.get("id"));
        assertEquals("builder@pulse.dev", userMap.get("email"));
        assertEquals("Test User", userMap.get("displayName"));
        assertEquals("DATA_ENGINEER", userMap.get("role"));
        verify(jwtService).generateToken(eq("user-1"), eq("builder@pulse.dev"), eq("Test User"),
                eq("tenant-1"), eq("DATA_ENGINEER"), anyList());
    }

    @Test
    void login_withInvalidPassword_throwsError() {
        // Given
        PulseUser user = buildUser("user-1", "builder@pulse.dev", "correctPassword", UserRole.DATA_ENGINEER);
        when(userRepository.findByEmail("builder@pulse.dev")).thenReturn(Optional.of(user));

        Map<String, String> request = Map.of("email", "builder@pulse.dev", "password", "wrongPassword");

        // When/Then
        assertThrows(RuntimeException.class, () -> controller.login(request));
    }

    @Test
    void login_withUnknownEmail_throwsError() {
        // Given
        when(userRepository.findByEmail("unknown@pulse.dev")).thenReturn(Optional.empty());

        Map<String, String> request = Map.of("email", "unknown@pulse.dev", "password", "password");

        // When/Then
        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.login(request));
        assertTrue(ex.getMessage().contains("Invalid credentials"));
    }

    // -----------------------------------------------------------------------
    //  me endpoint tests
    // -----------------------------------------------------------------------

    @Test
    void me_returnsCurrentUser() {
        // Given
        TenantDefinition tenant = new TenantDefinition();
        tenant.setId("tenant-home-lending");
        tenant.setName("Home Lending");
        when(tenantService.listTenants()).thenReturn(List.of(tenant));

        // When
        ResponseEntity<Map<String, Object>> response = controller.getCurrentUser();

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("01JUSER00000000000000000", response.getBody().get("id"));
        assertEquals("builder@pulse.dev", response.getBody().get("email"));
        assertEquals("Dev Builder", response.getBody().get("displayName"));
        assertEquals("DATA_ENGINEER", response.getBody().get("role"));
        assertEquals("tenant-home-lending", response.getBody().get("tenantId"));

        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) response.getBody().get("permissions");
        assertTrue(permissions.contains("pipeline:read"));
        assertTrue(permissions.contains("pipeline:write"));
    }

    @Test
    void me_noTenants_returnsNoTenantId() {
        // Given
        when(tenantService.listTenants()).thenReturn(List.of());

        // When
        ResponseEntity<Map<String, Object>> response = controller.getCurrentUser();

        // Then
        assertEquals("no-tenant", response.getBody().get("tenantId"));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private PulseUser buildUser(String id, String email, String rawPassword, UserRole role) {
        PulseUser user = new PulseUser();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName("Test User");
        user.setRole(role);
        user.setTenantId("tenant-1");
        user.setActive(true);
        return user;
    }
}
