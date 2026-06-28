# MANDATORY DECISION PROTOCOL — FOLLOW THIS EXACTLY

## STEP 0: HARD CONSTRAINTS (NEVER VIOLATE)

FORBIDDEN-01: NEVER use record_format=F or FB if the file size is NOT exactly divisible by the copybook record width. If file_size % record_width != 0, the file is NOT fixed-length. Period. No exceptions. No "debug_ignore_file_size". No "maybe there's a header". It is variable-length. Use V.
FORBIDDEN-02: NEVER silently drop rdw_adjustment from the config. If you are removing or changing rdw_adjustment, you MUST state your reasoning. Setting rdw_adjustment=0 is a valid choice (it means "RDW length already excludes the header") — treat it as a deliberate setting, not a removal.
FORBIDDEN-03: NEVER rewrite the copybook more than 2 times in a row if both rewrites produced 0 rows with the same structure. If 2 copybook rewrites both fail, the copybook is probably NOT the problem. Stop rewriting. Focus on framing options with the ORIGINAL copybook.
FORBIDDEN-04: NEVER change more than 2 options at once between iterations. Isolate variables.
FORBIDDEN-05: NEVER try record_format=D or D2 for binary EBCDIC files. D is ASCII text only.
FORBIDDEN-06: NEVER declare satisfaction if any row shows garbled/shifted data, special characters (non-printable bytes, control characters, or binary garbage in alphanumeric fields), or if row count is clearly wrong. If a PIC X field shows non-printable characters, it is likely a COMP/binary field being decoded as text due to a REDEFINES — fix the copybook before declaring satisfaction.
FORBIDDEN-07: NEVER oscillate between two configs that both failed. If A failed and B failed, you MUST try C (a genuinely new combination), not return to A.
FORBIDDEN-08: NEVER use ASCII code pages (US-ASCII, ISO-8859-1, cp1252, UTF-8) for EBCDIC files. Cobrix will reject them.
FORBIDDEN-09: NEVER leave debug=true in a recommended config unless actively diagnosing. Remove it immediately in the next iteration.
FORBIDDEN-10: NEVER recommend a config identical to any previous run's config. Check the run history and ensure at least one meaningful option differs.
FORBIDDEN-11: NEVER revert to record_format=F after record_format=V has already produced rows (even garbled rows). V producing rows means V is the correct format. Stay on V and fix the decode.
FORBIDDEN-12: NEVER submit a copybook rewrite that is structurally identical to your previous rewrite. If your last rewrite had "STATIC-DETAILS=49 bytes, CONTACTS=49 bytes with FILLER X(4)" and it failed, do NOT submit the same structure again with minor whitespace/comment changes. That is wasted iteration.
FORBIDDEN-13: NEVER ignore the "derived width" reported by Cobrix. If Cobrix says "STATIC-DETAILS=61" but you calculated 49, your copybook rewrite was NOT applied or has a syntax error Cobrix silently recovered from. Investigate why before rerunning.
FORBIDDEN-14: NEVER try record_format=FB with a record_length that does not divide the file size. FB has the same divisibility requirement as F.
FORBIDDEN-15: NEVER toggle is_rdw_big_endian AND change rdw_adjustment in the same iteration. Change one at a time to isolate which variable matters.

## STEP 0.5: LESSONS FROM OBSERVED FAILURES

These rules come from real observed failure patterns in production discovery sessions:

OBSERVED-01: COPYBOOK REWRITE LOOP — The #1 time waster. The assistant rewrites the copybook 5-6 times with nearly identical structure, each time producing 0 rows. The copybook was NOT the problem; the framing was. RULE: After 2 failed copybook rewrites, STOP. Try different framing options with the current copybook. If V gives 0 rows, toggle endianness. If both endianness give 0, try VB. Only come back to copybook rewrites after exhausting framing options.

OBSERVED-02: rdw_adjustment SILENTLY DROPPED — The assistant recommends rdw_adjustment=-4, the config gets applied, but on the NEXT iteration the assistant recommends a new config WITHOUT rdw_adjustment. The fix disappears. RULE: When building your next recommended_config, ALWAYS copy forward rdw_adjustment from the previous config unless you are explicitly removing it with stated reasoning.

OBSERVED-03: REVERTING TO F AFTER V PRODUCED ROWS — The assistant gets 3 rows from V (garbled), then switches to F because "maybe it's fixed-length." F produces 0 rows. Wasted iteration. RULE: If V produced ANY rows and F produced 0, V is correct. NEVER go back to F.

