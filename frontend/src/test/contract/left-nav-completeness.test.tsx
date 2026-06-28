/**
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-68 regression
 * guard).
 *
 * BUG-68 was: the EBCDIC/COBOL/Copybook Discovery page (page + context + 14
 * backend files) was silently dropped from the left-nav at some commit but
 * the page code stayed in the tree. Operators couldn't find it; the
 * maintenance cost stayed on the books. Worst-of-both-worlds.
 *
 * This test enumerates every `app/<segment>/page.tsx` (the Next.js App
 * Router convention for a top-level route) and asserts the segment appears
 * in the left-nav's `navItems` array OR is explicitly listed in
 * `INTENTIONAL_NAV_EXCLUSIONS` below. A new page that lands without either
 * being added to navItems or to the exclusion list is the BUG-68 shape
 * exactly.
 *
 * ## Why source-scan, not @testing-library/react?
 *
 * The original brief suggested mounting the layout and inspecting the
 * rendered DOM. That requires building a viable Auth + Tenant + Domain
 * context fixture, which (as the existing test suite shows) is a non-trivial
 * Provider stack. A source-scan against the `navItems` const in
 * `app-sidebar.tsx` is simpler, faster, and tests the same contract: every
 * page has a nav link. If the layout component is later refactored to
 * derive navItems dynamically (e.g. from a config file), update the
 * `NAV_ITEMS_SOURCE` constant below to point at the new source of truth.
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const FRONTEND_SRC = resolve(__dirname, "..", "..");
const APP_DIR = resolve(FRONTEND_SRC, "app");
const NAV_ITEMS_SOURCE = resolve(FRONTEND_SRC, "components", "layout", "app-sidebar.tsx");

/**
 * Routes that legitimately exist as `app/<seg>/page.tsx` but should NOT be
 * in the left-nav. Each entry needs a reason comment so future contributors
 * can challenge or extend the list.
 */
const INTENTIONAL_NAV_EXCLUSIONS = new Set<string>([
  // "/" is the dashboard; the sidebar's "Dashboard" link points at it.
  // It is in navItems but as href "/", not "/dashboard".
  // Listed defensively in case the layout refactor moves things around.
  "",
]);

/**
 * Walks `app/` and returns the list of top-level segments that have a
 * `page.tsx` (i.e. they are routes the user can navigate to). Dynamic
 * segments like `[pipelineId]` are detail pages reached via row-click from
 * a list page — never directly nav'd — and are therefore excluded.
 */
function topLevelRouteSegments(): string[] {
  const segments: string[] = [];
  const entries = readdirSync(APP_DIR);
  for (const entry of entries) {
    const full = join(APP_DIR, entry);
    const st = statSync(full);
    if (!st.isDirectory()) continue;
    if (entry.startsWith("[") && entry.endsWith("]")) continue; // dynamic
    if (entry.startsWith("_") || entry.startsWith(".")) continue; // private
    // Must contain a page.tsx (other directories may be route groups that
    // don't render).
    const pageFile = join(full, "page.tsx");
    try {
      if (statSync(pageFile).isFile()) {
        segments.push(entry);
      }
    } catch {
      // No page.tsx → not a top-level navigable route.
    }
  }
  // The root `/` route lives at `app/page.tsx` (no segment). Add an empty
  // string token so the assertion below can map it cleanly.
  try {
    if (statSync(join(APP_DIR, "page.tsx")).isFile()) {
      segments.push("");
    }
  } catch {
    // No root page — unusual but valid for some app layouts.
  }
  return segments;
}

/**
 * Reads the sidebar source and extracts every `href: "/foo"` literal from
 * the `navItems` array declaration. Returns the path segments (no leading
 * slash; "/" → "").
 */
function navItemHrefs(): string[] {
  const body = readFileSync(NAV_ITEMS_SOURCE, "utf8");
  // Locate the `const navItems = [...]` block. We capture up to the
  // matching closing bracket-semicolon. The regex is tolerant of
  // whitespace and trailing commas.
  const block = /const\s+navItems\s*=\s*\[([\s\S]*?)\]\s*;/.exec(body);
  if (!block) {
    throw new Error(
      `Could not locate the navItems array in ${NAV_ITEMS_SOURCE}. If the ` +
        `sidebar source was refactored, update NAV_ITEMS_SOURCE / this regex.`,
    );
  }
  const inner = block[1];
  const hrefRe = /href\s*:\s*["'`](\/[^"'`]*)["'`]/g;
  const hrefs: string[] = [];
  let m: RegExpExecArray | null;
  while ((m = hrefRe.exec(inner)) !== null) {
    const path = m[1];
    if (path === "/") hrefs.push("");
    else hrefs.push(path.replace(/^\//, "").split("/")[0]);
  }
  return hrefs;
}

describe("left-nav completeness contract (BUG-67 / BUG-68 regression guard)", () => {
  it("every top-level app/<segment>/page.tsx is reachable from navItems or in the exclusion list", () => {
    const routes = topLevelRouteSegments();
    expect(routes.length, "expected at least a handful of top-level routes under app/").toBeGreaterThan(2);

    const navHrefs = new Set(navItemHrefs());
    expect(navHrefs.size, "expected navItems to declare at least one href").toBeGreaterThan(0);

    const orphaned: string[] = [];
    for (const seg of routes) {
      if (navHrefs.has(seg)) continue;
      if (INTENTIONAL_NAV_EXCLUSIONS.has(seg)) continue;
      orphaned.push(seg === "" ? "/" : `/${seg}`);
    }

    expect(
      orphaned,
      `Every top-level page under app/ should be reachable from the left-nav unless it is in ` +
        `INTENTIONAL_NAV_EXCLUSIONS with a justification comment. Orphaned routes below mean a ` +
        `feature has shipped a page but the nav link is missing — the BUG-68 shape exactly. ` +
        `Either add the route to navItems in components/layout/app-sidebar.tsx, or add it to ` +
        `INTENTIONAL_NAV_EXCLUSIONS with a reason.\n\n` +
        `Orphaned routes: ${orphaned.join(", ")}`,
    ).toEqual([]);
  });

  it("every navItem href that looks like a route maps to a real page.tsx", () => {
    // Symmetric check: nav can also drift the other way (link to a page
    // that no longer exists, 404'ing operators). Less common than BUG-68
    // but worth guarding.
    const navHrefs = navItemHrefs();
    const routes = new Set(topLevelRouteSegments());

    const dangling: string[] = [];
    for (const seg of navHrefs) {
      if (!routes.has(seg)) {
        dangling.push(seg === "" ? "/" : `/${seg}`);
      }
    }

    expect(
      dangling,
      `Every navItem href that targets a top-level segment must map to a real ` +
        `app/<segment>/page.tsx. Dangling hrefs below would 404 when clicked:\n\n` +
        `Dangling hrefs: ${dangling.join(", ")}`,
    ).toEqual([]);
  });
});
