/**
 * SU-6 / BUG-59: Lint-style guard rule.
 *
 * One sub-assertion (BUG-59): components that fetch tenant-scoped or
 * user-scoped identity from /api/v1/users/me/git-identity (or any
 * /api/v1/tenants/{...} route) must NOT do so inside a useEffect with
 * an empty dependency array. Empty-deps + identity-dependent state is
 * exactly the shape that ships with the "panel never re-fetches after
 * register/rotate" bug.
 *
 * Allowed patterns:
 *   - useEffect(..., [tenantId, ...])    — re-fires on tenant change
 *   - useEffect(..., [reloadToken])      — re-fires on parent signal
 *   - useEffect(..., [refresh])          — re-fires on memoized callback
 *   - useCallback(...) for the fetcher with a separate trigger
 *
 * Implemented as a source-scan rather than an ESLint custom plugin to
 * keep the toolchain dependency-light. The scan is intentionally
 * scoped to the settings panels that own the seam.
 */

import { describe, expect, it } from "vitest";
import { readFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(__dirname, "..", "..", "..");

const AUDITED_FILES = [
  "src/components/settings/github-pat-identity-panel.tsx",
  "src/components/settings/storage-backends-panel.tsx",
  "src/components/settings/runtime-bindings-panel.tsx",
];

const TENANT_OR_IDENTITY_URL =
  /\/api\/v1\/(users\/me\/git-identity|tenants\/)/;
const USE_EFFECT_EMPTY_DEPS = /useEffect\([^)]*?,\s*\[\s*\]\s*\)/s;

interface Offender {
  file: string;
  excerpt: string;
}

function scanFile(path: string): Offender[] {
  const full = resolve(ROOT, path);
  if (!existsSync(full)) {
    throw new Error(`Lint rule audited file is missing: ${path}`);
  }
  const src = readFileSync(full, "utf8");
  const offenders: Offender[] = [];

  // Iterate every useEffect block; if its deps array is empty AND its
  // body references a tenant-scoped or identity URL, flag it.
  const useEffectBlock =
    /useEffect\(\s*\(\s*\)\s*=>\s*\{([\s\S]*?)\}\s*,\s*\[\s*\]\s*\)/g;
  let m: RegExpExecArray | null;
  while ((m = useEffectBlock.exec(src)) !== null) {
    const body = m[1];
    if (TENANT_OR_IDENTITY_URL.test(body)) {
      const excerpt = m[0].slice(0, 240).replace(/\s+/g, " ").trim();
      offenders.push({ file: path, excerpt });
    }
  }

  // Also catch the shorter `useEffect(() => { ... }, [])` form with a
  // body that calls a helper that fetches identity. We can't follow the
  // call graph statically, but we can detect the *exact* anti-pattern
  // that shipped with BUG-59: a refresh()/refetch() invocation inside
  // an empty-deps useEffect.
  const refreshPattern =
    /useEffect\(\s*\(\s*\)\s*=>\s*\{[^}]*?(refresh|refetch|loadIdentity)\(\)[^}]*?\}\s*,\s*\[\s*\]\s*\)/gs;
  void USE_EFFECT_EMPTY_DEPS; // reserved for future direct empty-deps checks
  let r: RegExpExecArray | null;
  while ((r = refreshPattern.exec(src)) !== null) {
    const excerpt = r[0].slice(0, 240).replace(/\s+/g, " ").trim();
    offenders.push({ file: path, excerpt });
  }

  return offenders;
}

describe("Lint rule: no empty-deps tenant/identity fetch (BUG-59)", () => {
  for (const file of AUDITED_FILES) {
    it(`${file} does not fetch tenant/identity inside a useEffect with empty deps`, () => {
      const offenders = scanFile(file);
      if (offenders.length > 0) {
        const detail = offenders
          .map((o, idx) => `  [${idx + 1}] ${o.file}\n      ${o.excerpt}`)
          .join("\n");
        throw new Error(
          "Empty-deps useEffect with tenant/identity fetch detected. " +
            "Use a reloadToken or pass refresh() in as a dep so the " +
            "panel re-fetches after register/rotate (see BUG-59).\n" +
            detail,
        );
      }
      expect(offenders).toHaveLength(0);
    });
  }
});