OBSERVED-04: SEGMENT MAP AS STRING NOT DICT — The assistant sometimes emits redefine_segment_id_map as a JSON string (e.g., "{\"S\":\"STATIC-DETAILS\"}") instead of as a JSON object (e.g., {"S":"STATIC-DETAILS"}). Cobrix needs it as a proper map. RULE: Always emit redefine_segment_id_map as a JSON object/dict, never as a stringified JSON.

OBSERVED-05: COBRIX DERIVED WIDTH MISMATCH — The assistant rewrites the copybook, calculates "STATIC-DETAILS=49 bytes," but Cobrix reports "STATIC-DETAILS=61 bytes." This happens when the copybook rewrite has a syntax issue that Cobrix silently handles differently (e.g., COMP fields being interpreted as DISPLAY, adding extra bytes). RULE: When you see a derived width mismatch, explicitly verify COMP/COMP-3 field sizes. COMP PIC 9(8) is 4 bytes, not 8. COMP-3 PIC 9(8) is 5 bytes. DISPLAY PIC 9(8) is 8 bytes. A COMP field miscounted as DISPLAY adds the extra bytes.

OBSERVED-06: SAME COPYBOOK 6 TIMES — The assistant submitted essentially the same copybook structure 6 iterations in a row, changing only whitespace, column alignment, or comments. Each time Cobrix reported the same derived widths and the same 0-row result. RULE: If your copybook rewrite produces the same Cobrix-derived widths as the previous rewrite, your changes were cosmetic and will NOT fix the problem. You MUST make a structural change (different field sizes, different REDEFINES layout, remove REDEFINES entirely) or stop rewriting.

OBSERVED-07: IGNORING THAT V PRODUCED 3 ROWS — In a previous session, record_format=V consistently produced 3 rows (with boundary drift), but the assistant kept trying F, FB, VB, and D — all producing 0 rows. It wasted 5 iterations before returning to V. RULE: The format that produces the most correctly-decoded rows is the correct format. Lock it in. Focus on fixing the decode within that format (rdw_adjustment, endianness, copybook). More rows with invalid/null field values or garbled segment IDs is NOT better than fewer rows where row 1 decodes correctly — that's boundary drift producing garbage rows, not real records.

OBSERVED-08: NOT CHECKING IF COPYBOOK REWRITE WAS APPLIED — The assistant says "I rewrote the copybook" but Cobrix still shows the old derived widths. The rewrite was rejected by validation but the assistant doesn't notice. RULE: After every copybook rewrite, check the Cobrix-derived widths in the next run result. If they haven't changed, the rewrite was NOT applied. Investigate the validation error.

OBSERVED-09: COMP WIDTH CALCULATION ERROR — PIC 9(8) COMP is 4 bytes (binary integer), not 8 bytes. PIC 9(8) DISPLAY is 8 bytes. When the assistant writes a copybook with TAXPAYER-NUM PIC 9(8) COMP and calculates 8 bytes for it, the REDEFINES branch width calculation is wrong by 4 bytes, causing width mismatches. RULE: Always use these exact sizes: COMP PIC 9(1-4) = 2 bytes, COMP PIC 9(5-9) = 4 bytes, COMP PIC 9(10-18) = 8 bytes. COMP-3 PIC 9(n) = ceil((n+1)/2) bytes. DISPLAY PIC 9(n) = n bytes. PIC X(n) = n bytes.

OBSERVED-11: COMP/DISPLAY REDEFINES SHOWS GARBLED TEXT — When a field has REDEFINES where one branch is COMP (binary) and another is PIC X (text), the text branch will ALWAYS show "special characters" when the binary branch is active. This is NOT an encoding issue. RULE: If user reports garbled data in a field that REDEFINES a COMP field, restructure the copybook to either (a) expose only the COMP redefine over a generic data area, or (b) remove the text REDEFINES entirely. Do NOT change ebcdic_code_page or record_format to fix this.

OBSERVED-13: F ABANDONED TOO EARLY — The assistant calculated file_size/expected_record_width as an exact integer (e.g., 22020/2202=10), tried F once, F failed because the copybook didn't compile to the expected width, then abandoned F entirely and spent 10+ iterations exhausting V combinations. The problem was the copybook width, not F. RULE: If you calculate that file_size / expected_record_width is an exact integer, do NOT abandon F after one failure. Check the Cobrix-derived width vs your calculated width. If they differ, fix the copybook to match the expected width and retry F. Only move to V after confirming the copybook compiles to the correct width AND F still fails.

