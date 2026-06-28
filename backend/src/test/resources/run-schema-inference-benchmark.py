#!/usr/bin/env python3
"""
Schema Inference Benchmark Runner
Tests the PULSE schema inference prompt against 151+ scenarios across multiple models.
Reports accuracy, latency, and cost per model.
"""

import json
import time
import sys
import os
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict

API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

MODELS = [
    "google/gemini-2.0-flash-001",
    "deepseek/deepseek-chat",
    "openai/gpt-4o-mini",
    "anthropic/claude-3.5-haiku",
]

SYSTEM_PROMPT = """You are a deterministic schema inference engine for a data pipeline builder called PULSE.

Given a transform type, its input schema(s), and its configuration parameters, you must compute the EXACT output schema.

## Rules by transform type:

### GenericFilter
- Output schema is IDENTICAL to input schema (same columns, same types)
- Filtering only removes rows, never columns

### GenericAggregate
- Output columns = group_by columns (retain original name and type) + aggregation columns (new name from alias, type from function)
- Type rules for aggregation functions:
  - COUNT, COUNT_DISTINCT -> "long"
  - SUM -> if source column is integer/long, output is "long"; if double/float/decimal, output is "double"
  - AVG -> "double"
  - MIN, MAX -> same type as source column
  - If column is "*" (e.g., COUNT(*)), source type doesn't matter, use the function rule above

### GenericJoin
- Start with ALL columns from the left input (in order)
- Then append columns from the right input, with these rules:
  - If a right column name matches a JOIN KEY column name, SKIP it (it's redundant with the left key)
  - If a right column name matches a NON-KEY left column name, RENAME BOTH: the left one becomes "left_{name}" and the right one becomes "right_{name}"
  - Otherwise, append the right column as-is
- The join key columns appear exactly ONCE (from the left side)
- Join type (inner/left/right/full) does NOT affect the output schema
- For collisions: the LEFT column keeps its original name. The RIGHT column gets "right_" prefix.
- This matches standard SQL behavior where the first/left table takes priority.

**Example — Join with collision:**
Left: [{name:"id",type:"long"}, {name:"value",type:"string"}]
Right: [{name:"id",type:"long"}, {name:"value",type:"double"}, {name:"score",type:"integer"}]
Keys: ["id"]
Output: [{name:"id",type:"long"}, {name:"value",type:"string"}, {name:"right_value",type:"double"}, {name:"score",type:"integer"}]
Explanation: "id" is key → once from left. "value" collides → left keeps "value", right becomes "right_value". "score" only on right → as-is.

**Example — Self-join (identical schemas both sides):**
Left: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}]
Right: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}]
Keys: ["emp_id"]
Output: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}, {name:"right_dept",type:"string"}]
Explanation: "emp_id" is key → once. "dept" collides → left keeps original, right gets "right_" prefix.

### GenericRouter
- Output schema is IDENTICAL to input schema for EVERY output port
- Routing only splits rows by condition, never changes columns

### JsonFlatten
- For each source column of type "struct": expand nested fields using the separator
  - e.g., column "address" type "struct" with fields [{name:"city",type:"string"},{name:"zip",type:"string"}] becomes "address_city" (string) and "address_zip" (string)
- If explode_arrays is true: array columns become their element type (changes cardinality)
- If keep_original is true: include the original struct column alongside the flattened fields
- Non-flattened columns pass through unchanged
- If source_columns is ["*"] or empty, flatten ALL struct/nested columns
- If max_depth is specified, STOP flattening at that depth. Depth 1 = expand top-level struct fields only. Depth 2 = expand one more level. Fields beyond max_depth remain as struct type.
  Example: column "data" is struct{level1: struct{level2: struct{val: string}}}. With max_depth=1 and separator "_": output is "data_level1" (type: struct). With max_depth=2: output is "data_level1_level2" (type: struct). With no max_depth: output is "data_level1_level2_val" (type: string).
- Flatten struct columns RECURSIVELY: if a struct has sub-struct fields, expand those too (unless max_depth is reached).
- If sourceColumns includes a map column, pass the map through UNCHANGED (maps cannot be struct-flattened).

**Example — Recursive flatten (IMPORTANT):**
Input: [{name:"id", type:"long"}, {name:"info", type:"struct", fields:[{name:"personal", type:"struct", fields:[{name:"name",type:"string"},{name:"age",type:"integer"}]}, {name:"contact",type:"string"}]}]
sourceColumns: ["info"], separator: "_", keepOriginal: false, maxDepth: 10
Output: [{name:"id", type:"long"}, {name:"info_personal_name", type:"string"}, {name:"info_personal_age", type:"integer"}, {name:"info_contact", type:"string"}]
Explanation: "info" is flattened. "info.personal" is a sub-struct → recurse → produces "info_personal_name" and "info_personal_age". "info.contact" is a string → produces "info_contact". Total: 4 columns.

**Example — max_depth=2 on 3-level nesting:**
Input: [{name:"id",type:"long"}, {name:"data",type:"struct",fields:[{name:"level1",type:"struct",fields:[{name:"val",type:"string"},{name:"level2",type:"struct",fields:[{name:"val",type:"integer"},{name:"level3",type:"struct",fields:[{name:"val",type:"string"}]}]}]}]}]
sourceColumns: ["data"], separator: "_", maxDepth: 2
Output: [{name:"id",type:"long"}, {name:"data_level1_val",type:"string"}, {name:"data_level1_level2",type:"struct"}, {name:"data_level1_level2_val",type:"integer"}]
Explanation: Depth 1 expands "data" → "data_level1". Depth 2 expands "data_level1" → "data_level1_val" (string) and "data_level1_level2" (struct, STOP at depth 2). But wait, "data_level1_level2_val" is at depth 2 as a scalar, so it IS included. "data_level1_level2_level3" would be depth 3, so it's NOT included (the level2 struct contains it).

### JsonStruct
- Output = passthrough columns + new struct columns
- Passthrough = all input columns NOT in any mapping's source_columns list (if drop_source_columns is true) or all input columns (if drop_source_columns is false)
- New struct columns: one per mapping, type is "struct" with fields typed from their source columns
- If output_format is "json_string": the new columns are type "string" (JSON serialized) instead of "struct"

### Other blueprints (DedupeAndMerge, PIIMasking, SCD2Dimension, BronzeToSilverCleaning, SchemaNormalization, EnrichmentJoin, etc.)
- DedupeAndMerge / Deduplication: output = input (same columns, deduplicated rows)
- PIIMasking: output = input but masked columns have type "string" (masked/tokenized). If no specific columns listed in params, output = input
- BronzeToSilverCleaning / BronzeCleaning: output = input (cleaned but same schema)
- SchemaNormalization: output = input with standardized column names (lowercase, underscores). Types stay the same.
- SCD2Dimension / SCD2: output = input + [effective_from (timestamp), effective_to (timestamp), is_current (boolean), scd2_hash (string)]
- EnrichmentJoin: output = main_data columns + reference_data columns (like a left join). Key columns from reference are omitted. Non-key collisions: left keeps name, right gets "right_" prefix. ALWAYS include both sides of a collision — never drop a column.
- For any unknown blueprint: output = input (passthrough assumption)

## Output format:
Return ONLY valid JSON in this exact format:
{"columns": [{"name": "column_name", "type": "column_type"}, ...]}

Valid types: "string", "long", "integer", "double", "float", "decimal", "boolean", "date", "timestamp", "struct", "array", "binary", "map"

Do NOT include any explanation. Do NOT wrap in markdown. ONLY the JSON object.
"""


