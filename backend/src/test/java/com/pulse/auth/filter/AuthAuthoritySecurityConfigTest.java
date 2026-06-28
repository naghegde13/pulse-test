package com.pulse.auth.filter;

import com.pulse.auth.controller.AuthController;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.repository.UserRepository;
import com.pulse.auth.service.JwtService;
import com.pulse.auth.service.TenantService;
import com.pulse.config.SecurityConfig;
import com.pulse.runtime.controller.RuntimeAuthorityController;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.model.SecretAuthorityKind;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, RuntimeAuthorityController.class})
@Import({SecurityConfig.class, JwtService.class})
@TestPropertySource(properties = {
        "pulse.auth.enabled=true",
        "pulse.cors.allowed-origins=http://localhost:3000",
        "pulse.jwt.secret=pulse-dev-secret-key-change-in-production-minimum-256-bits!!",
        "pulse.jwt.access-token-expiry=28800"
})
class AuthAuthoritySecurityConfigTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    @MockitoBean private TenantService tenantService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private RuntimeAuthorityService runtimeAuthorityService;

    @Test
    void protectedApiRejectsMissingBearerTokenWhenAuthEnabled() throws Exception {
        mockMvc.perform(get("/api/v1/runtime-authority"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authMeReturnsJwtPrincipalAndIgnoresSpoofHeadersWhenAuthEnabled() throws Exception {
        String token = jwtService.generateToken(
                "user-42",
                "eng@acme.dev",
                "Acme Engineer",
                "acme-lending",
                "DATA_ENGINEER",
                List.of("pipeline:read", "chat:use"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .header(ActorResolverService.HEADER_USER_ID, "spoofed-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, "spoofed-tenant")
                        .header(ActorResolverService.HEADER_ROLES, "PLATFORM_ADMIN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-42"))
                .andExpect(jsonPath("$.email").value("eng@acme.dev"))
                .andExpect(jsonPath("$.displayName").value("Acme Engineer"))
                .andExpect(jsonPath("$.tenantId").value("acme-lending"))
                .andExpect(jsonPath("$.role").value("DATA_ENGINEER"));
    }

    @Test
    void runtimeAuthorityAllowsValidBearerTokenWhenAuthEnabled() throws Exception {
        when(runtimeAuthorityService.getAuthority()).thenReturn(new RuntimeAuthority(
                RuntimePersona.GCP_PULSE,
                "GCP Pulse",
                Set.of("GCP_COMPOSER_DATAPROC"),
                Set.of("GCP"),
                Set.of("COMPOSER"),
                Set.of("DATAPROC_SERVERLESS"),
                Set.of("GCS"),
                Set.of("BIGQUERY"),
                Set.of(),
                Map.of("gold", List.of("bq_native")),
                SecretAuthorityKind.GCP_SECRET_MANAGER,
                "arch-004.v1"));

        String token = jwtService.generateToken(
                "user-42", "eng@acme.dev", "Acme Engineer", "acme-lending",
                "DATA_ENGINEER", List.of("commands:view"));

        mockMvc.perform(get("/api/v1/runtime-authority")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePersona").value("GCP_PULSE"))
                .andExpect(jsonPath("$.displayName").value("GCP Pulse"))
                .andExpect(jsonPath("$.secretAuthority", containsString("GCP_SECRET_MANAGER")));
    }
}
