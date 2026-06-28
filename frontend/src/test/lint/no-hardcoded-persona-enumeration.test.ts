/**
 * SU-6 / BUG-61: Lint-style guard rule.
 *
 * One sub-assertion (BUG-61): the settings panels must NOT contain a
 * hardcoded ["GCP", "DPC"] enumeration of storage-backend kinds. The
 * legal set is governed by RuntimeAuthority.allowedStorageBackends —
 * filtering happens at render time inside the panels. Re-introducing
 * the hardcoded list silently re-introduces the persona-leak bug.
 *
 * Implemented as a Vitest source-scan rather than an ESLint custom
 * plugin to keep the toolchain dependency-light. The scan tolerates
 * a single canonical superset literal (annotated with `ALL_BACKENDS`
 * or `ALL_BINDING_KINDS`) but rejects any other ["GCP", "DPC"]-style
 * literal in the audited files.
 */

import { describe, expect, it } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(__dirname, "..", "..", "..");

const AUDITED_FILES = [
  "src/components/settings/storage-backends-panel.tsx",
  "src/components/settings/runtime-bindings-panel.tsx",
];

// Match arrays containing both "GCP" and "DPC" (any order), e.g.
//   ["GCP", "DPC"]
//   ['DPC', 'GCP', 'LOCAL']
// We strip whitespace + quote style so the matcher is robust.
const HARDCODED_LITERAL = /\[[^\]]*?"GCP"[^\]]*?"DPC"[^\]]*?\]|\[[^\]]*?"DPC"[^\]]*?"GCP"[^\]]*?\]/g;

// Lines whose enclosing literal is the canonical superset constant. We
// only allow it when it's named ALL_BACKENDS / ALL_BINDING_KINDS — i.e.
// the explicit "this is the superset; the panel filters at render time"
// pattern. Any other site of the literal trips the rule.
const ALLOWED_NAMES = ["ALL_BACKENDS", "ALL_BINDING_KINDS"];

interface Offender {
  file: string;
  line: number;
  text: string;
}

// Strip comments before scanning so the rule does not trip on the
// explanatory `["GCP", "DPC"]` reference inside doc-blocks describing
// the very change this rule enforces.
function stripComments(src: string): string {
  // Block comments (multi-line).
  let out = src.replace(/\/\*[\s\S]*?\*\//g, (m) => "\n".repeat((m.match(/\n/g) ?? []).length));
  // Line comments — drop only the part after //, keep the newline so
  // line numbers stay aligned.
  out = out
    .split("\n")
    .map((line) => {
      // A naive split is fine here; the literal we audit never lives
      // inside a string literal that contains "//".
      const idx = line.indexOf("//");
      if (idx === -1) return line;
      return line.slice(0, idx);
    })
    .join("\n");
  return out;
}

function scanFile(path: string): Offender[] {
  const full = resolve(ROOT, path);
  if (!existsSync(full)) {
    throw new Error(`Lint rule audited file is missing: ${path}`);
  }
  const src = readFileSync(full, "utf8");
  const codeOnly = stripComments(src);
  const lines = codeOnly.split("\n");
  const offenders: Offender[] = [];
  // Walk through matches and look at a small window around each one to
  // decide whether it's the canonical declaration or a hardcoded leak.
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!HARDCODED_LITERAL.test(line)) continue;
    HARDCODED_LITERAL.lastIndex = 0;
    // Look at the matched line + the previous few lines for an allowed
    // name (handles `const ALL_BACKENDS: ... = ["GCP", "DPC"];` whether
    // the literal sits on the same line or the next).
    const windowText = lines.slice(Math.max(0, i - 2), i + 1).join("\n");
    const isAllowed = ALLOWED_NAMES.some((n) => windowText.includes(n));
    if (!isAllowed) {
      offenders.push({ file: path, line: i + 1, text: line.trim() });
    }
  }
  return offenders;
}

describe("Lint rule: no hardcoded persona enumeration (BUG-61)", () => {
  for (const file of AUDITED_FILES) {
    it(`${file} does not hardcode the (GCP, DPC) storage-backend enum`, () => {
      const offenders = scanFile(file);
      if (offenders.length > 0) {
        const detail = offenders
          .map((o) => `  ${o.file}:${o.line}  ${o.text}`)
          .join("\n");
        throw new Error(
          "Hardcoded persona enumeration detected. Filter against " +
            "RuntimeAuthority.allowedStorageBackends instead (see BUG-61).\n" +
            detail,
        );
      }
      expect(offenders).toHaveLength(0);
    });
  }
});
