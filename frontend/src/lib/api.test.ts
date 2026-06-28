/**
 * Unit tests for src/lib/api.ts — the central HTTP client used by every
 * authenticated UI route. These tests lock in three pieces of behavior that
 * have historically been easy to regress:
 *
 *   1. Bearer header injection from localStorage('pulse_token').
 *   2. The 401 distinction: a 401 with the magic error string
 *      "Git authentication failed" surfaces a downstream-git error to the
 *      caller and must NOT force a PULSE session logout. Any other 401
 *      clears the token and dispatches the `pulse:logout` window event so
 *      AuthContext can tear the session down.
 *   3. The PULSE error envelope (`{ detail: "..." }`) parses cleanly and
 *      surfaces `detail` as the thrown Error message.
 *
 * We stub `fetch` globally per-test via `vi.stubGlobal`. The `mock-api` helper
 * is used where its request recorder is sufficient; the `postForm` case uses
 * a hand-rolled fetch mock because FormData bodies don't survive the
 * recorder's JSON.parse path.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installMockApi, type MockApi } from "@/test/mock-api";
import { api } from "@/lib/api";

const BASE = "http://localhost:8080";

/**
 * Install a deterministic in-memory `localStorage` on `window` for the
 * duration of the test. The CI environment can leak a Node-level Web Storage
 * stub that lacks `clear/setItem/getItem`, so we replace it with a small
 * polyfill that's compatible with the Storage API surface api.ts uses.
 */
function installLocalStorageShim(): Map<string, string> {
  const store = new Map<string, string>();
  const shim = {
    getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    get length() {
      return store.size;
    },
  };
  Object.defineProperty(window, "localStorage", {
    value: shim,
    configurable: true,
    writable: true,
  });
  return store;
}

