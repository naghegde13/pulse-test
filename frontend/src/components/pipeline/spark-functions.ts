/**
 * Shared Spark SQL function registry (G-1 — SPEC-construct-library.md §inventory,
 * IMPL §6 "Shared seam").
 *
 * SINGLE SOURCE OF TRUTH for the Spark-function palette. Previously this lived
 * inline in `sql-filter-builder.tsx` (the one and only copy). It is now hoisted
 * here so EVERY construct that needs the palette — the condition-builder (S5),
 * the rich sql-chain-editor (S1a), and any future SQL-authoring surface — imports
 * the SAME list. Do NOT fork a second copy inline; import `SPARK_FUNCTIONS` from
 * this module.
 *
 * RECONCILE TARGET (forward): the authoritative function catalog is the Calcite
 * function registry (SPEC-calcite-sql-model.md G-1). When CALCITE-PHASE-2 lands,
 * this static list is replaced by / validated against the Calcite registry so the
 * palette never advertises a function the validator rejects. Until then this
 * curated list is the design-time palette.
 */

export interface SparkFunction {
  name: string;
  signature: string;
  description: string;
}

export interface SparkFunctionCategory {
  category: string;
  functions: SparkFunction[];
}

export const SPARK_FUNCTIONS: SparkFunctionCategory[] = [
  {
    category: "String",
    functions: [
      { name: "UPPER", signature: "UPPER(col)", description: "Convert to uppercase" },
      { name: "LOWER", signature: "LOWER(col)", description: "Convert to lowercase" },
      { name: "TRIM", signature: "TRIM(col)", description: "Remove leading/trailing whitespace" },
      { name: "LTRIM", signature: "LTRIM(col)", description: "Remove leading whitespace" },
      { name: "RTRIM", signature: "RTRIM(col)", description: "Remove trailing whitespace" },
      { name: "LENGTH", signature: "LENGTH(col)", description: "String length" },
      { name: "SUBSTRING", signature: "SUBSTRING(col, pos, len)", description: "Extract substring" },
      { name: "CONCAT", signature: "CONCAT(col1, col2, ...)", description: "Concatenate strings" },
      { name: "CONCAT_WS", signature: "CONCAT_WS(sep, col1, col2)", description: "Concatenate with separator" },
      { name: "REPLACE", signature: "REPLACE(col, search, replace)", description: "Replace occurrences" },
      { name: "REGEXP_REPLACE", signature: "REGEXP_REPLACE(col, pattern, replacement)", description: "Regex replace" },
      { name: "REGEXP_EXTRACT", signature: "REGEXP_EXTRACT(col, pattern, idx)", description: "Extract regex group" },
      { name: "SPLIT", signature: "SPLIT(col, pattern)", description: "Split string into array" },
      { name: "LPAD", signature: "LPAD(col, len, pad)", description: "Left-pad string" },
      { name: "RPAD", signature: "RPAD(col, len, pad)", description: "Right-pad string" },
      { name: "REVERSE", signature: "REVERSE(col)", description: "Reverse string" },
      { name: "INITCAP", signature: "INITCAP(col)", description: "Capitalize first letter of each word" },
      { name: "SOUNDEX", signature: "SOUNDEX(col)", description: "Soundex encoding" },
      { name: "TRANSLATE", signature: "TRANSLATE(col, from, to)", description: "Character-level translation" },
    ],
  },
  {
    category: "Numeric",
    functions: [
      { name: "ABS", signature: "ABS(col)", description: "Absolute value" },
      { name: "CEIL", signature: "CEIL(col)", description: "Round up to nearest integer" },
      { name: "FLOOR", signature: "FLOOR(col)", description: "Round down to nearest integer" },
      { name: "ROUND", signature: "ROUND(col, d)", description: "Round to d decimal places" },
      { name: "SQRT", signature: "SQRT(col)", description: "Square root" },
      { name: "POWER", signature: "POWER(base, exp)", description: "Raise to power" },
      { name: "MOD", signature: "MOD(a, b)", description: "Modulo" },
      { name: "LOG", signature: "LOG(base, col)", description: "Logarithm" },
      { name: "LN", signature: "LN(col)", description: "Natural logarithm" },
      { name: "EXP", signature: "EXP(col)", description: "Exponential" },
      { name: "GREATEST", signature: "GREATEST(col1, col2, ...)", description: "Max of values" },
      { name: "LEAST", signature: "LEAST(col1, col2, ...)", description: "Min of values" },
      { name: "SIGN", signature: "SIGN(col)", description: "Sign (-1, 0, 1)" },
    ],
  },
  {
    category: "Date/Time",
    functions: [
      { name: "CURRENT_DATE", signature: "CURRENT_DATE()", description: "Current date" },
      { name: "CURRENT_TIMESTAMP", signature: "CURRENT_TIMESTAMP()", description: "Current timestamp" },
      { name: "DATE_FORMAT", signature: "DATE_FORMAT(date, fmt)", description: "Format date as string" },
      { name: "TO_DATE", signature: "TO_DATE(col, fmt)", description: "Parse string to date" },
      { name: "TO_TIMESTAMP", signature: "TO_TIMESTAMP(col, fmt)", description: "Parse string to timestamp" },
      { name: "DATEDIFF", signature: "DATEDIFF(end, start)", description: "Days between dates" },
      { name: "DATE_ADD", signature: "DATE_ADD(date, days)", description: "Add days to date" },
      { name: "DATE_SUB", signature: "DATE_SUB(date, days)", description: "Subtract days from date" },
      { name: "ADD_MONTHS", signature: "ADD_MONTHS(date, months)", description: "Add months" },
      { name: "MONTHS_BETWEEN", signature: "MONTHS_BETWEEN(d1, d2)", description: "Months between dates" },
      { name: "YEAR", signature: "YEAR(date)", description: "Extract year" },
      { name: "MONTH", signature: "MONTH(date)", description: "Extract month" },
      { name: "DAY", signature: "DAY(date)", description: "Extract day" },
      { name: "DAYOFWEEK", signature: "DAYOFWEEK(date)", description: "Day of week (1=Sun)" },
      { name: "HOUR", signature: "HOUR(ts)", description: "Extract hour" },
      { name: "MINUTE", signature: "MINUTE(ts)", description: "Extract minute" },
      { name: "SECOND", signature: "SECOND(ts)", description: "Extract second" },
      { name: "LAST_DAY", signature: "LAST_DAY(date)", description: "Last day of month" },
      { name: "TRUNC", signature: "TRUNC(date, fmt)", description: "Truncate date to unit" },
      { name: "DATE_TRUNC", signature: "DATE_TRUNC(fmt, ts)", description: "Truncate timestamp to unit" },
      { name: "FROM_UNIXTIME", signature: "FROM_UNIXTIME(unix, fmt)", description: "Unix timestamp to string" },
      { name: "UNIX_TIMESTAMP", signature: "UNIX_TIMESTAMP(ts, fmt)", description: "To unix timestamp" },
    ],
  },
  {
    category: "Conditional",
    functions: [
      { name: "COALESCE", signature: "COALESCE(col1, col2, ...)", description: "First non-null value" },
      { name: "NVL", signature: "NVL(col, default)", description: "Replace null with default" },
      { name: "NVL2", signature: "NVL2(col, not_null_val, null_val)", description: "If col not null return not_null_val, else null_val" },
      { name: "NULLIF", signature: "NULLIF(col1, col2)", description: "Null if col1 equals col2" },
      { name: "IF", signature: "IF(cond, true_val, false_val)", description: "Conditional expression" },
      { name: "CASE WHEN", signature: "CASE WHEN cond THEN val ... ELSE default END", description: "Multi-branch conditional" },
      { name: "DECODE", signature: "DECODE(col, val1, res1, ...)", description: "Map values to results" },
    ],
  },
  {
    category: "Type Cast",
    functions: [
      { name: "CAST", signature: "CAST(col AS type)", description: "Cast to type (STRING, INT, DOUBLE, DATE, etc.)" },
      { name: "INT", signature: "INT(col)", description: "Cast to integer" },
      { name: "DOUBLE", signature: "DOUBLE(col)", description: "Cast to double" },
      { name: "STRING", signature: "STRING(col)", description: "Cast to string" },
      { name: "BOOLEAN", signature: "BOOLEAN(col)", description: "Cast to boolean" },
      { name: "DECIMAL", signature: "DECIMAL(col, precision, scale)", description: "Cast to decimal" },
    ],
  },
  {
    category: "Aggregate",
    functions: [
      { name: "SUM", signature: "SUM(col)", description: "Sum of values" },
      { name: "COUNT", signature: "COUNT(col)", description: "Count of values" },
      { name: "AVG", signature: "AVG(col)", description: "Average" },
      { name: "MIN", signature: "MIN(col)", description: "Minimum value" },
      { name: "MAX", signature: "MAX(col)", description: "Maximum value" },
      { name: "COUNT_DISTINCT", signature: "COUNT(DISTINCT col)", description: "Count distinct values" },
      { name: "COLLECT_LIST", signature: "COLLECT_LIST(col)", description: "Collect into array (with duplicates)" },
      { name: "COLLECT_SET", signature: "COLLECT_SET(col)", description: "Collect into array (unique)" },
      { name: "FIRST", signature: "FIRST(col)", description: "First value in group" },
      { name: "LAST", signature: "LAST(col)", description: "Last value in group" },
      { name: "STDDEV", signature: "STDDEV(col)", description: "Standard deviation" },
      { name: "VARIANCE", signature: "VARIANCE(col)", description: "Variance" },
      { name: "PERCENTILE_APPROX", signature: "PERCENTILE_APPROX(col, pct)", description: "Approximate percentile" },
    ],
  },
  {
    category: "Window",
    functions: [
      { name: "ROW_NUMBER", signature: "ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)", description: "Sequential row number" },
      { name: "RANK", signature: "RANK() OVER (...)", description: "Rank with gaps" },
      { name: "DENSE_RANK", signature: "DENSE_RANK() OVER (...)", description: "Rank without gaps" },
      { name: "LAG", signature: "LAG(col, n, default) OVER (...)", description: "Value n rows before" },
      { name: "LEAD", signature: "LEAD(col, n, default) OVER (...)", description: "Value n rows after" },
      { name: "NTILE", signature: "NTILE(n) OVER (...)", description: "Distribute into n buckets" },
      { name: "CUME_DIST", signature: "CUME_DIST() OVER (...)", description: "Cumulative distribution" },
    ],
  },
  {
    category: "Collection",
    functions: [
      { name: "SIZE", signature: "SIZE(array_or_map)", description: "Size of array or map" },
      { name: "ARRAY_CONTAINS", signature: "ARRAY_CONTAINS(array, val)", description: "Check if array contains value" },
      { name: "EXPLODE", signature: "EXPLODE(array_or_map)", description: "Flatten array/map to rows" },
      { name: "FLATTEN", signature: "FLATTEN(array_of_arrays)", description: "Flatten nested arrays" },
      { name: "ARRAY", signature: "ARRAY(val1, val2, ...)", description: "Create array" },
      { name: "MAP", signature: "MAP(k1, v1, k2, v2, ...)", description: "Create map" },
      { name: "GET_JSON_OBJECT", signature: "GET_JSON_OBJECT(json, path)", description: "Extract from JSON string" },
      { name: "FROM_JSON", signature: "FROM_JSON(json, schema)", description: "Parse JSON to struct" },
      { name: "TO_JSON", signature: "TO_JSON(struct)", description: "Convert struct to JSON" },
    ],
  },
];

/** Flat list of every function name (for autocomplete / unknown-function checks). */
export const SPARK_FUNCTION_NAMES: string[] = SPARK_FUNCTIONS.flatMap((c) =>
  c.functions.map((f) => f.name)
);
