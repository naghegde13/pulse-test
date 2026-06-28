package com.pulse.command.draft;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record DraftRef(String raw, String kind, int ordinal) {

    private static final Pattern EXACT_PATTERN = Pattern.compile("^draft:([a-z][a-z0-9_-]*):(\\d+)$");
    private static final Set<String> SUPPORTED_KINDS = Set.of("pipeline", "connector");

    public static Optional<DraftRef> tryParse(String value) {
        if (value == null || !value.contains("draft:")) {
            return Optional.empty();
        }

        var matcher = EXACT_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref values must use the exact form draft:<kind>:<ordinal>; got '" + value + "'");
        }

        String kind = matcher.group(1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_KINDS.contains(kind)) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Unsupported draft ref kind '" + kind + "' in '" + value + "'");
        }

        int ordinal;
        try {
            ordinal = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref ordinal must be numeric in '" + value + "'");
        }
        if (ordinal <= 0) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref ordinal must be one-based and positive in '" + value + "'");
        }

        return Optional.of(new DraftRef(value, kind, ordinal));
    }
}
