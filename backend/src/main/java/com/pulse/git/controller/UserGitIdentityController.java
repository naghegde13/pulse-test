package com.pulse.git.controller;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.git.identity.MaskedGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Phase 6 — write-only endpoints for user GitHub PAT classic management.
 *
 * <p>Read endpoints return {@link MaskedGitIdentity} only — never the
 * PAT value, never the full GSM secret id. The actor is resolved
 * server-side via {@link ActorResolverService}; the request bodies
 * deliberately have NO {@code pulseUserId} / {@code credentialReference}
 * / {@code secretId} fields so callers cannot spoof identity.
 */
@RestController
@RequestMapping("/api/v1/users/me/git-identity")
public class UserGitIdentityController {

    private final UserGitIdentityService identityService;
    private final ActorResolverService actorResolver;

    public UserGitIdentityController(UserGitIdentityService identityService,
                                     ActorResolverService actorResolver) {
        this.identityService = identityService;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestHeader(value = "X-Pulse-Tenant-Id", required = false) String tenantHeader) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantHeader);
        Optional<MaskedGitIdentity> masked = identityService.getMasked(caller);
        return masked.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        Map.of("error", "no_identity",
                                "message", "No GitHub identity registered for the active actor.")));
    }

    @PostMapping
    public ResponseEntity<MaskedGitIdentity> register(
            @RequestHeader(value = "X-Pulse-Tenant-Id", required = false) String tenantHeader,
            @RequestBody UserGitIdentityService.RegisterRequest request) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(identityService.register(caller, request));
    }

    @PostMapping("/rotate")
    public ResponseEntity<MaskedGitIdentity> rotate(
            @RequestHeader(value = "X-Pulse-Tenant-Id", required = false) String tenantHeader,
            @RequestBody UserGitIdentityService.RotateRequest request) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantHeader);
        return ResponseEntity.ok(identityService.rotate(caller, request));
    }

    @DeleteMapping
    public ResponseEntity<MaskedGitIdentity> revoke(
            @RequestHeader(value = "X-Pulse-Tenant-Id", required = false) String tenantHeader) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantHeader);
        return ResponseEntity.ok(identityService.revoke(caller));
    }
}