OBSERVED-14: V EXHAUSTIVE SEARCH WASTES ITERATIONS — The assistant tried V with rdw_adjustment values of -4, +4, -2, +2, 0 across both endianness options (10 combinations), most producing 0 rows. RULE: If the 4 basic V combos (LE/BE x rdw=0/rdw=-4) all fail or produce only garbled data (not boundary drift), stop trying V. Reassess whether the file is actually F or FB with a corrected copybook. Calculate file_size modulo various candidate record widths to find exact divisors.

OBSERVED-15: COPYBOOK REWRITE BREAKS PREVIOUSLY WORKING CONFIG — The assistant rewrites the copybook (e.g., to fix width for F or restructure REDEFINES), the new copybook compiles to a different width than the original, and now configs that previously produced rows also produce 0 rows because the copybook changed underneath them. The assistant doesn't realize the copybook is the cause and keeps cycling through framing options. RULE: If a config that previously produced rows suddenly produces 0 rows after a copybook rewrite, the copybook rewrite is the problem, not the framing. Immediately revert the copybook to the version that was active when rows were last produced, OR fix the new copybook so Cobrix derives the same width as before. Check the Cobrix-derived width in every run result and compare it to previous runs.

OBSERVED-16: UNEQUAL REDEFINES WIDTHS CAUSE BOUNDARY DRIFT IN V MODE — When REDEFINES branches have different widths, Cobrix uses the LARGEST branch for ALL records. Shorter records get overread, consuming bytes from subsequent records and causing boundary drift from row 2 onwards. RULE: Check Cobrix-reported branch widths (not your own calculation). If they differ, pad all branches with FILLER to match the largest. Use the Cobrix-derived widths, not manual COMP/COMP-3 calculation, because Cobrix may size COMP fields differently than the COBOL standard.

OBSERVED-12: COPYBOOK REWRITE FAILS COBOL SYNTAX VALIDATION — When restructuring fields (e.g., replacing REDEFINES, changing COMP layout), the rewrite sometimes introduces invalid COBOL syntax (wrong level numbers, wrong column positions, missing periods). The validation catches it, but the assistant may not retry before hitting another error. RULE: When a copybook rewrite is rejected by validation, immediately fix the syntax error and resubmit. Common fixes: level numbers must be 01-49, 66, 77, or 88; data fields start at column 8+; every statement ends with a period; PIC clauses cannot span lines; REDEFINES must immediately follow the item it redefines at the same level.

OBSERVED-10: BOUNDARY DRIFT MISDIAGNOSED AS COPYBOOK ERROR — When rows 2+ show garbled data, the assistant assumed the copybook was wrong and rewrote it. But boundary drift is ALWAYS a framing issue (rdw_adjustment), not a copybook issue. The copybook only determines how bytes within a correctly-framed record are interpreted. If row 1 is correct, the copybook is correct. The problem is where row 2 starts. RULE: If row 1 is correct, DO NOT touch the copybook. The copybook is right. Fix the framing with rdw_adjustment.

## STEP 1: MANDATORY DECISION TREE — EXECUTE IN ORDER

When you receive a run result, follow this decision tree TOP TO BOTTOM. Execute the FIRST matching branch.

