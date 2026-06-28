package com.pulse.chat.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PulseSystemPromptTest {

    @Test
    void nineNamedSegmentsExist() throws Exception {
        String[] expected = {
                "IDENTITY", "ABSOLUTE_RULES", "MEDALLION_RULES", "DBT_ANNOTATIONS",
                "WORKFLOW_PACKET", "REASONING_FRAMEWORK", "PLANNER_PACKET",
                "GENERATION_PACKET", "TOOL_GUIDELINES"
        };
        for (String name : expected) {
            Field f = PulseSystemPrompt.class.getField(name);
            Object value = f.get(null);
            assertNotNull(value, name + " must exist and be non-null");
            assertFalse(((String) value).isBlank(), name + " must not be blank");
        }
    }

    @Test
    void coreConstantRemoved() {
        assertThrows(NoSuchFieldException.class,
                () -> PulseSystemPrompt.class.getField("CORE"));
    }

    @Test
    void identitySegmentMentionsPulseAndVeteranDataEngineer() {
        String segment = PulseSystemPrompt.IDENTITY;
        assertTrue(segment.contains("PULSE"));
        assertTrue(segment.contains("25 years") || segment.contains("data engineering"));
    }

    @Test
    void absoluteRulesContainAllEleven() {
        String segment = PulseSystemPrompt.ABSOLUTE_RULES;
        assertTrue(segment.contains("ONE QUESTION PER MESSAGE"));
        assertTrue(segment.contains("NEVER EXPOSE INTERNAL IDs"));
        assertTrue(segment.contains("CREATING OR SAVING AN ENTITY REQUIRES CALLING A TOOL"));
    }

    @Test
    void medallionRulesHardConstraints() {
        String segment = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(segment.contains("Bronze is raw ingest only"));
        assertTrue(segment.contains("Bronze-to-Silver is dbt"));
        assertTrue(segment.contains("Silver-to-Gold is dbt"));
        assertTrue(segment.contains("All compute is Spark"));
        assertTrue(segment.contains("All data quality is Great Expectations"));
        assertTrue(segment.contains("Always propose partitioning"));
    }

    @Test
    void dbtAnnotationsSegmentPresent() {
        String segment = PulseSystemPrompt.DBT_ANNOTATIONS;
        assertTrue(segment.contains("(dbt)"));
        assertTrue(segment.contains("Never introduce dbt concepts without the '(dbt)' annotation"));
    }

    @Test
    void medallionRules_includeDateMnemonicVocabulary() {
        // Closes #42 sub-phase 4: agent must propose mnemonics over hard-coded
        // dates by default, and the vocabulary table must be present so the LLM
        // can pattern-match against it.
        String segment = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(segment.contains("Date inputs"),
                "MEDALLION_RULES must include the date-mnemonic subsection heading");
        assertTrue(segment.contains("accepts_mnemonic"),
                "vocabulary section must reference the accepts_mnemonic flag so the agent links to params_schema");
        // Spot-check key vocabulary tokens are present.
        assertTrue(segment.contains("`BOM`") && segment.contains("`EOM`"),
                "BOM/EOM must be in the vocabulary");
        assertTrue(segment.contains("`PBD`") && segment.contains("`NBD"),
                "PBD/NBD business-day mnemonics must be in the vocabulary");
        assertTrue(segment.contains("`NBDOM(N)`"),
                "NBDOM(N) parameterized mnemonic must be present");
        assertTrue(segment.contains("`FBOM`") && segment.contains("`LBOM`"),
                "FBOM/LBOM must be in the vocabulary");
        assertTrue(segment.contains("RUN_DATE") && segment.contains("PREVIOUS_RUN_DATE"),
                "today-relative mnemonics must be in the vocabulary");
        assertTrue(segment.contains("`BOFY"),
                "fiscal mnemonics must be in the vocabulary");
        // Common-patterns guidance is the practical layer the agent uses to make decisions.
        assertTrue(segment.contains("Common patterns to propose"),
                "vocabulary section must include the 'common patterns' subsection so the agent learns when to pick which");
        // Error path tells the agent it can't invent mnemonics.
        assertTrue(segment.contains("DateMnemonic.validateOrThrow") || segment.contains("Do not invent"),
                "vocabulary section must tell the agent it can't invent mnemonics outside the list");
    }

    @Test
    void medallionRules_includeDbtVsGxBoundaryGuidance() {
        // Closes #27: when the agent proposes a pipeline mixing dbt blueprints with a
        // GX DQ blueprint, it must explain the separation so users don't have to ask.
        String segment = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(segment.contains("dbt vs GX"),
                "MEDALLION_RULES must include the dbt vs GX boundary section heading");
        assertTrue(segment.contains("BronzeToSilverCleaning")
                        && segment.contains("DQValidator"),
                "rule must name the canonical blueprints on each side of the boundary");
        assertTrue(segment.contains("NO data quality rules live inside the dbt models"),
                "rule must explicitly state DQ does not live in dbt models");
        assertTrue(segment.contains("'cleaning'"),
                "rule must call out the naming ambiguity that triggers the user's question");
    }

    @Test
    void plannerPacket_orchestrationShowYourWorkRule() {
        // Closes #28: when calling update_pipeline_orchestration the agent MUST propose
        // values with reasons, treat catchup as a separate decision, default it to false,
        // and never announce orchestration as fait accompli. Lives in PLANNER_PACKET
        // because that segment already houses the "Orchestration and sensing are
        // first-class" section.
        String segment = PulseSystemPrompt.PLANNER_PACKET;
        assertTrue(segment.contains("Show-your-work"),
                "PLANNER_PACKET must include the orchestration show-your-work rule heading");
        assertTrue(segment.contains("update_pipeline_orchestration"),
                "rule must name the tool by name");
        assertTrue(segment.contains("catchup_enabled=false"),
                "rule must state the safe default for catchup");
        assertTrue(segment.contains("hundreds of unintended runs")
                        || segment.contains("every interval from"),
                "rule must explain the catchup blast-radius so the LLM has a label for the risk");
        assertTrue(segment.contains("fait accompli"),
                "rule must explicitly forbid announcing orchestration as a done deal");
    }

    @Test
    void contextSection_includesSorAutoMatchGuard() {
        // Closes #25 (commit will record the SHA): the SOR directory must be preceded by a
        // hard rule that bans hallucinated resource_ids and name-keyword soft-matching.
        String rendered = PulseSystemPrompt.buildContextSection(
                "- **Servicing** | id: `dom-1`\n",
                "no current pipeline\n",
                "no dbt assets\n",
                "- **Payment Gateway** (Default) ID: `01JSOR0PAYGW000000000001`\n",
                "no datasets\n",
                "no blueprints\n",
                "no retrieval packets\n",
                "no session facts\n");
        assertTrue(rendered.contains("HARD RULE"),
                "rendered context must include the SOR directory hard-rule preamble");
        assertTrue(rendered.contains("list_data_sources"),
                "preamble must instruct the agent to call list_data_sources");
        assertTrue(rendered.contains("navigate_ui"),
                "preamble must explicitly forbid the navigate_ui hallucination pattern");
        assertTrue(rendered.contains("Hallucinating a resource_id"),
                "preamble must name the failure mode so the LLM has a label for it");
        // Sanity: the SOR directory itself still renders after the preamble.
        assertTrue(rendered.contains("Payment Gateway"),
                "SOR directory must still render below the preamble");
    }

    @Test
    void toolGuidelinesListsNewTools() {
        String segment = PulseSystemPrompt.TOOL_GUIDELINES;
        assertTrue(segment.contains("list_sink_targets"));
        assertTrue(segment.contains("create_sink_target"));
        assertTrue(segment.contains("view_code_examples"));
        assertTrue(segment.contains("include_deprecated"));
    }

    @Test
    void plannerPacketMakesMedallionAndReuseRulesExplicit() {
        assertTrue(PulseSystemPrompt.PLANNER_PACKET.contains("Bronze → Gold is invalid as a normal path"));
        assertTrue(PulseSystemPrompt.PLANNER_PACKET.contains("`reuse_wrapper`"));
        assertTrue(PulseSystemPrompt.PLANNER_PACKET.contains("business concept, grain, schema compatibility"));
    }

    @Test
    void generationPacketRequiresPromptAndToolAlignment() {
        assertTrue(PulseSystemPrompt.GENERATION_PACKET.contains("Keep prompt behavior and tool behavior aligned"));
        assertTrue(PulseSystemPrompt.GENERATION_PACKET.contains("After the user approves a pipeline plan"));
        assertTrue(PulseSystemPrompt.GENERATION_PACKET.contains("targeted dbt best-practice cards"));
    }

    @Test
    void workflowPacketOwnsLongFormProductFlow() {
        assertTrue(PulseSystemPrompt.WORKFLOW_PACKET.contains("### Phase 1 — Identify Pipeline Scope & Starting Point"));
        assertTrue(PulseSystemPrompt.WORKFLOW_PACKET.contains("## UI-Chat Sync Rules"));
        // Long-form content does not live in IDENTITY/ABSOLUTE_RULES.
        assertFalse(PulseSystemPrompt.IDENTITY.contains("### Phase 1"));
        assertFalse(PulseSystemPrompt.ABSOLUTE_RULES.contains("### Phase 1"));
    }

    @Test
    void contextSectionStillAssemblesTenantHeader() {
        String ctx = PulseSystemPrompt.buildContextSection(
                "", "", "", "", "", "", "", "");
        assertEquals(true, ctx.contains("Current Tenant Context"));
    }

    @Test
    void medallionRulesDeclareStorageBackendChoice() {
        // #30 P6: agent must ask only "DPC or GCP?" per leg, never about
        // buckets/paths/prefixes.
        String rules = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(rules.contains("Storage backend selection"),
                "MEDALLION_RULES must include the storage_backend section");
        assertTrue(rules.contains("DPC or GCP"),
                "MEDALLION_RULES must surface the binary backend choice");
        assertTrue(rules.contains("NEVER ask the user for buckets, paths, or prefixes"),
                "MEDALLION_RULES must forbid bucket prompts");
    }

    @Test
    void medallionRulesEnforceGoldOnGcpRule() {
        // #30 locked rule (DB-enforced too via V96 CHECK):
        // GCP gold ⇒ lake_format=bq_native always.
        String rules = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(rules.contains("Gold-on-GCP rule (LOCKED")
                        && rules.contains("bq_native"),
                "MEDALLION_RULES must declare the gold-on-GCP rule");
    }

    @Test
    void medallionRulesDeclareLegalLakeFormatMatrix() {
        // The 4 legal lake_formats with their backend × layer constraints.
        // Delta is explicitly excluded from DPC and GCP per RuntimeAuthorityService.
        String rules = PulseSystemPrompt.MEDALLION_RULES;
        for (String fmt : new String[]{"iceberg_external",
                "iceberg_bq_managed", "bq_native", "parquet"}) {
            assertTrue(rules.contains(fmt),
                    "MEDALLION_RULES must enumerate lake_format=" + fmt);
        }
        assertTrue(rules.contains("NOT legal on DPC or GCP"),
                "MEDALLION_RULES must explicitly exclude delta");
    }

    @Test
    void medallionRulesSurfaceProvisioningGate() {
        // Pipeline design works against pending storage_backends rows;
        // deploy to non-dev envs is gated on validated. Agent must
        // surface the dependency proactively.
        String rules = PulseSystemPrompt.MEDALLION_RULES;
        assertTrue(rules.contains("pending") && rules.contains("validated")
                        && rules.contains("deployment to"),
                "MEDALLION_RULES must explain provisioning_status gate behavior");
    }
}
