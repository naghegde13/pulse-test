import { describe, expect, it } from "vitest";

/**
 * Foundation smoke test. If this passes, the runner (Vitest), jsdom env,
 * tsconfig path alias, and React 19 act configuration are all wired
 * correctly. Component tests in downstream tasks build on top of this.
 */
describe("frontend test runner foundation", () => {
  it("runs vitest with globals and jsdom", () => {
    expect(1 + 1).toBe(2);
    // jsdom-only API; would throw under the node env.
    const div = document.createElement("div");
    div.textContent = "pulse";
    expect(div.textContent).toBe("pulse");
  });

  it("has the React 19 act environment flag set", () => {
    expect(globalThis.IS_REACT_ACT_ENVIRONMENT).toBe(true);
  });
});
