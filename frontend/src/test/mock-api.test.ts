import { afterEach, describe, expect, it } from "vitest";
import { installMockApi } from "@/test/mock-api";

describe("installMockApi", () => {
  let api: ReturnType<typeof installMockApi> | null = null;

  afterEach(() => {
    api?.restore();
    api = null;
  });

  it("returns the stubbed response for a registered (method, url) pair", async () => {
    api = installMockApi();
    api.mock("GET", "/api/v1/blueprints", { items: [{ id: "bp-1" }] });

    const res = await fetch("http://localhost:8080/api/v1/blueprints");
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json).toEqual({ items: [{ id: "bp-1" }] });
  });

  it("records call metadata so tests can assert on request shape", async () => {
    api = installMockApi();
    api.mock("POST", "/api/v1/commands", { ok: true });

    await fetch("http://localhost:8080/api/v1/commands", {
      method: "POST",
      headers: { Authorization: "Bearer t", "Content-Type": "application/json" },
      body: JSON.stringify({ kind: "CREATE_PIPELINE" }),
    });

    const calls = api.calls();
    expect(calls).toHaveLength(1);
    expect(calls[0].method).toBe("POST");
    expect(calls[0].url).toContain("/api/v1/commands");
    expect(calls[0].headers["Authorization"]).toBe("Bearer t");
    expect(calls[0].body).toEqual({ kind: "CREATE_PIPELINE" });
  });

  it("returns 599 when no stub matches, instead of letting the request escape", async () => {
    api = installMockApi();
    const res = await fetch("http://localhost:8080/api/v1/missing");
    expect(res.status).toBe(599);
    const json = await res.json();
    expect(json.detail).toContain("no stub");
  });

  it("supports regex URL matchers", async () => {
    api = installMockApi();
    api.mock("GET", /\/api\/v1\/pipelines\/[a-z0-9-]+$/i, { id: "p-1" });

    const res = await fetch("http://localhost:8080/api/v1/pipelines/abc-123");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ id: "p-1" });
  });
});
