/**
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-60 regression
 * guard).
 *
 * BUG-60 was: PULSE shipped a `ScaffoldPreview` TS interface in
 * `types/index.ts` with a comment explicitly referencing the backend API
 * (`POST /api/v1/tenants/{tenantId}/onboard/preview`), but NO component
 * imported it — the wizard's Step 3 was supposed to render this preview but
 * the wiring was forgotten. The interface sat unused, the wizard rendered
 * nothing, and the operator's chat hint pointed at a feature that didn't
 * exist in the UI.
 *
 * This test enumerates every interface/type alias in
 * `frontend/src/types/index.ts` whose preceding comment block references
 * `/api/v1/...` (i.e. the type was created to model an API response), and
 * asserts that AT LEAST ONE file under `frontend/src/` imports it. A type
 * that nobody imports is the BUG-60 shape exactly — model written, render
 * never wired.
 *
 * ## Scope (per DEVIATIONS.md §5)
 *
 * We only flag types whose comment block contains an `/api/v1/...` reference.
 * A type with no API comment is exempt; that's intentional under the BUG-60
 * framing (which is specifically about API-shape types being orphaned).
 * Adding a heuristic that flags every comment-less type would generate false
 * positives on internal scaffolding types like `ScaffoldPreviewDomain` whose
 * sole purpose is to be a field of another type.
 *
 * The test reads files via Node `fs` (synchronous) because (a) Vitest
 * supports it without extra plumbing and (b) it keeps the test as a pure
 * source-scan, no module loading required.
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const REPO_ROOT = resolve(__dirname, "..", "..", "..", "..");
const FRONTEND_SRC = resolve(__dirname, "..", "..");
const TYPES_FILE = resolve(FRONTEND_SRC, "types", "index.ts");

/**
 * Walks `frontend/src/` (excluding `test/` and `node_modules/`) and returns
 * the absolute paths of every `.ts`/`.tsx` file. Used to scan for imports
 * of each candidate type.
 */
function walkFrontendSrc(): string[] {
  const out: string[] = [];
  function walk(dir: string): void {
    const entries = readdirSync(dir);
    for (const entry of entries) {
      const full = join(dir, entry);
      const st = statSync(full);
      if (st.isDirectory()) {
        // Skip the test/ tree (the orphan check is about runtime use, not
        // test fixtures) and node_modules (irrelevant).
        if (entry === "test" || entry === "node_modules" || entry === ".next") continue;
        walk(full);
      } else if (st.isFile()) {
        if (entry.endsWith(".ts") || entry.endsWith(".tsx")) {
          out.push(full);
        }
      }
    }
  }
  walk(FRONTEND_SRC);
  return out;
}

/**
 * Extracts every `export interface FooBar` / `export type FooBar = …` /
 * `export class FooBar` declared in `types/index.ts` whose preceding
 * comment block mentions `/api/v1/`. Returns the type name only — the
 * import-check is name-based.
 *
 * The "preceding comment" is any `// …` line OR `/* … *​/` block that ends
 * within ~20 lines before the `export interface`/`export type` line. That's
 * generous enough to catch multi-paragraph doc comments and tight enough to
 * not pick up unrelated comments two screens up.
 */
function extractApiTypedeclarations(typesFileBody: string): string[] {
  const lines = typesFileBody.split("\n");
  const apiTypes: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const m =
      /^export\s+(?:interface|type|class)\s+([A-Z][A-Za-z0-9_]*)/.exec(line);
    if (!m) continue;
    const typeName = m[1];
    // Walk backwards from i-1 up to 20 lines collecting comment lines until
    // we hit a blank line or a non-comment line.
    const comments: string[] = [];
    for (let j = i - 1; j >= Math.max(0, i - 25); j--) {
      const prev = lines[j].trim();
      if (prev === "") break;
      // Accept `// …`, `/** …`, `* …`, `*/`, `/* …`
      if (
        prev.startsWith("//") ||
        prev.startsWith("*") ||
        prev.startsWith("/*")
      ) {
        comments.unshift(prev);
      } else {
        break;
      }
    }
    const commentBlock = comments.join("\n");
    if (/\/api\/v1\//.test(commentBlock)) {
      apiTypes.push(typeName);
    }
  }
  return apiTypes;
}