def build_user_prompt(scenario):
    parts = [f"## Transform: {scenario['blueprintKey']}"]
    parts.append(f"\n## Input Schema (primary):\n```json\n{json.dumps(scenario['inputSchema'])}\n```")
    if scenario.get("secondarySchema"):
        parts.append(f"\n## Secondary Input Schema (right/reference):\n```json\n{json.dumps(scenario['secondarySchema'])}\n```")
    parts.append(f"\n## Transform Parameters:\n```json\n{json.dumps(scenario['params'])}\n```")
    parts.append("\n## Output:\nReturn ONLY the output schema JSON. No explanation, no markdown fences.")
    return "\n".join(parts)


def call_openrouter(model, user_prompt):
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a schema inference engine. You ONLY output valid JSON."},
            {"role": "user", "content": SYSTEM_PROMPT + "\n\n" + user_prompt}
        ],
        "temperature": 0.0,
        "max_tokens": 2000
    }).encode("utf-8")

    req = urllib.request.Request(BASE_URL, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {API_KEY}",
        "HTTP-Referer": "https://pulse.app",
        "X-Title": "PULSE Schema Inference Benchmark"
    })

    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            latency = time.time() - start
            content = data["choices"][0]["message"]["content"]
            usage = data.get("usage", {})
            return content, latency, usage
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else str(e)
        return None, time.time() - start, {"error": body}
    except Exception as e:
        return None, time.time() - start, {"error": str(e)}