describe("api client", () => {
  let mock: MockApi;
  let dispatchSpy: ReturnType<typeof vi.spyOn>;
  let storage: Map<string, string>;

  beforeEach(() => {
    storage = installLocalStorageShim();
    mock = installMockApi();
    dispatchSpy = vi.spyOn(window, "dispatchEvent");
  });

  afterEach(() => {
    mock.restore();
    dispatchSpy.mockRestore();
    storage.clear();
  });

  describe("Bearer header injection", () => {
    it("GET injects Authorization: Bearer <token> when pulse_token is set", async () => {
      localStorage.setItem("pulse_token", "abc");
      mock.mock("GET", "/api/v1/blueprints", { items: [] });

      await api.get("/api/v1/blueprints");

      const [call] = mock.calls();
      expect(call.method).toBe("GET");
      expect(call.url).toBe(`${BASE}/api/v1/blueprints`);
      expect(call.headers["Authorization"]).toBe("Bearer abc");
    });

    it("omits Authorization header when no pulse_token is in localStorage (does not send 'Bearer null')", async () => {
      mock.mock("GET", "/api/v1/blueprints", { items: [] });

      await api.get("/api/v1/blueprints");

      const [call] = mock.calls();
      // The header key must not be present; absence is the contract.
      // Both 'Authorization' and a lowercased variant should be absent because
      // api.ts only ever sets the canonical-cased key when token is present.
      expect(call.headers["Authorization"]).toBeUndefined();
      expect(call.headers["authorization"]).toBeUndefined();
    });

    it("preserves token bytes verbatim (no URL-encoding) for tokens with special characters", async () => {
      // JWTs commonly contain '.', '-', and '_'; assert we forward the exact bytes.
      const exotic = "head.payload-with_special.chars==";
      localStorage.setItem("pulse_token", exotic);
      mock.mock("GET", "/api/v1/ping", { ok: true });

      await api.get("/api/v1/ping");

      const [call] = mock.calls();
      expect(call.headers["Authorization"]).toBe(`Bearer ${exotic}`);
    });
  });

  describe("HTTP methods", () => {
    it("POST serializes a JSON body and sets Content-Type: application/json", async () => {
      mock.mock("POST", "/api/v1/pipelines", { id: "p1" });

      await api.post("/api/v1/pipelines", { foo: 1 });

      const [call] = mock.calls();
      expect(call.method).toBe("POST");
      expect(call.url).toBe(`${BASE}/api/v1/pipelines`);
      expect(call.headers["Content-Type"]).toBe("application/json");
      // mock-api JSON.parses string bodies, so we get back the structured value.
      expect(call.body).toEqual({ foo: 1 });
    });

    it("PUT serializes a JSON body and sets Content-Type", async () => {
      mock.mock("PUT", "/api/v1/pipelines/p1", { id: "p1", name: "updated" });

      await api.put("/api/v1/pipelines/p1", { name: "updated" });

      const [call] = mock.calls();
      expect(call.method).toBe("PUT");
      expect(call.headers["Content-Type"]).toBe("application/json");
      expect(call.body).toEqual({ name: "updated" });
    });

    it("PATCH serializes a JSON body and sets Content-Type", async () => {
      mock.mock("PATCH", "/api/v1/pipelines/p1", { id: "p1" });

      await api.patch("/api/v1/pipelines/p1", { status: "ARCHIVED" });

      const [call] = mock.calls();
      expect(call.method).toBe("PATCH");
      expect(call.headers["Content-Type"]).toBe("application/json");
      expect(call.body).toEqual({ status: "ARCHIVED" });
    });

    it("DELETE issues a DELETE without a body", async () => {
      mock.mock("DELETE", "/api/v1/pipelines/p1", { ok: true });

      await api.delete("/api/v1/pipelines/p1");

      const [call] = mock.calls();
      expect(call.method).toBe("DELETE");
      // No body was passed to .delete(), so the recorder sees undefined.
      expect(call.body).toBeUndefined();
    });

    it("returns parsed JSON on 200", async () => {
      mock.mock("GET", "/api/v1/blueprints", { items: [{ id: "bp-1" }] });

      const result = await api.get<{ items: { id: string }[] }>(
        "/api/v1/blueprints",
      );

      expect(result).toEqual({ items: [{ id: "bp-1" }] });
    });

    it("returns undefined on 204 No Content", async () => {
      // The Fetch spec forbids constructing a Response with status 204 and a
      // body, so we cannot route this through the shared mock-api stub (which
      // always passes a string body). Install a one-shot fetch mock instead.
      mock.restore();
      const oneShot = vi.fn(
        async () =>
          new Response(null, {
            status: 204,
            headers: { "Content-Length": "0" },
          }),
      );
      vi.stubGlobal("fetch", oneShot);
      try {
        const result = await api.delete<undefined>("/api/v1/pipelines/p1");
        expect(result).toBeUndefined();
        expect(oneShot).toHaveBeenCalledTimes(1);
      } finally {
        vi.unstubAllGlobals();
      }
    });
  });

  describe("PKT-FINAL-3 401/502 session-vs-upstream distinction", () => {
    it("401 with no code (Spring Security default) clears the token and dispatches pulse:logout", async () => {
      localStorage.setItem("pulse_token", "abc");
      localStorage.setItem("pulse.currentTenantId", "tenant-home-lending");
      localStorage.setItem("pulse_last_auth_check", "2026-05-25T10:00:00Z");
      mock.mock(
        "GET",
        "/api/v1/pipelines",
        { detail: "Token expired" },
        401,
      );

      await expect(api.get("/api/v1/pipelines")).rejects.toThrow(
        "Session expired",
      );

      // BUG-09b loop-recovery: ALL pulse_*/pulse.* keys are cleared, not just pulse_token.
      expect(localStorage.getItem("pulse_token")).toBeNull();
      expect(localStorage.getItem("pulse.currentTenantId")).toBeNull();
      expect(localStorage.getItem("pulse_last_auth_check")).toBeNull();

      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(1);
    });

    it("401 with explicit code === 'SESSION_EXPIRED' clears state and dispatches pulse:logout", async () => {
      localStorage.setItem("pulse_token", "abc");
      mock.mock(
        "GET",
        "/api/v1/pipelines",
        { code: "SESSION_EXPIRED", message: "JWT expired" },
        401,
      );

      await expect(api.get("/api/v1/pipelines")).rejects.toThrow("Session expired");
      expect(localStorage.getItem("pulse_token")).toBeNull();
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(1);
    });

    it("502 with code === 'UPSTREAM_GIT_AUTH_FAILED' propagates inline and does NOT dispatch pulse:logout", async () => {
      // PKT-FINAL-3 (BUG-09a): backend returns 502 (not 401) for upstream
      // Git auth failures. Frontend must render inline and keep the session.
      localStorage.setItem("pulse_token", "abc");
      mock.mock(
        "POST",
        "/api/v1/tenants/tenant-home-lending/onboard",
        {
          code: "UPSTREAM_GIT_AUTH_FAILED",
          type: "UPSTREAM_AUTH",
          message: "Bad PAT for origin",
        },
        502,
      );

      await expect(
        api.post("/api/v1/tenants/tenant-home-lending/onboard", { repoType: "REMOTE" }),
      ).rejects.toThrow("Bad PAT for origin");

      // Critical: PULSE session is preserved.
      expect(localStorage.getItem("pulse_token")).toBe("abc");
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(0);
    });

    it("403 with code === 'UPSTREAM_GIT_REPO_ACCESS_DENIED' propagates inline and does NOT dispatch pulse:logout", async () => {
      localStorage.setItem("pulse_token", "abc");
      mock.mock(
        "POST",
        "/api/v1/tenants/t/onboard",
        {
          code: "UPSTREAM_GIT_REPO_ACCESS_DENIED",
          type: "UPSTREAM_AUTH",
          message: "Repo locked",
        },
        403,
      );

      await expect(api.post("/api/v1/tenants/t/onboard", {})).rejects.toThrow("Repo locked");
      expect(localStorage.getItem("pulse_token")).toBe("abc");
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(0);
    });

    it("401 with an arbitrary other code propagates without logout (defense in depth)", async () => {
      localStorage.setItem("pulse_token", "abc");
      mock.mock(
        "GET",
        "/api/v1/foo",
        { code: "TENANT_NOT_ENTITLED", message: "Not entitled" },
        401,
      );

      await expect(api.get("/api/v1/foo")).rejects.toThrow("Not entitled");
      expect(localStorage.getItem("pulse_token")).toBe("abc");
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(0);
    });
  });

  describe("PKT-FINAL-3 BUG-09c: in-flight POST dedupe", () => {
    it("two simultaneous POSTs to the same path share a single backend call", async () => {
      // Use a custom one-shot fetch so we can observe call count and
      // delay the response to force overlap.
      mock.restore();
      let resolveFn: (v: Response) => void = () => {};
      const pending = new Promise<Response>((r) => (resolveFn = r));
      const fetchSpy = vi.fn(async () => pending);
      vi.stubGlobal("fetch", fetchSpy);

      const a = api.post("/api/v1/tenants/t/onboard", { x: 1 });
      const b = api.post("/api/v1/tenants/t/onboard", { x: 2 });

      // Resolve the underlying fetch once.
      resolveFn(
        new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const [resA, resB] = await Promise.all([a, b]);
      expect(resA).toEqual({ ok: true });
      expect(resB).toEqual({ ok: true });
      // Only one network call was made even though two POSTs were issued.
      expect(fetchSpy).toHaveBeenCalledTimes(1);
      vi.unstubAllGlobals();
    });

    it("a follow-up POST after the first resolves does NOT dedupe (retry semantics preserved)", async () => {
      mock.mock("POST", "/api/v1/tenants/t/onboard", { ok: true }, 200);

      await api.post("/api/v1/tenants/t/onboard", { x: 1 });
      await api.post("/api/v1/tenants/t/onboard", { x: 2 });

      const calls = mock
        .calls()
        .filter((c) => c.url.endsWith("/api/v1/tenants/t/onboard") && c.method === "POST");
      expect(calls).toHaveLength(2);
    });
  });

  describe("error envelope parsing", () => {
    it("rejects with `detail` from a PULSE error envelope on 4xx", async () => {
      mock.mock(
        "POST",
        "/api/v1/pipelines",
        { detail: "name must be unique" },
        400,
      );

      await expect(
        api.post("/api/v1/pipelines", { name: "dupe" }),
      ).rejects.toThrow("name must be unique");
    });

    it("falls back to a generic 'API error: <status>' message when no detail is present", async () => {
      // mock-api sends a JSON body, so we send an empty object — `error.detail`
      // is undefined and the client's fallback kicks in.
      mock.mock("GET", "/api/v1/pipelines", {}, 500);

      await expect(api.get("/api/v1/pipelines")).rejects.toThrow(
        "API error: 500",
      );
    });

    it("5xx without a parseable body still rejects (no silent success)", async () => {
      // The mock-api helper always sends valid JSON; here we register a string
      // body that is intentionally not the {detail} shape so the catch path
      // in api.ts is exercised.
      mock.mock("GET", "/api/v1/pipelines", "not-json-but-string", 502);

      // mock-api JSON-stringifies the string ("not-json-but-string" → '"not-json-but-string"')
      // which IS valid JSON; the parsed body has no `detail`, so we expect the
      // 'API error: 502' fallback.
      await expect(api.get("/api/v1/pipelines")).rejects.toThrow(
        "API error: 502",
      );
    });
  });

  describe("postForm multipart path", () => {
    /**
     * `postForm` deliberately does NOT set Content-Type — the fetch
     * implementation must set `multipart/form-data; boundary=...` itself
     * from the FormData body. The shared mock-api recorder JSON-parses
     * string bodies and so cannot capture a FormData body, so we install a
     * targeted fetch mock here.
     */
    let fetchMock: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      // Restore the shared mock-api stub before installing our own so the
      // two don't conflict.
      mock.restore();
      fetchMock = vi.fn(async () => {
        return new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      });
      vi.stubGlobal("fetch", fetchMock);
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it("sends the FormData body verbatim and does NOT explicitly set Content-Type to application/json", async () => {
      const form = new FormData();
      form.append("file", new Blob(["copybook bytes"]), "customer.cpy");
      form.append("kind", "cobol-copybook");

      await api.postForm("/api/v1/schema/upload", form);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe(`${BASE}/api/v1/schema/upload`);
      expect(init.method).toBe("POST");
      // Body must be the original FormData instance (no JSON.stringify path).
      expect(init.body).toBeInstanceOf(FormData);
      // Critical: postForm must not send application/json — letting the
      // platform add the multipart boundary header itself.
      const headers = init.headers as Record<string, string> | undefined;
      expect(headers?.["Content-Type"]).toBeUndefined();
      expect(headers?.["content-type"]).toBeUndefined();
    });

    it("postForm injects Bearer token when pulse_token is set", async () => {
      localStorage.setItem("pulse_token", "form-token");
      const form = new FormData();
      form.append("k", "v");

      await api.postForm("/api/v1/schema/upload", form);

      const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      const headers = init.headers as Record<string, string>;
      expect(headers["Authorization"]).toBe("Bearer form-token");
    });

    it("postForm path: a 401 with no code still clears state and dispatches pulse:logout", async () => {
      localStorage.setItem("pulse_token", "form-token");
      localStorage.setItem("pulse.currentTenantId", "tenant-x");
      // Override fetch for this test to return 401.
      fetchMock.mockImplementationOnce(
        async () =>
          new Response(JSON.stringify({ detail: "expired" }), {
            status: 401,
            headers: { "Content-Type": "application/json" },
          }),
      );

      const form = new FormData();
      form.append("k", "v");
      await expect(
        api.postForm("/api/v1/schema/upload", form),
      ).rejects.toThrow("Session expired");

      expect(localStorage.getItem("pulse_token")).toBeNull();
      expect(localStorage.getItem("pulse.currentTenantId")).toBeNull();
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(1);
    });

    it("postForm path: 502 UPSTREAM_GIT_AUTH_FAILED propagates inline (no logout)", async () => {
      localStorage.setItem("pulse_token", "form-token");
      fetchMock.mockImplementationOnce(
        async () =>
          new Response(
            JSON.stringify({
              code: "UPSTREAM_GIT_AUTH_FAILED",
              type: "UPSTREAM_AUTH",
              message: "Bad PAT for origin",
            }),
            { status: 502, headers: { "Content-Type": "application/json" } },
          ),
      );

      const form = new FormData();
      form.append("k", "v");
      await expect(api.postForm("/api/v1/schema/upload", form)).rejects.toThrow(
        "Bad PAT for origin",
      );

      expect(localStorage.getItem("pulse_token")).toBe("form-token");
      const logoutEvents = dispatchSpy.mock.calls.filter(
        (args) => (args[0] as Event).type === "pulse:logout",
      );
      expect(logoutEvents).toHaveLength(0);
    });
  });
});
