# What a "scenario" and an "answer key" actually are (worked example)

Built from the **loan-master pipeline** — the one that ran end-to-end today
(bronze→silver→gold on real Spark). It already exists in `V154`, so its
**answer key is not invented — it's read out of what we already built.**

---

## The ANSWER KEY  (= the correct composition; this is just data we already have)

This is the target. It comes straight from the seeded `loan_master` pipeline in
`V154__regenerate_demo_compositions.sql`. Nobody "generated" it — it's a real,
known-correct pipeline.

```jsonc
{
  "pipeline": "Loan Master",
  "tenant": "tenant-home-lending", "domain": "lending",
  "instances": [
    { "ref": "ingest",  "blueprint": "FileIngestion",
      "params": { "filename_pattern": "loan_master_{date}.csv",
                  "date_value": "RUN_DATE", "delimiter": ",", "has_header": true },
      "output_role": "bronze" },

    { "ref": "clean",   "blueprint": "BronzeToSilverCleaning",
      "params": { "trim_columns": ["borrower_name", "property_address"],
                  "type_coercions": { "loan_amount": "decimal", "origination_date": "date" },
                  "drop_null_columns": ["loan_id"],
                  "dedup_key": ["loan_id"] },
      "input_role": "bronze", "output_role": "silver" }
  ],
  "wiring": [
    { "from": "ingest.raw_output", "to": "clean.raw_input" }
  ]
}
```

That's the whole "answer key": **which blueprints, how they're wired, the key params.**

---

## The SCENARIO  (= what a human authors: one goal + a facts sheet)

The only thing a person writes. The goal is the prose a data engineer would type;
the facts sheet is what the simulated user is allowed to reveal *when the agent asks*.

```jsonc
{
  "id": "loan-master-ingest-clean",
  "persona": "data engineer, terse",

  "goal": "We get a loan master file dropped daily in object storage,
           loan_master_<date>.csv. Load it to bronze, then clean it to silver.",

  "facts_the_user_knows": {            // answers to the agent's clarifying questions
    "source": "object storage, daily CSV, pattern loan_master_{date}.csv, comma, has header",
    "business_key": "loan_id",
    "cleaning": "trim borrower_name + property_address; cast loan_amount->decimal,
                 origination_date->date; drop rows missing loan_id; dedupe on loan_id",
    "schedule": "daily"
  }
  // NOTE: the facts sheet deliberately does NOT mention 'FileIngestion' or
  // 'BronzeToSilverCleaning'. The user describes intent; the AGENT must choose the
  // blueprints. Otherwise we'd be feeding it the answer.
}
```

---

## How the test runs (and where the LLM does / does NOT help)

1. **Simulated user** (an LLM role-playing the persona) opens with `goal`.
2. The **real Chat agent** asks questions; the simulated user answers *only* from
   `facts_the_user_knows`. (LLM used here = safe; it's just role-play.)
3. The agent composes a pipeline (op-queue → staged graph = a concrete composition).
4. **Score** = diff(agent's composition, ANSWER KEY) — pure data comparison, no LLM:
   - right blueprints? (FileIngestion + BronzeToSilverCleaning)  → structural
   - wired right? (ingest.raw_output → clean.raw_input)          → topology
   - key params match? (type_coercions, dedup_key, trim_columns) → config
   - PASS if structural+topology exact and key params match.
5. (Optional) an LLM **judge** rates only *naturalness* of the transcript — never correctness.

The LLM never writes the answer key and never grades correctness. It only (a) plays
the user and (b) optionally rates conversational feel.

---

## So "how do we GET scenarios + answer keys?" — in order of effort

1. **Free, today:** the answer keys already exist — the **3 seeded V154 pipelines +
   the anchor**. Read them out → you instantly have ~4 answer keys. Authoring left to
   do = one `goal` + `facts` sheet each (~5 min apiece).
2. **More coverage:** every time the operator/a DE builds a correct pipeline in the UI,
   **that composition becomes a new answer key** — building demos *is* building the test set.
3. **Catalog coverage at scale:** code can compose valid pipelines by port-matching
   blueprints (source→transform→sink); the graph is **built by code = correct by
   construction** = an answer key, no LLM guess.
4. **Surface variety only:** an LLM rephrases each `goal` 10 ways (terse/vague/jargon)
   to stress the agent — but the answer key stays fixed from steps 1–3.

**Bottom line:** the answer keys are the *pipelines themselves*, which we already have.
The "generation" is just (a) writing a goal sentence per known-good pipeline and
(b) letting an LLM play the user at runtime.