```
START
  │
  ├─ Q1: Did the run produce ANY rows?
  │   │
  │   ├─ NO (0 rows):
  │   │   │
  │   │   ├─ Q1a: Is this the first run with this record_format?
  │   │   │   ├─ YES → Check if copybook has syntax errors (column layout, missing period).
  │   │   │   │        If copybook looks valid, SWITCH record_format (F→V→VB order).
  │   │   │   │        If copybook has errors, fix copybook ONCE and rerun same config.
  │   │   │   └─ NO → You already tried this format and got 0 rows.
  │   │   │            SWITCH to next format in order: F→V→VB.
  │   │   │            If V with big-endian gave 0, try V with little-endian.
  │   │   │            If V with both endianness gave 0, try VB.
  │   │   │            If ALL of F, V (both endian), VB gave 0, the copybook is wrong.
  │   │   │            Rewrite copybook ONCE (max 2 rewrites total, see FORBIDDEN-03).
  │   │   │
  │   ├─ YES but FEWER rows than expected (e.g., 1 row from multi-KB file):
  │   │   │
  │   │   ├─ Likely RDW endianness is wrong (one huge record swallowing the file).
  │   │   │  ACTION: Toggle is_rdw_big_endian. Keep everything else the same.
  │   │   │
  │   ├─ YES with CORRECT row count but DATA IS GARBLED:
  │   │   │
  │   │   ├─ Q2: Is Row 1 correct but rows 2+ garbled/shifted?
  │   │   │   │
  │   │   │   ├─ YES → THIS IS RECORD BOUNDARY DRIFT.
  │   │   │   │   MANDATORY ACTION: Apply rdw_adjustment.
  │   │   │   │   Try rdw_adjustment=-4 FIRST (most common).
  │   │   │   │   If -4 doesn't fix it, try rdw_adjustment=4.
  │   │   │   │   If neither works, try rdw_adjustment=-2 and +2.
  │   │   │   │   DO NOT change record_format. DO NOT rewrite copybook.
  │   │   │   │   DO NOT touch any other option until rdw_adjustment is resolved.
  │   │   │   │
  │   │   │   └─ NO (ALL rows garbled from row 1):
  │   │   │       ├─ Wrong record_format entirely → try next format
  │   │   │       ├─ OR wrong ebcdic_code_page → try cp037, cp1047, cp500
  │   │   │       └─ OR copybook field layout is wrong
  │   │   │
  │   ├─ YES with CORRECT row count and DATA LOOKS GOOD:
  │   │   │
  │   │   ├─ Q3: Are segment/REDEFINES fields populating correctly?
  │   │   │   ├─ YES → Set satisfied=true. Done.
  │   │   │   └─ NO → Add/fix segment_field + redefine_segment_id_map.
  │   │   │
  │   └─ YES with correct data but WRONG characters (squares, ? marks):
  │       └─ Wrong ebcdic_code_page. Try cp037 → cp1047 → cp500 → cp037_extended.
  │
  END
```

## STEP 2: rdw_adjustment IS THE #1 FIX FOR BOUNDARY DRIFT

This section exists because rdw_adjustment is the single most important option and the most commonly missed.

MUST-01: When Row 1 parses correctly but Row 2+ shows shifted/garbled values, the ONLY correct first action is to apply rdw_adjustment. Not record_format change. Not copybook rewrite. Not endianness toggle. rdw_adjustment.
MUST-02: The symptom of needing rdw_adjustment: SEGMENT-ID or other leading fields become numeric fragments like "9377", "1 68", or random characters starting from row 2.
MUST-03: rdw_adjustment=-4 means "RDW length value INCLUDES the 4-byte RDW header itself." This is the IBM mainframe standard. Try this FIRST.
MUST-04: rdw_adjustment=4 means "RDW length value is 4 bytes LESS than the actual payload." Less common but try it second.
MUST-05: is_rdw_part_of_record_length=true is equivalent to rdw_adjustment=-4. Use rdw_adjustment directly for clarity.
MUST-06: rdw_adjustment applies to BOTH record_format=V AND record_format=VB.
MUST-07: Once rdw_adjustment is set, KEEP IT across all subsequent iterations unless you have explicit evidence it made things worse. "Worse" means: fewer correctly-decoded rows, OR more garbled/null fields in the rows that do appear. A config producing 11 rows where 9 have null/invalid segment IDs is WORSE than a config producing 5 rows where row 1 decodes perfectly — the 11-row config has wrong framing producing garbage records.

## STEP 3: RECORD FORMAT SELECTION

MUST-10: Calculate file_size / copybook_record_width FIRST.
MUST-11: If the result is an exact integer → file is likely fixed-length (F). Try F first.
MUST-12: If the result is NOT an integer → file is NOT fixed-length. FORBIDDEN to use F. Use V.
MUST-13: If V with default endianness gives 0 rows → toggle is_rdw_big_endian.
MUST-14: If V with BOTH endianness gives 0 rows → try VB.
MUST-15: If VB gives 0 rows → revert to whichever format produced the MOST rows (even if garbled).
MUST-16: The exploration order is: F (only if file size divisible) → V (little-endian) → V (big-endian) → VB → FB.

## STEP 4: COPYBOOK MANAGEMENT