def parse_schema(raw):
    if raw is None:
        return None
    cleaned = raw.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.split("\n", 1)[1] if "\n" in cleaned else cleaned[3:]
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3]
        cleaned = cleaned.strip()
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        return None


def normalize_type(t):
    """Normalize type strings for comparison."""
    t = t.lower().strip()
    # Strip parameterized type info: struct<...> -> struct, array<...> -> array, map<...> -> map
    for prefix in ["struct", "array", "map"]:
        if t.startswith(prefix + "<") or t.startswith(prefix + "("):
            return prefix
    type_aliases = {
        "int": "integer", "bigint": "long", "int64": "long",
        "float64": "double", "float32": "float", "varchar": "string",
        "text": "string", "bool": "boolean", "datetime": "timestamp",
        "number": "double",
    }
    return type_aliases.get(t, t)


def schemas_match(actual, expected):
    """Compare two schemas. Returns (match_bool, detail_string)."""
    if actual is None:
        return False, "parse_error"
    
    actual_cols = actual.get("columns", [])
    expected_cols = expected.get("columns", [])
    
    if len(actual_cols) != len(expected_cols):
        return False, f"col_count: got {len(actual_cols)} expected {len(expected_cols)}"
    
    # Build sets for order-insensitive comparison
    actual_set = {(c.get("name", "").lower().strip(), normalize_type(c.get("type", ""))) for c in actual_cols}
    expected_set = {(c.get("name", "").lower().strip(), normalize_type(c.get("type", ""))) for c in expected_cols}
    
    missing = expected_set - actual_set
    extra = actual_set - expected_set
    
    if missing or extra:
        details = []
        if missing:
            details.append(f"missing: {sorted(missing)[:5]}")
        if extra:
            details.append(f"extra: {sorted(extra)[:5]}")
        return False, "; ".join(details)
    
    return True, "match"


def run_scenario(model, scenario):
    prompt = build_user_prompt(scenario)
    raw, latency, usage = call_openrouter(model, prompt)
    actual = parse_schema(raw)
    match, detail = schemas_match(actual, scenario["expectedOutput"])
    
    return {
        "id": scenario["id"],
        "blueprint": scenario["blueprintKey"],
        "match": match,
        "detail": detail,
        "latency_s": round(latency, 3),
        "prompt_tokens": usage.get("prompt_tokens", 0),
        "completion_tokens": usage.get("completion_tokens", 0),
        "raw_output": raw[:200] if raw else None,
        "tags": scenario.get("tags", [])
    }


