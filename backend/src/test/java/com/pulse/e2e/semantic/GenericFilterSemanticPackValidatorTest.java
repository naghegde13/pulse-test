package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericFilterSemanticPackValidatorTest {

    @Test
    void gcpGoldenGenericFilterPackIsInternallyConsistent() throws Exception {
        GenericFilterSemanticPackValidator.ValidationReport report = new GenericFilterSemanticPackValidator(
                new ObjectMapper().findAndRegisterModules()
        ).validate();

        assertTrue(report.failures().isEmpty(), () -> "validation failures: " + report.failures());
        assertEquals(java.util.List.of("E100", "E105", "E107"), report.selectedEmployeeIds());
        assertEquals(java.util.List.of("E101", "E102", "E103", "E104", "E106"), report.excludedEmployeeIds());
    }
}
