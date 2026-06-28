package com.pulse.runtime.service;

import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * Validates runtime binding probe evidence.
 *
 * <p>PKT-0014: validates with explicit {@code validationKind} semantics.
 * STUB validation checks storage root completeness but can never claim
 * live-GCP or live-HDFS readiness. Live probes (LIVE_GCP, LIVE_HDFS)
 * will land in a future phase.
 *
 * <p>The {@code validationKind} is always stamped on the binding row so
 * downstream readiness aggregation can distinguish stub from live.
 */
@Service
public class RuntimeBindingValidationService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeBindingValidationService.class);

    /** Legal validation kinds. */
    private static final Set<String> LEGAL_VALIDATION_KINDS = Set.of("STUB", "LIVE_GCP", "LIVE_HDFS");

    private final RuntimeBindingRepository bindingRepository;

    public RuntimeBindingValidationService(RuntimeBindingRepository bindingRepository) {
        this.bindingRepository = bindingRepository;
    }

    // ------------------------------------------------------------------ result

    /**
     * Result of a binding validation probe.
     */
    public record ValidationResult(boolean valid, String message, Instant validatedAt,
                                    String validationKind) {
        /** Backwards-compatible constructor. */
        public ValidationResult(boolean valid, String message, Instant validatedAt) {
            this(valid, message, validatedAt, "STUB");
        }
    }

    // ------------------------------------------------------------------ validate

    /**
     * Validate an already-loaded entity using stub validation.
     * Stub validation checks storage root completeness but always
     * stamps {@code validationKind = STUB}.
     */
    @Transactional
    public RuntimeBinding validate(RuntimeBinding binding) {
        return validate(binding, "STUB");
    }

    /**
     * Validate an already-loaded entity with the specified validation kind.
     *
     * <p>PKT-0014 guard: only STUB validation is currently implemented.
     * Requesting LIVE_GCP or LIVE_HDFS fails with an explicit message
     * so callers cannot overclaim live readiness through stub validation.
     */
    @Transactional
    public RuntimeBinding validate(RuntimeBinding binding, String requestedKind) {
        String kind = requestedKind != null ? requestedKind.trim().toUpperCase() : "STUB";
        if (!LEGAL_VALIDATION_KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                    "Illegal validationKind: " + kind
                            + "; legal values: " + LEGAL_VALIDATION_KINDS);
        }

        Instant now = Instant.now();

        // Guard: live validation kinds are not yet implemented.
        // Stub validation cannot claim live readiness.
        if (!"STUB".equals(kind)) {
            binding.setValidationStatus("FAILED");
            binding.setValidationKind(kind);
            binding.setValidatedAt(now);
            binding.setValidationError(
                    kind + " validation is not yet implemented; "
                            + "stub validation cannot claim live readiness");
            RuntimeBinding saved = bindingRepository.save(binding);
            log.warn("Rejected live validation request: id={}, kind={}, env={}",
                    saved.getId(), kind, saved.getEnvironment());
            return saved;
        }

        // STUB validation: check storage root completeness
        binding.setValidationKind("STUB");
        if (binding.hasCompleteRoots()) {
            binding.setValidationStatus("VALIDATED");
            binding.setValidatedAt(now);
            binding.setValidationError(null);
        } else {
            binding.setValidationStatus("FAILED");
            binding.setValidatedAt(now);
            binding.setValidationError("Incomplete storage roots");
        }

        RuntimeBinding saved = bindingRepository.save(binding);
        log.info("Validated runtime binding (stub): id={}, env={}, kind={}, status={}",
                saved.getId(), saved.getEnvironment(),
                saved.getBindingKind(), saved.getValidationStatus());
        return saved;
    }

    /**
     * Validate the runtime binding identified by {@code bindingId}.
     *
     * @param bindingId the ULID of the runtime binding to validate
     * @return a {@link ValidationResult} indicating success or failure
     */
    @Transactional
    public ValidationResult validateBinding(String bindingId) {
        return validateBinding(bindingId, "STUB");
    }

    /**
     * Validate with an explicit validation kind.
     */
    @Transactional
    public ValidationResult validateBinding(String bindingId, String validationKind) {
        RuntimeBinding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime binding not found: " + bindingId));

        RuntimeBinding validated = validate(binding, validationKind);

        boolean valid = "VALIDATED".equals(validated.getValidationStatus());
        String message = valid ? "Stub validation passed" : validated.getValidationError();
        return new ValidationResult(valid, message, validated.getValidatedAt(),
                validated.getValidationKind());
    }
}