def run_model_benchmark(model, scenarios, max_parallel=5):
    print(f"\n{'='*60}")
    print(f"BENCHMARKING: {model}")
    print(f"{'='*60}")
    
    results = []
    total = len(scenarios)
    
    with ThreadPoolExecutor(max_workers=max_parallel) as executor:
        futures = {executor.submit(run_scenario, model, s): s for s in scenarios}
        done = 0
        for future in as_completed(futures):
            done += 1
            result = future.result()
            results.append(result)
            status = "PASS" if result["match"] else f"FAIL ({result['detail']})"
            if done % 10 == 0 or not result["match"]:
                print(f"  [{done}/{total}] {result['id']}: {status} ({result['latency_s']}s)")
    
    # Compute stats
    passes = sum(1 for r in results if r["match"])
    fails = [r for r in results if not r["match"]]
    avg_latency = sum(r["latency_s"] for r in results) / len(results)
    total_prompt = sum(r["prompt_tokens"] for r in results)
    total_completion = sum(r["completion_tokens"] for r in results)
    
    # Group failures by blueprint
    fail_by_bp = defaultdict(list)
    for f in fails:
        fail_by_bp[f["blueprint"]].append(f)
    
    # Group accuracy by blueprint
    acc_by_bp = defaultdict(lambda: {"pass": 0, "total": 0})
    for r in results:
        acc_by_bp[r["blueprint"]]["total"] += 1
        if r["match"]:
            acc_by_bp[r["blueprint"]]["pass"] += 1
    
    print(f"\n--- RESULTS: {model} ---")
    print(f"  Accuracy: {passes}/{total} ({100*passes/total:.1f}%)")
    print(f"  Avg latency: {avg_latency:.2f}s")
    print(f"  Total tokens: {total_prompt} prompt + {total_completion} completion")
    
    print(f"\n  Accuracy by blueprint:")
    for bp, stats in sorted(acc_by_bp.items()):
        pct = 100 * stats["pass"] / stats["total"]
        marker = " ***" if pct < 100 else ""
        print(f"    {bp}: {stats['pass']}/{stats['total']} ({pct:.0f}%){marker}")
    
    if fails:
        print(f"\n  FAILURES ({len(fails)}):")
        for f in fails[:20]:
            print(f"    {f['id']} ({f['blueprint']}): {f['detail']}")
            if f["raw_output"]:
                print(f"      raw: {f['raw_output'][:120]}...")
    
    return {
        "model": model,
        "accuracy": passes / total,
        "accuracy_pct": f"{100*passes/total:.1f}%",
        "avg_latency_s": round(avg_latency, 3),
        "total_prompt_tokens": total_prompt,
        "total_completion_tokens": total_completion,
        "pass_count": passes,
        "fail_count": len(fails),
        "total_count": total,
        "accuracy_by_blueprint": {bp: {"pass": s["pass"], "total": s["total"]} for bp, s in acc_by_bp.items()},
        "failures": [{"id": f["id"], "blueprint": f["blueprint"], "detail": f["detail"]} for f in fails]
    }


def main():
    scenarios_path = os.path.join(os.path.dirname(__file__), "schema-inference-scenarios.json")
    if not os.path.exists(scenarios_path):
        scenarios_path = "/Users/aameradam/projects/dev/PULSE/backend/src/test/resources/schema-inference-scenarios.json"
    
    with open(scenarios_path) as f:
        scenarios = json.load(f)
    
    print(f"Loaded {len(scenarios)} test scenarios")
    
    # Parse CLI args
    models_to_test = MODELS
    if len(sys.argv) > 1:
        if sys.argv[1] == "--quick":
            # Quick mode: test first 20 scenarios on all models
            scenarios = scenarios[:20]
            print(f"Quick mode: testing {len(scenarios)} scenarios")
        elif sys.argv[1] == "--model":
            models_to_test = [sys.argv[2]]
            print(f"Single model mode: {models_to_test[0]}")
    
    all_results = []
    for model in models_to_test:
        result = run_model_benchmark(model, scenarios, max_parallel=5)
        all_results.append(result)
    
    # Summary comparison
    print(f"\n{'='*60}")
    print("BENCHMARK SUMMARY")
    print(f"{'='*60}")
    print(f"{'Model':<35} {'Accuracy':>10} {'Avg Latency':>12} {'Prompt Tok':>12} {'Compl Tok':>12}")
    print("-" * 81)
    for r in sorted(all_results, key=lambda x: -x["accuracy"]):
        print(f"{r['model']:<35} {r['accuracy_pct']:>10} {r['avg_latency_s']:>10.2f}s {r['total_prompt_tokens']:>12} {r['total_completion_tokens']:>12}")
    
    # Save results
    results_path = os.path.join(os.path.dirname(scenarios_path), "benchmark-results.json")
    with open(results_path, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nDetailed results saved to: {results_path}")


if __name__ == "__main__":
    main()
