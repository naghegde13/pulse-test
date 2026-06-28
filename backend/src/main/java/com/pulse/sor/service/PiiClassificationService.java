package com.pulse.sor.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PKT-0026: Deterministic PII / confidential field classifier.
 * <p>
 * Uses column name pattern matching to flag fields as PII or CONFIDENTIAL.
 * No LLM involvement — classification is fully deterministic and auditable.
 */
@Service
public class PiiClassificationService {

    // Word boundary that also treats underscore as a separator (column names use _ not spaces)
    private static final String B = "(?:^|_|\\b)";
    private static final String EB = "(?:$|_|\\b)";

    private static final Set<Pattern> PII_PATTERNS = Set.of(
            Pattern.compile("(?i).*" + B + "ssn" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "social.?security" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "tax.?id" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "tin" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "first.?name" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "last.?name" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "full.?name" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "borrower.?name" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "customer.?name" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "email" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "e.?mail" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "phone" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "mobile" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "cell" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "address" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "zip" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "postal" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "date.?of.?birth" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "dob" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "birth.?date" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "driver.?license" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "passport" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "account.?number" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "account.?no" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "routing.?number" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "iban" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "credit.?card" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "card.?number" + EB + ".*")
    );

    private static final Set<Pattern> CONFIDENTIAL_PATTERNS = Set.of(
            Pattern.compile("(?i).*" + B + "salary" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "income" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "credit.?score" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "fico" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "loan.?amount" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "debt" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "ltv" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "dti" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "interest.?rate" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "monthly.?payment" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "balance" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "principal" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "escrow" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "appraisal" + EB + ".*"),
            Pattern.compile("(?i).*" + B + "property.?value" + EB + ".*")
    );

    /**
     * Classify a single column name.
     *
     * @return "PII", "CONFIDENTIAL", or null if not classified
     */
    public String classifyColumn(String columnName) {
        if (columnName == null) return null;
        for (Pattern p : PII_PATTERNS) {
            if (p.matcher(columnName).matches()) return "PII";
        }
        for (Pattern p : CONFIDENTIAL_PATTERNS) {
            if (p.matcher(columnName).matches()) return "CONFIDENTIAL";
        }
        return null;
    }

    /**
     * Annotate a list of field maps with PII/CONFIDENTIAL flags.
     * Mutates each field map in place: adds "pii" (boolean) and
     * "classification" (String or null) keys.
     *
     * @return the overall dataset classification: "PII" if any PII field,
     *         "CONFIDENTIAL" if any confidential field, else "INTERNAL"
     */
    public String classifyFields(List<Map<String, Object>> fields) {
        boolean hasPii = false;
        boolean hasConfidential = false;

        for (Map<String, Object> field : fields) {
            String name = (String) field.get("name");
            String cls = classifyColumn(name);
            if ("PII".equals(cls)) {
                field.put("pii", true);
                field.put("classification", "PII");
                hasPii = true;
            } else if ("CONFIDENTIAL".equals(cls)) {
                field.put("pii", false);
                field.put("classification", "CONFIDENTIAL");
                hasConfidential = true;
            } else {
                field.putIfAbsent("pii", false);
            }
        }

        if (hasPii) return "PII";
        if (hasConfidential) return "CONFIDENTIAL";
        return "INTERNAL";
    }
}
