package com.pulse.sor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PKT-0026: PII Classification Service tests.
 * Covers deterministic PII/CONFIDENTIAL detection by column name patterns.
 */
class PiiClassificationServiceTest {

    private PiiClassificationService service;

    @BeforeEach
    void setUp() {
        service = new PiiClassificationService();
    }

    // --- PII column patterns ---

    @ParameterizedTest
    @ValueSource(strings = {"ssn", "SSN", "borrower_ssn", "customer_ssn_encrypted",
            "social_security_number", "SOCIAL_SECURITY_NO"})
    void classifyColumn_ssnPatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"first_name", "FIRST_NAME", "last_name", "full_name",
            "borrower_name", "customer_name"})
    void classifyColumn_namePatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "EMAIL_ADDRESS", "e_mail", "email_addr"})
    void classifyColumn_emailPatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"phone", "phone_number", "mobile", "cell_phone"})
    void classifyColumn_phonePatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"address", "property_address", "mailing_address",
            "zip_code", "postal_code"})
    void classifyColumn_addressPatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"date_of_birth", "dob", "birth_date"})
    void classifyColumn_dobPatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"account_number", "account_no", "routing_number",
            "iban", "credit_card_number", "card_number"})
    void classifyColumn_financialIdPatterns_returnsPii(String col) {
        assertEquals("PII", service.classifyColumn(col));
    }

    // --- CONFIDENTIAL column patterns ---

    @ParameterizedTest
    @ValueSource(strings = {"salary", "SALARY", "annual_salary"})
    void classifyColumn_salaryPatterns_returnsConfidential(String col) {
        assertEquals("CONFIDENTIAL", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit_score", "fico_score", "FICO"})
    void classifyColumn_creditScorePatterns_returnsConfidential(String col) {
        assertEquals("CONFIDENTIAL", service.classifyColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"loan_amount", "interest_rate", "monthly_payment",
            "current_balance", "principal_balance", "escrow_balance",
            "ltv", "dti", "appraisal_value", "property_value"})
    void classifyColumn_loanFinancialPatterns_returnsConfidential(String col) {
        assertEquals("CONFIDENTIAL", service.classifyColumn(col));
    }

    // --- Non-sensitive columns ---

    @ParameterizedTest
    @ValueSource(strings = {"loan_id", "status", "created_at", "updated_at",
            "loan_status", "origination_date", "maturity_date", "id",
            "description", "category", "type"})
    void classifyColumn_nonSensitive_returnsNull(String col) {
        assertNull(service.classifyColumn(col));
    }

    @Test
    void classifyColumn_null_returnsNull() {
        assertNull(service.classifyColumn(null));
    }

    // --- Field list classification ---

    @Test
    void classifyFields_withPiiFields_returnsPii() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("loan_id"));
        fields.add(makeField("ssn"));
        fields.add(makeField("loan_status"));

        String result = service.classifyFields(fields);

        assertEquals("PII", result);
        assertEquals(true, fields.get(1).get("pii")); // ssn
        assertEquals(false, fields.get(0).get("pii")); // loan_id
    }

    @Test
    void classifyFields_withConfidentialOnly_returnsConfidential() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("loan_id"));
        fields.add(makeField("credit_score"));
        fields.add(makeField("loan_amount"));

        String result = service.classifyFields(fields);

        assertEquals("CONFIDENTIAL", result);
    }

    @Test
    void classifyFields_noSensitiveFields_returnsInternal() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("id"));
        fields.add(makeField("status"));
        fields.add(makeField("created_at"));

        String result = service.classifyFields(fields);

        assertEquals("INTERNAL", result);
    }

    @Test
    void classifyFields_piiTakesPrecedenceOverConfidential() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(makeField("ssn"));           // PII
        fields.add(makeField("credit_score"));  // CONFIDENTIAL

        String result = service.classifyFields(fields);

        assertEquals("PII", result); // PII wins over CONFIDENTIAL
    }

    private Map<String, Object> makeField(String name) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", "VARCHAR");
        return f;
    }
}
