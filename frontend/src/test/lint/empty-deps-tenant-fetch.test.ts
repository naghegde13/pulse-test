/**
 * Layer 2.5 lint test (BUG-2026-05-26-67 META-packet, BUG-59 regression
 * guard).
 *
 * BUG-59 was: the onboarding wizard fetched the user's git identity inside
 * a `useEffect(() => {...}, [])` block — empty deps. After PAT registration
 * the identity changed but the effect never re-ran, so the wizard kept
 * showing "Initialize is disabled because PAT is missing" even though the
 * PAT was now present. Operator had to refresh the browser.
 *
 * This lint test scans every file under `frontend/src/app/settings/` and
 * `frontend/src/components/settings/` for the empty-deps useEffect pattern
 * AND a tenant-scoped fetch inside its body. Tenant-scoped here means the
 * fetch URL contains `/tenants/` or the effect references a tenantId-bearing
 * variable. Anything matching is flagged.
 *
 * ## Allowlist
 *
 * Some legitimate one-shot effects exist (e.g., mounting a chart library,
 * subscribing to an EventSource once). They live in the EMPTY_DEPS_ALLOWLIST
 * below with a per-entry justification comment.
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const FRONTEND_SRC = resolve(__dirname, "..", "..");

/** Directories under which the empty-deps pattern is forbidden when it
 *  touches tenant data. Limited to settings/ surfaces because that's where
 *  the onboarding wizard + readiness panels live (the BUG-59 surface area).
 */
const SCOPED_DIRS = [
  resolve(FRONTEND_SRC, "app", "settings"),
  resolve(FRONTEND_SRC, "components", "settings"),
];

/**
 * Per-file path → reason allowlist. Empty by default; add entries with a
 * clear justification (effect is truly one-shot, no tenant dependency, etc.)
 * if the lint produces a false positive.
 */
const EMPTY_DEPS_ALLOWLIST: Record<string, string> = {};

const TENANT_SCOPED_HINT = /\/tenants\/|tenantId|currentTenant/;

function walkDirRecursive(root: string): string[] {
  const out: string[] = [];
  function go(dir: string) {
    let entries: string[];
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const e of entries) {
      const full = join(dir, e);
      const st = statSync(full);
      if (st.isDirectory()) go(full);
      else if (st.isFile() && (full.endsWith(".tsx") || full.endsWith(".ts"))) out.push(full);
    }
  }
  go(root);
  return out;
}

/**
 * Returns the list of useEffect-with-empty-deps occurrences in `source`,
 * with each occurrence's body. Uses bracket-counting rather than regex
 * backtracking so it correctly handles useEffect bodies containing nested
 * `{}` (object literals, inner functions) without conflating them with
 * sibling `useCallback`/`useMemo` calls below.
 */
function findEmptyDepsUseEffects(source: string): string[] {
  const bodies: string[] = [];
  const startPattern = /useEffect\s*\(\s*\(\s*\)\s*=>\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = startPattern.exec(source)) !== null) {
    const bodyStart = m.index + m[0].length;
    // Walk forward counting braces to find the matching close of the arrow
    // function body.
    let depth = 1;
    let i = bodyStart;
    while (i < source.length && depth > 0) {
      const ch = source[i];
      // Naive — does not skip strings/comments. False positives possible but
      // empirically rare in PULSE settings panels, and a false positive only
      // means an extra "needs allowlist" prompt at code-review time.
      if (ch === "{") depth++;
      else if (ch === "}") depth--;
      i++;
    }
    if (depth !== 0) continue;
    const bodyEnd = i - 1; // position of the closing `}`
    const body = source.substring(bodyStart, bodyEnd);
    // After the closing `}`, expect optional whitespace + `,` + the deps
    // literal. If the deps literal is `[]` (with optional whitespace), this
    // is the BUG-59 shape.
    const tail = source.substring(i);
    if (/^\s*,\s*\[\s*\]\s*\)/.test(tail)) {
      bodies.push(body);
    }
  }
  return bodies;
}

describe("empty-deps-tenant-fetch lint (BUG-67 / BUG-59 regression guard)", () => {
  it("flags useEffect(..., []) bodies in settings/ that fetch tenant-scoped data", () => {
    const files: string[] = [];
    for (const dir of SCOPED_DIRS) files.push(...walkDirRecursive(dir));
    expect(files.length, "expected at least one settings file to scan").toBeGreaterThan(0);

    const offenders: string[] = [];
    for (const file of files) {
      const rel = file.replace(`${FRONTEND_SRC}/`, "");
      const body = readFileSync(file, "utf8");
      const emptyDepsBodies = findEmptyDepsUseEffects(body);
      for (const effectBody of emptyDepsBodies) {
        if (TENANT_SCOPED_HINT.test(effectBody)) {
          if (EMPTY_DEPS_ALLOWLIST[rel]) {
            // Whitelisted — operator has explicitly OK'd this one-shot.
            continue;
          }
          offenders.push(`${rel}: useEffect(...) with [] deps references tenant-scoped data`);
        }
      }
    }

    expect(
      offenders,
      `useEffect(..., []) blocks in frontend/src/{app,components}/settings/ that reference ` +
        `tenant-scoped data (/tenants/, tenantId, currentTenant) are forbidden. They reproduce ` +
        `BUG-59: the effect runs once, fetched data goes stale on tenant switch / PAT registration, ` +
        `and the panel silently shows obsolete state. Fix by adding the relevant dependency to ` +
        `the deps array (tenantId is the usual one), or — if the effect is truly one-shot and tenant- ` +
        `independent — add the file path to EMPTY_DEPS_ALLOWLIST with a justification comment.\n\n` +
        `Offenders:\n  ${offenders.join("\n  ")}`,
    ).toEqual([]);
  });
});