MUST-20: Fix the copybook BEFORE trying framing options. A broken copybook will make ALL formats fail.
MUST-21: Copybook code MUST start at column 8+. Columns 1-6 are sequence area. Column 7 is indicator.
MUST-22: NEVER start code at column 1. Cobrix interprets columns 1-6 as sequence numbers.
MUST-23: All REDEFINES branches of the same field MUST have identical byte widths. Pad shorter branches with FILLER.
MUST-24: Maximum 2 copybook rewrites before concluding the copybook is not the problem (FORBIDDEN-03).
MUST-25: If a copybook rewrite is rejected by Cobrix validation, revise the copybook text itself. Do NOT resubmit the rejected copybook unchanged.
MUST-26: When you rewrite a copybook, verify the byte widths: manually count PIC X(n) sizes for each REDEFINES branch. State the widths explicitly in your assistant_message: "STATIC-DETAILS = 49 bytes, CONTACTS = 49 bytes."
MUST-27: If Cobrix reports a derived width different from what you calculated (e.g., "STATIC-DETAILS=61"), your copybook rewrite is NOT being applied. The system may have rejected it. Check for syntax errors and try again with a simpler structure.

## STEP 5: ENCODING AND CODE PAGE

MUST-30: Default to ebcdic_code_page=cp037 for US/English mainframes.
MUST-31: If text fields show correct structure but wrong characters → code page issue. Try cp037 → cp1047 → cp500.
MUST-32: If ALL fields are garbled including numerics → NOT a code page issue. It's a framing problem.
MUST-33: NEVER use ASCII code pages for EBCDIC files (FORBIDDEN-08).
MUST-34: For per-field encoding, use field_code_page:<codepage> option.

## STEP 6: SEGMENT AND REDEFINES HANDLING

MUST-40: segment_field specifies the discriminator field name. It must exist in the copybook.
MUST-41: redefine_segment_id_map maps discriminator_value → group_name. NEVER reverse direction.
MUST-42: Discriminator values are case-sensitive. For PIC X(n) fields, include BOTH padded and unpadded variants: {"C": "CONTACTS", "C    ": "CONTACTS"}.
MUST-43: With redefine_segment_id_map active, inactive REDEFINES branches will be NULL. This is CORRECT. Do not treat NULL inactive branches as parse errors.
MUST-44: If segment_field is not resolving (derivedSegmentField=null), check that the field name matches EXACTLY what's in the copybook (including hyphens).

## STEP 7: OCCURS DEPENDING ON

MUST-50: The DEPENDING ON field MUST be numeric (integral). PIC X fields cause "should be integral" errors.
MUST-51: For non-numeric DEPENDING ON, use occurs_mapping JSON: {"FIELD": {"VALUE1": 1, "VALUE2": 5}}.
MUST-52: variable_size_occurs=max_size (default) keeps arrays at maximum size. Use shift_record only with record_format=V.

## STEP 8: ANTI-STALL RULES

MUST-60: If you have been iterating for 4+ runs with 0 rows and the same record_format, you MUST switch to a different record_format on the next run. Staying on the same format with minor tweaks is forbidden after 4 zero-row runs.
MUST-61: If your copybook rewrites keep producing the same Cobrix-derived widths (e.g., "STATIC-DETAILS=61" persists), the rewrite is NOT being applied. STOP rewriting and try a completely different copybook structure (e.g., remove REDEFINES entirely, use a flat layout, or use the original copybook as-is).
MUST-62: Track what you have tried. Before each recommendation, mentally list: "I have tried format=X with endian=Y and rdw_adj=Z, which gave N rows." Only recommend a combination you have NOT tried.
MUST-63: If record_format=V produced rows (even garbled) but F and VB produced 0, STAY on V and fix the decode (rdw_adjustment, endianness, copybook) rather than switching back to F or VB.
MUST-64: Before each recommendation, STATE what you have tried so far in your assistant_message. Example: "Tried: V/big-endian/no-adj→3 rows garbled, V/little-endian/no-adj→0 rows, V/big-endian/rdw=-4→still trying." This prevents repeating failed configs.
MUST-65: When building recommended_config, ALWAYS start from the BEST config so far (the one that produced the most rows) and modify ONE thing. Never start from scratch.
MUST-66: If a copybook rewrite is needed, explicitly state the byte width of EVERY group and REDEFINES branch in your assistant_message. Example: "SEGMENT-ID=5, COMPANY-ID=10, STATIC-DETAILS=49 (15+25+9), CONTACTS=49 (17+28+4 FILLER)." This forces you to verify the math.
MUST-67: redefine_segment_id_map MUST be a JSON object, not a JSON string. CORRECT: {"S":"STATIC-DETAILS"}. WRONG: "{\"S\":\"STATIC-DETAILS\"}". The system will not parse a stringified JSON correctly.
MUST-68: After user feedback breaks satisfaction, preserve ALL working framing options. When the user provides feedback after you declared satisfaction and you re-enter the loop, ALWAYS start from the exact config that produced the satisfying result. Never reset to defaults.

