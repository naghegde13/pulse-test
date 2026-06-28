const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/** Must match `TENANT_STORAGE_KEY` in `contexts/tenant-context.tsx`. */
const PULSE_CURRENT_TENANT_ID_KEY = "pulse.currentTenantId";

/**
 * PKT-FINAL-3 (BUG-09b): clearing the auth token alone left stale
 * `pulse.*` keys in localStorage that pinned the operator to /login in a
 * redirect loop. Exported so {@code auth-context} can purge the same set
 * on its own `logout()` path, and so tests can assert the contract.
 */
export const PULSE_PERSISTED_KEY_PREFIX = "pulse";

function clearPersistedPulseState() {
  if (typeof window === "undefined") return;
  try {
    const keys: string[] = [];
    for (let i = 0; i < window.localStorage.length; i++) {
      const k = window.localStorage.key(i);
      if (k == null) continue;
      // Match both the legacy `pulse_token` style (underscore-prefixed)
      // and the namespaced `pulse.currentTenantId` style. Stay strict to
      // the `pulse` prefix so unrelated app state survives.
      if (
        k.startsWith(`${PULSE_PERSISTED_KEY_PREFIX}_`) ||
        k.startsWith(`${PULSE_PERSISTED_KEY_PREFIX}.`)
      ) {
        keys.push(k);
      }
    }
    for (const k of keys) {
      window.localStorage.removeItem(k);
    }
  } catch {
    // Storage access can throw in private-browsing / sandboxed contexts.
    // The session-expiry signal is still dispatched below.
  }
}

class ApiClient {
  private baseUrl: string;

  /**
   * PKT-FINAL-3 (BUG-09c): dedupe in-flight POSTs keyed on `method:path`
   * so a re-render race after an auth cascade cannot fire the same
   * mutation twice. Idempotent reads (GET) and form posts are not
   * deduped because identical FormData calls should be honored.
   */
  private inFlightMutations = new Map<string, Promise<unknown>>();

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  private getToken(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem("pulse_token");
  }

  /**
   * Phase 3 actor resolution: backend reads `X-Pulse-Tenant-Id` when JWT
   * headers are not wired yet. User Git identity and other tenant-scoped APIs
   * require a non-blank tenant on the caller context.
   */
  private pulseActorHeaders(): Record<string, string> {
    if (typeof window === "undefined") return {};
    const tenantId = localStorage.getItem(PULSE_CURRENT_TENANT_ID_KEY);
    if (!tenantId) return {};
    return { "X-Pulse-Tenant-Id": tenantId };
  }

  /**
   * PKT-FINAL-3 (BUG-09a/b): the 401 handler used to treat every 401 as
   * session expiry, which incorrectly logged the operator out when an
   * upstream Git credential failed. The new contract:
   *
   *   - 502 + `code === "UPSTREAM_GIT_AUTH_FAILED"` (and any other
   *     `type === "UPSTREAM_AUTH"` envelope) propagates to the caller
   *     for inline rendering; never triggers logout.
   *   - 401 + `code === "SESSION_EXPIRED"` (or 401 with no `code` field —
   *     covers Spring Security defaults, real JWT expiry) clears
   *     persisted state and dispatches `pulse:logout`.
   *   - 401 with any other `code` propagates as a regular error; the
   *     session stays alive.
   */
  private async handleAuthFailure(res: Response): Promise<never> {
    const body = await res
      .clone()
      .json()
      .catch(() => ({} as Record<string, unknown>));
    const code = (body as { code?: string }).code;
    const message =
      (body as { message?: string; detail?: string; error?: string }).message ||
      (body as { detail?: string }).detail ||
      (body as { error?: string }).error;

    // Backend 502 (or any 4xx/5xx tagged UPSTREAM_AUTH) — propagate, do NOT logout.
    if (
      code === "UPSTREAM_GIT_AUTH_FAILED" ||
      code === "UPSTREAM_GIT_REPO_ACCESS_DENIED" ||
      (body as { type?: string }).type === "UPSTREAM_AUTH"
    ) {
      throw new Error(message || "Git credential verification failed");
    }

    if (res.status === 401) {
      // Real session expiry: explicit SESSION_EXPIRED, or absence-of-code
      // (Spring Security default, actuator probes, real JWT expiry).
      if (!code || code === "SESSION_EXPIRED") {
        clearPersistedPulseState();
        window.dispatchEvent(new Event("pulse:logout"));
        throw new Error("Session expired");
      }
      // Other 401s: surface inline without nuking the session.
      throw new Error(message || "Unauthorized");
    }

    // Any non-401 routed here (e.g. an UPSTREAM_AUTH 502/403) without a
    // structured envelope falls through to the caller as a plain error.
    throw new Error(message || `API error: ${res.status}`);
  }

