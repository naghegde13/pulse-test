package com.pulse.codegen.service;

import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.scan.ForbiddenTokenScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC #6 §(c) — inline {@code [[ ]]} date-mnemonic lowering (ADR 0024): the slug
 * rule (§c.2), dbt rewrite to {@code {{ var(...) }}} (§c.3), config-time typo
 * loud-fail (§c.1), and §c.6 — no {@code [[ ]]} survives, scanner stays green.
 */
class MnemonicLoweringTest {

    // --- §c.2 slug rule (6-G07) -------------------------------------------

    @Test
    void slugRuleLowercasesAndUnderscoresNonAlnumWithPulsePrefix() {
        assertEquals("pulse_pbd_1", CodeGenerationService.mnemonicSlug("PBD-1"));
        assertEquals("pulse_run_date", CodeGenerationService.mnemonicSlug("RUN_DATE"));
        assertEquals("pulse_nbdom_2_", CodeGenerationService.mnemonicSlug("NBDOM(2)"));
        assertEquals("pulse_same_day_last_week",
                CodeGenerationService.mnemonicSlug("SAME_DAY_LAST_WEEK"));
    }

    // --- §c.3 dbt rewrite -------------------------------------------------

    @Test
    void dbtRewriteLowersMnemonicToVarAndLeavesNoBrackets() {
        // CodeGenerationService is heavy to construct; use a no-arg instance only to
        // reach the instance-method lowering (it touches no injected collaborators).
        CodeGenerationService svc = newSvc();
        String sql = "SELECT * FROM t WHERE ds = DATE('[[ PBD-1 ]]')";
        String lowered = svc.lowerDbtMnemonics(sql);
        assertEquals("SELECT * FROM t WHERE ds = DATE('{{ var('pulse_pbd_1') }}')", lowered);
        assertFalse(lowered.contains("[["));
        assertFalse(lowered.contains("]]"));
    }

    @Test
    void dbtRewriteIsByteIdenticalWhenNoMnemonics() {
        CodeGenerationService svc = newSvc();
        String sql = "SELECT * FROM t WHERE ds = '{{ var(\"pulse_business_date\") }}'";
        assertEquals(sql, svc.lowerDbtMnemonics(sql));
    }

    // --- §c.1 config-time typo loud-fail ----------------------------------

    @Test
    void typoMnemonicLoudFails() {
        CodeGenerationService svc = newSvc();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.validateMnemonics("SELECT * FROM t WHERE ds = '[[ PDB-1 ]]'"));
        assertTrue(ex.getMessage().contains("Invalid date input"));
    }

    @Test
    void dbtRewriteAlsoLoudFailsOnTypo() {
        CodeGenerationService svc = newSvc();
        assertThrows(IllegalArgumentException.class,
                () -> svc.lowerDbtMnemonics("SELECT '[[ NOPE ]]'"));
    }

    // --- §c.6 scanner stays green after lowering --------------------------

    @Test
    void loweredDbtModelHasNoBracketsAndPassesForbiddenScanner() {
        CodeGenerationService svc = newSvc();
        String lowered = svc.lowerDbtMnemonics(
                "SELECT a FROM t WHERE d BETWEEN DATE('[[ BOM ]]') AND DATE('[[ EOM ]]')");
        assertFalse(lowered.contains("[["));
        assertFalse(lowered.contains("]]"));

        GeneratedArtifact art = new GeneratedArtifact();
        art.setFilePath("dbt_project/models/silver/m.sql");
        art.setFileType("DBT_MODEL");
        art.setContent(lowered);
        Map<String, List<String>> violations = ForbiddenTokenScanner.scan(List.of(art));
        assertTrue(violations.isEmpty(), "scanner should stay green: " + violations);
    }

    /** A bare instance — lowering helpers touch no injected collaborators. */
    private static CodeGenerationService newSvc() {
        try {
            var ctor = CodeGenerationService.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            return (CodeGenerationService) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
