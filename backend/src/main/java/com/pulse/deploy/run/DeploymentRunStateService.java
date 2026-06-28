package com.pulse.deploy.run;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.repository.DeploymentRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 4 — applies validated state transitions to {@link DeploymentRun}
 * rows. Rejects illegal transitions with a stable
 * {@link IllegalRunTransitionException}; future phases bind that to a
 * {@code 409} response when invoked from controller endpoints.
 */
@Service
public class DeploymentRunStateService {

    private final DeploymentRunRepository runRepo;

    public DeploymentRunStateService(DeploymentRunRepository runRepo) {
        this.runRepo = runRepo;
    }

    @Transactional
    public DeploymentRun transition(String runId, DeploymentRunState toState, String reason) {
        DeploymentRun run = runRepo.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", runId));
        DeploymentRunState from = DeploymentRunState.parse(run.getStatus());
        if (!DeploymentRunState.canTransition(from, toState)) {
            throw new IllegalRunTransitionException(from, toState);
        }
        run.setStatus(toState.name());
        if (toState.isTerminal() && run.getFinishedAt() == null) {
            run.setFinishedAt(java.time.Instant.now());
        }
        if (toState == DeploymentRunState.PREFLIGHT_FAILED
                || toState == DeploymentRunState.FAILED
                || toState == DeploymentRunState.TIMED_OUT) {
            run.setFailureReason(reason);
        }
        return runRepo.save(run);
    }

    /** Thrown when a caller asks for an illegal {@link DeploymentRunState} transition. */
    public static class IllegalRunTransitionException extends RuntimeException {
        private final DeploymentRunState from;
        private final DeploymentRunState to;

        public IllegalRunTransitionException(DeploymentRunState from, DeploymentRunState to) {
            super("Illegal deployment run transition: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public DeploymentRunState getFrom() { return from; }
        public DeploymentRunState getTo() { return to; }
    }
}
