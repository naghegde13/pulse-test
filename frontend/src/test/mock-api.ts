import { vi } from "vitest";

/**
 * Minimal fetch stubbing helper for component/unit tests.
 *
 * We intentionally chose `vi.stubGlobal('fetch', ...)` over MSW for the
 * foundation runner: the PULSE frontend talks to a single Bearer-authenticated
 * REST surface via `src/lib/api.ts`, and most component tests only need to
 * assert request shape + response shape. A full network mock layer (MSW) adds
 * a service-worker / Node interceptor dependency we don't currently need.
 *
 * Usage:
 *   import { installMockApi } from "@/test/mock-api";
 *
 *   const api = installMockApi();
 *   api.mock("GET", "/api/v1/blueprints", { items: [] });
 *   // ...render component...
 *   expect(api.calls()).toHaveLength(1);
 *
 * Each test gets its own `installMockApi()` instance; `cleanup()` (called
 * automatically in `setup.ts`) plus an explicit `api.restore()` in
 * `afterEach` (or `vi.unstubAllGlobals()`) keeps tests isolated.
 */

export type MockMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface RecordedCall {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

interface MockEntry {
  method: MockMethod;
  matcher: string | RegExp;
  status: number;
  body: unknown;
}

export interface MockApi {
  /** Register a canned response for a (method, url) pair. */
  mock: (
    method: MockMethod,
    matcher: string | RegExp,
    body: unknown,
    status?: number,
  ) => void;
  /** All fetch calls observed since install, in order. */
  calls: () => RecordedCall[];
  /** Remove the stub and clear recorded calls. */
  restore: () => void;
}

function headersToRecord(init?: HeadersInit): Record<string, string> {
  if (!init) return {};
  if (init instanceof Headers) {
    const out: Record<string, string> = {};
    init.forEach((value, key) => {
      out[key] = value;
    });
    return out;
  }
  if (Array.isArray(init)) {
    return Object.fromEntries(init);
  }
  return { ...(init as Record<string, string>) };
}

function urlMatches(matcher: string | RegExp, url: string): boolean {
  if (typeof matcher === "string") {
    // Treat string matcher as suffix/substring match so callers can register
    // either "/api/v1/blueprints" or a full URL.
    return url === matcher || url.endsWith(matcher) || url.includes(matcher);
  }
  return matcher.test(url);
}

export function installMockApi(): MockApi {
  const entries: MockEntry[] = [];
  const recorded: RecordedCall[] = [];

  const fetchImpl: typeof fetch = async (input, init) => {
    const url =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    const method = (init?.method ?? "GET").toUpperCase();
    const headers = headersToRecord(init?.headers);
    let parsedBody: unknown = undefined;
    if (init?.body && typeof init.body === "string") {
      try {
        parsedBody = JSON.parse(init.body);
      } catch {
        parsedBody = init.body;
      }
    }
    recorded.push({ method, url, headers, body: parsedBody });

    const entry = entries.find(
      (e) => e.method === method && urlMatches(e.matcher, url),
    );
    if (!entry) {
      return new Response(
        JSON.stringify({ detail: `mock-api: no stub for ${method} ${url}` }),
        {
          status: 599,
          headers: { "Content-Type": "application/json" },
        },
      );
    }
    const bodyText =
      typeof entry.body === "string" ? entry.body : JSON.stringify(entry.body);
    return new Response(bodyText, {
      status: entry.status,
      headers: { "Content-Type": "application/json" },
    });
  };

  vi.stubGlobal("fetch", vi.fn(fetchImpl));

  return {
    mock(method, matcher, body, status = 200) {
      entries.push({ method, matcher, body, status });
    },
    calls() {
      return [...recorded];
    },
    restore() {
      vi.unstubAllGlobals();
      entries.length = 0;
      recorded.length = 0;
    },
  };
}