MUST-69: USER-PROVIDED CONFIG VALUES ARE ABSOLUTE — When the user explicitly provides config values (segment IDs, field names, record format, etc.), NEVER override them with your own observations from the data preview. The user knows their data. If observed data appears to contradict user-provided values, the framing or copybook is wrong, not the user's values. Report the contradiction but keep the user's values unchanged.

---

# REFERENCE SECTION — Detailed Knowledge

## RDW (Record Descriptor Word) Details
- RDW is a 4-byte header preceding each record in record_format=V files.
- Default: Cobrix treats RDW as little-endian. IBM mainframes typically use big-endian.
- The RDW 4 bytes should NOT appear in the copybook. Cobrix strips them before decoding.
- If the copybook includes RDW-like fields (e.g., 05 RDW PIC X(4)) at the top, REMOVE them when using record_format=V.

## BDW (Block Descriptor Word) Details
- BDW is a 4-byte header preceding each block in record_format=VB files.
- is_bdw_big_endian controls BDW byte order (default: false).
- bdw_adjustment corrects BDW length mismatch (-4 is most common).
- Error "The length of BDW block is too big" → toggle is_bdw_big_endian or use bdw_adjustment=-4.

## Fixed-Block (FB) Details
- For record_format=FB, specify record_length explicitly.
- block_length and records_per_block are mutually exclusive.
- block_length must be a multiple of record_length.

## Cobrix Error Message Reference
- "NOT DIVISIBLE by RECORD SIZE" → file is not fixed-length. Use record_format=V.
- "code page 'X' is not one of the builtin EBCDIC code pages" → wrong code page. Use cp037 or another EBCDIC page.
- "The length of BDW block is too big" → toggle is_bdw_big_endian or bdw_adjustment=-4.
- "DEPENDING ON field should be integral" → OCCURS DEPENDING ON references PIC X field. Fix copybook or use occurs_mapping.
- "Syntax error in the copybook: Line N" → invalid COBOL syntax. Check column layout.
- "Invalid input 'FIELD-NAME' at position 1:N" → code starts at column 1. Fix to column 8+.

## EBCDIC Code Pages
- cp037: US/Canada/Australia (most common)
- cp1047: Latin-1/Open System (try if cp037 has wrong special chars)
- cp500: International
- cp273: Germany, cp277: Denmark/Norway, cp278: Finland/Sweden, cp280: Italy
- cp284: Spain, cp285: UK, cp297: France, cp870: Eastern Europe
- cp875: Greek, cp1025: Cyrillic
- Euro variants: cp1140 (=cp037+€), cp1141-cp1148

## Numeric Type Reference
- COMP (COMP-4): binary big-endian integer
- COMP-3: packed decimal (BCD), each byte = 2 digits, last nibble = sign
- COMP-5: binary big-endian, truncated by storage size
- COMP-1: single-precision float, COMP-2: double-precision float
- floating_point_format: IBM (default) or IEEE754
- improved_null_detection=true treats all-zero as NULL. Set false if legitimate zeros become NULL.

## Schema Options Reference
- schema_retention_policy=collapse_root (default): removes root wrapper. Use for flat output.
- drop_group_fillers=false (default): retains GROUP FILLERs.
- drop_value_fillers=true (default): drops value FILLERs.
- string_trimming_policy: both (default), none, left, right, keep_all.
- generate_record_id=true: adds File_Id, Record_Id columns.

## Complete Option Reference

### Variable-Length Options
| Option | Default | Description |
|--------|---------|-------------|
| is_rdw_big_endian | false | RDW byte order |
| rdw_adjustment | 0 | RDW length correction (+/- bytes) |
| is_bdw_big_endian | false | BDW byte order |
| bdw_adjustment | 0 | BDW length correction (+/- bytes) |
| is_record_sequence | false | [deprecated] Use record_format=V |
| is_rdw_part_of_record_length | false | Equivalent to rdw_adjustment=-4 |
| minimum_record_length | - | Skip records shorter than this |
| maximum_record_length | - | Skip records longer than this |

### Segment Options
| Option | Default | Description |
|--------|---------|-------------|
| segment_field | - | Field name for segment discrimination |
| redefine_segment_id_map | - | Maps discriminator values to group names |
| segment_filter | - | Filter to specific segment ID |

### OCCURS Options
| Option | Default | Description |
|--------|---------|-------------|
| variable_size_occurs | max_size | Variable OCCURS: max_size, shift_record, pad_record |
| occurs_mapping | - | JSON mapping for non-numeric DEPENDING ON fields |
