package com.pulse.auth.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.UserRole;
import com.pulse.auth.repository.UserRepository;
import com.pulse.auth.service.JwtService;
import com.pulse.auth.service.TenantService;
import com.pulse.config.TenantConfig.TenantDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${pulse.auth.enabled:false}")
    private boolean authEnabled;

    public AuthController(TenantService tenantService,
                          UserRepository userRepository,
                          JwtService jwtService) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        PulseUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("Invalid credentials");
        }

        List<String> permissions = getPermissionsForRole(user.getRole());
        String token = jwtService.generateToken(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getTenantId(),
                user.getRole().name(), permissions);

        return ResponseEntity.ok(Map.of(
                "accessToken", token,
                "user", Map.of(
                        "id", user.getId(),
                        "email", user.getEmail(),
                        "displayName", user.getDisplayName(),
                        "role", user.getRole().name(),
                        "tenantId", user.getTenantId(),
                        "permissions", permissions
                )
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        if (authEnabled) {
            // Derive identity from authenticated JWT principal
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
                return ResponseEntity.ok(Map.of(
                        "id", principal.userId(),
                        "email", principal.email(),
                        "displayName", principal.displayName(),
                        "role", principal.role(),
                        "tenantId", principal.tenantId(),
                        "permissions", principal.permissions()
                ));
            }
            return ResponseEntity.status(401).build();
        }

        // Dev/test stub mode: return permissive dev user
        List<TenantDefinition> tenants = tenantService.listTenants();
        String defaultTenantId = tenants.isEmpty() ? "no-tenant" : tenants.get(0).getId();

        // Session-3 (2026-05-26): dev stub /me response declares
        // DATA_ENGINEER as the primary role for display, but the actual
        // SecurityContext anonymous authentication in dev mode is granted
        // ROLE_PLATFORM_ADMIN (and every other PulseRole) by
        // SecurityConfig.filterChain() so @PreAuthorize-gated UI flows
        // succeed without needing real JWT. The /me payload below
        // surfaces both the legacy role AND the additional dev-stub
        // authorities so the frontend can render the actual capability
        // set if it wants to.
        return ResponseEntity.ok(Map.of(
                "id", "01JUSER00000000000000000",
                "email", "builder@pulse.dev",
                "displayName", "Dev Builder",
                "role", UserRole.DATA_ENGINEER.name(),
                "additionalRoles", List.of("PLATFORM_ADMIN"),
                "tenantId", defaultTenantId,
                "permissions", List.of(
                        "pipeline:read", "pipeline:write",
                        "pipeline:deploy:dev", "producer:write",
                        "chat:use", "commands:view",
                        "admin:users", "admin:allowlist"
                )
        ));
    }

    private List<String> getPermissionsForRole(UserRole role) {
        return switch (role) {
            case CITIZEN -> List.of("pipeline:read", "pipeline:write", "chat:use");
            case DATA_ENGINEER -> List.of("pipeline:read", "pipeline:write", "pipeline:deploy:dev",
                    "producer:write", "chat:use", "commands:view");
            case DEPLOYER -> List.of("pipeline:read", "pipeline:deploy:int", "pipeline:deploy:uat",
                    "pipeline:deploy:prod", "pipeline:approve", "chat:use", "commands:view");
            case ADMIN -> List.of("pipeline:read", "pipeline:write", "pipeline:deploy:dev",
                    "pipeline:deploy:int", "pipeline:deploy:uat", "pipeline:deploy:prod",
                    "pipeline:approve", "producer:write", "admin:users", "admin:allowlist",
                    "chat:use", "commands:view");
        };
    }
}
