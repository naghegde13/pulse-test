package com.pulse.expression.service;

import com.pulse.expression.service.ExpressionValidationService.Diagnostic;
import com.pulse.expression.service.ExpressionValidationService.InputSchema;
import com.pulse.expression.service.ExpressionValidationService.ReferencedColumn;
import com.pulse.expression.service.ExpressionValidationService.SchemaColumn;
import com.pulse.expression.service.ExpressionValidationService.ValidationRequest;
import com.pulse.expression.service.ExpressionValidationService.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionValidationServiceTest {

    private final ExpressionValidationService service = new ExpressionValidationService();

    @Test
    void validate_simpleArithmeticIsValid_andColumnRefsCollected() {
        ValidationResult result = service.validate(new ValidationRequest(
                "balance * 1.05",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("balance", "decimal")))),
                null));
        assertTrue(result.valid());
        List<ReferencedColumn> refs = result.referencedColumns();
        assertTrue(refs.stream().anyMatch(r -> "balance".equalsIgnoreCase(r.column())));
    }

    @Test
    void validate_unknownColumnRaisesWarning_butDoesNotBlock() {
        ValidationResult result = service.validate(new ValidationRequest(
                "missing_col + 1",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("balance", "decimal")))),
                null));
        // Parses fine; columns checked separately. Unqualified missing column is
        // a warning (not an error) so the user can keep typing.
        List<Diagnostic> warnings = result.diagnostics().stream()
                .filter(d -> "warning".equals(d.severity())).toList();
        assertEquals(1, warnings.size());
        assertEquals("UNKNOWN_COLUMN", warnings.get(0).code());
        assertTrue(warnings.get(0).message().contains("missing_col"));
    }

    @Test
    void validate_qualifiedReferenceWithUnknownPortIsAnError() {
        ValidationResult result = service.validate(new ValidationRequest(
                "wrongport.col1 + 1",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("col1", "int")))),
                null));
        assertFalse(result.valid());
        List<Diagnostic> errors = result.diagnostics().stream()
                .filter(d -> "error".equals(d.severity())).toList();
        assertTrue(errors.stream().anyMatch(d -> "UNKNOWN_PORT".equals(d.code())));
    }

    @Test
    void validate_qualifiedReferenceWithUnknownColumnOnKnownPortIsAnError() {
        ValidationResult result = service.validate(new ValidationRequest(
                "primary.does_not_exist",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("col1", "int")))),
                null));
        assertFalse(result.valid());
        List<Diagnostic> errors = result.diagnostics().stream()
                .filter(d -> "error".equals(d.severity())).toList();
        assertTrue(errors.stream().anyMatch(d -> "UNKNOWN_COLUMN".equals(d.code())));
    }

    @Test
    void validate_predicateKindAcceptsBooleanExpression() {
        ValidationResult result = service.validate(new ValidationRequest(
                "balance > 0 AND status IN ('ACTIVE','DELINQUENT')",
                "predicate",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("balance", "decimal"),
                        new SchemaColumn("status", "string")))),
                null));
        assertTrue(result.valid(),
                "expected predicate to validate; got diagnostics: " + result.diagnostics());
        List<String> refNames = result.referencedColumns().stream()
                .map(r -> r.column().toLowerCase()).toList();
        assertTrue(refNames.contains("balance"));
        assertTrue(refNames.contains("status"));
    }

    @Test
    void validate_emptyExpressionReturnsErrorDiagnostic() {
        ValidationResult result = service.validate(new ValidationRequest(
                "  ", "value", List.of(), null));
        assertFalse(result.valid());
        assertEquals("EMPTY_EXPRESSION", result.diagnostics().get(0).code());
    }

    @Test
    void validate_syntaxErrorReturnsParseDiagnosticAndIsInvalid() {
        ValidationResult result = service.validate(new ValidationRequest(
                "balance + + ",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("balance", "decimal")))),
                null));
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "PARSE_ERROR".equals(d.code())));
    }

    @Test
    void validate_currentDateAndCurrentTimestampNotFlaggedAsUnknownColumns() {
        ValidationResult result = service.validate(new ValidationRequest(
                "datediff(current_date, origination_date)",
                "value",
                List.of(new InputSchema("primary", List.of(
                        new SchemaColumn("origination_date", "date")))),
                null));
        // current_date is a SQL keyword; must not show up as an UNKNOWN_COLUMN.
        assertFalse(result.diagnostics().stream()
                .anyMatch(d -> "UNKNOWN_COLUMN".equals(d.code())
                        && d.message().toLowerCase().contains("current_date")));
    }

    @Test
    void validate_multiPortJoinPredicateChecksBothSides() {
        ValidationResult result = service.validate(new ValidationRequest(
                "left.account_id = right.account_id AND left.as_of_date >= right.as_of_date",
                "predicate",
                List.of(
                        new InputSchema("left", List.of(
                                new SchemaColumn("account_id", "int"),
                                new SchemaColumn("as_of_date", "date"))),
                        new InputSchema("right", List.of(
                                new SchemaColumn("account_id", "int"),
                                new SchemaColumn("as_of_date", "date")))
                ),
                null));
        assertTrue(result.valid(),
                "join predicate should validate; got: " + result.diagnostics());
    }
}
