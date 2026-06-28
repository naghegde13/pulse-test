package com.pulse.git.workspace;

import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceAuthorizationService {

    private final ActorResolverService actorResolver;
    private final AuthorizationPolicyService authPolicy;

    public WorkspaceAuthorizationService(ActorResolverService actorResolver,
                                         AuthorizationPolicyService authPolicy) {
        this.actorResolver = actorResolver;
        this.authPolicy = authPolicy;
    }

    public CallerContext enforce(String tenantId, PulseAction action) {
        CallerContext caller = actorResolver.resolve(CallerSurface.UI, tenantId);
        if (ActorResolverService.DEV_STUB_USER_ID.equals(caller.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "dev_stub_actor_not_allowed");
        }
        PolicyDecision decision = authPolicy.check(caller, action, ActionContext.forTenant(tenantId));
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.denyReason());
        }
        return caller;
    }
}
