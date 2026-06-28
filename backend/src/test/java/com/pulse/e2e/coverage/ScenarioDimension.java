package com.pulse.e2e.coverage;

import java.util.List;

public record ScenarioDimension(String name, List<String> values) {
    public ScenarioDimension {
        values = List.copyOf(values);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Dimension name is required");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Dimension values are required for " + name);
        }
    }
}