  private async executeRequest<T>(path: string, options?: RequestInit): Promise<T> {
    const token = this.getToken();
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...this.pulseActorHeaders(),
      ...(options?.headers as Record<string, string>),
    };
    if (token) {
      headers["Authorization"] = `Bearer ${token}`;
    }

    const res = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      headers,
    });

    if (res.status === 401 || res.status === 502 || res.status === 403) {
      // Auth-related responses (session expiry, upstream Git auth, repo
      // access denied) go through the shared envelope-aware handler. We
      // include 403 here to catch the UPSTREAM_GIT_REPO_ACCESS_DENIED
      // body shape; if the envelope is absent the handler falls back to
      // the generic API-error path below.
      try {
        await this.handleAuthFailure(res);
      } catch (err) {
        // handleAuthFailure always throws; if it propagated as a generic
        // error for a 403/502 without the structured envelope, fall back
        // to the parsing below so legacy callers still see a useful
        // error message.
        if (res.status === 401 || (err as Error).message !== `API error: ${res.status}`) {
          throw err;
        }
      }
    }

    if (!res.ok) {
      const error = await res.json().catch(() => ({ detail: res.statusText }));
      throw new Error(
        (error as { message?: string }).message ||
          (error as { detail?: string }).detail ||
          `API error: ${res.status}`,
      );
    }

    if (res.status === 204 || res.headers.get("content-length") === "0") {
      return undefined as T;
    }

    return res.json();
  }

  private async request<T>(path: string, options?: RequestInit): Promise<T> {
    const method = (options?.method || "GET").toUpperCase();
    // PKT-FINAL-3 (BUG-09c): collapse re-entrant POSTs (e.g. the wizard
    // remount race after an auth cascade) into a single in-flight call.
    // GETs and other read-side methods skip dedupe — they're cheap and
    // sometimes legitimately repeat.
    if (method === "POST") {
      const key = `POST:${path}`;
      const existing = this.inFlightMutations.get(key) as Promise<T> | undefined;
      if (existing) {
        return existing;
      }
      const promise = this.executeRequest<T>(path, options).finally(() => {
        this.inFlightMutations.delete(key);
      });
      this.inFlightMutations.set(key, promise);
      return promise;
    }
    return this.executeRequest<T>(path, options);
  }

  private async requestForm<T>(path: string, method: string, body: FormData): Promise<T> {
    const token = this.getToken();
    const headers: Record<string, string> = {
      ...this.pulseActorHeaders(),
    };
    if (token) {
      headers["Authorization"] = `Bearer ${token}`;
    }

    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body,
    });

    if (res.status === 401 || res.status === 502 || res.status === 403) {
      try {
        await this.handleAuthFailure(res);
      } catch (err) {
        if (res.status === 401 || (err as Error).message !== `API error: ${res.status}`) {
          throw err;
        }
      }
    }

    if (!res.ok) {
      const error = await res.json().catch(() => ({ detail: res.statusText }));
      throw new Error(
        (error as { message?: string }).message ||
          (error as { detail?: string }).detail ||
          `API error: ${res.status}`,
      );
    }

    if (res.status === 204 || res.headers.get("content-length") === "0") {
      return undefined as T;
    }

    return res.json();
  }

  get<T>(path: string) {
    return this.request<T>(path);
  }

  post<T>(path: string, body: unknown) {
    return this.request<T>(path, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  put<T>(path: string, body: unknown) {
    return this.request<T>(path, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  patch<T>(path: string, body: unknown) {
    return this.request<T>(path, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  }

  postForm<T>(path: string, body: FormData) {
    return this.requestForm<T>(path, "POST", body);
  }

  delete<T>(path: string) {
    return this.request<T>(path, { method: "DELETE" });
  }
}

export const api = new ApiClient(API_BASE);

/**
 * PKT-FINAL-3 (BUG-09b): exported for the auth context's own logout
 * handler so a manual `logout()` clears the same persisted state as a
 * 401-triggered session expiry, ensuring the operator is free to
 * navigate after either path.
 */
export { clearPersistedPulseState };