/**
 * True iff at least one file under `frontend/src/` (excluding `types/index.ts`
 * itself and the test tree) contains an import that names `typeName`.
 * Accepts named imports (`import { Foo } from …`), aliased imports
 * (`import { Foo as Bar } from …`), and type-only imports
 * (`import type { Foo } from …`). All three reach the BUG-60 contract: if
 * the type appears in any import binding, someone is at least referencing it.
 */
function isImportedByAnyComponent(typeName: string, files: string[]): boolean {
  // Word-boundary regex so `Foo` does not match `FooBar`.
  const re = new RegExp(
    `import\\s+(?:type\\s+)?[^;]*\\b${typeName}\\b[^;]*from\\s+["'][^"']+["']`,
    "m",
  );
  for (const file of files) {
    if (file === TYPES_FILE) continue;
    // Skip the test tree — orphan check is about runtime usage.
    if (file.includes(`${"/"}test${"/"}`)) continue;
    const body = readFileSync(file, "utf8");
    if (re.test(body)) return true;
  }
  return false;
}

/**
 * Returns true iff `typeName` appears as a NESTED field type inside another
 * declaration in `types/index.ts`. Such types are reached transitively via
 * their parent interface and don't need their own component-level import —
 * the orphan check should skip them.
 *
 * Example: `ScaffoldPreviewDomain` is the element type of
 * `ScaffoldPreview.domains: ScaffoldPreviewDomain[]`. Anyone importing
 * `ScaffoldPreview` already has full structural access; flagging it as an
 * orphan would be a false positive.
 */
function isNestedFieldType(typeName: string, typesBody: string): boolean {
  // Strip the type's own declaration so we don't match its own forward
  // reference. We capture from `export interface FooBar {` through the
  // matching closing `}` and replace it with a stub.
  const decl = new RegExp(
    `export\\s+(?:interface|type|class)\\s+${typeName}\\b[\\s\\S]*?^}`,
    "m",
  );
  const without = typesBody.replace(decl, "");
  // Anywhere else in the file: a colon followed by typeName (with optional
  // [] array suffix) — that's a structural usage as a field type.
  const usage = new RegExp(`:\\s*${typeName}\\b`);
  return usage.test(without);
}

describe("orphan-type contract (BUG-67 / BUG-60 regression guard)", () => {
  it("every TS type whose comment references /api/v1/... is imported by at least one component (or is a nested field type)", () => {
    const typesBody = readFileSync(TYPES_FILE, "utf8");
    const apiTypes = extractApiTypedeclarations(typesBody);
    expect(apiTypes.length, "expected at least one /api/v1-commented type in types/index.ts").toBeGreaterThan(0);

    const files = walkFrontendSrc();
    const orphans: string[] = [];
    for (const t of apiTypes) {
      if (isImportedByAnyComponent(t, files)) continue;
      if (isNestedFieldType(t, typesBody)) continue;
      orphans.push(t);
    }

    expect(
      orphans,
      `Every TS type in frontend/src/types/index.ts whose comment block references /api/v1/... ` +
        `must be imported by at least one file under frontend/src/ (excluding test/), OR be ` +
        `reached transitively as a nested field of another exported type. ` +
        `Orphan types below indicate a BUG-60 regression: the type was created to model an API ` +
        `response, the operator's chat hint or wizard step references that response, but NO ` +
        `component actually consumes it — the UI is silently broken.\n\n` +
        `Orphan types: ${orphans.join(", ")}\n\n` +
        `EXPECTED-PASS-AFTER status: as of the SU-8 META-packet commit, ` +
        `SubPipelineInstanceResponse is a known orphan — the composition surface ` +
        `(POST /api/v1/versions/:versionId/composition/instances) returns this shape but ` +
        `no React component imports or renders the canonical / deprecations / warnings ` +
        `fields. That's a sibling BUG-60 regression to file. Test goes green when the ` +
        `consuming component is wired OR the type is removed.`,
    ).toEqual([]);
  });
});

// Mark REPO_ROOT as referenced so an unused-variable lint doesn't trip the
// build (we keep it for future test extensions that might need to walk the
// backend tree to confirm an endpoint actually exists for each API comment).
void REPO_ROOT;
