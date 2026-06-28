import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach } from "vitest";
import { cleanup } from "@testing-library/react";

// React 19 act() configuration. React Testing Library reads this global
// to drive its concurrent-mode-aware act wrapper. Without it, tests that
// trigger state updates can produce "act(...)" warnings even when the
// updates are awaited correctly.
// See: https://react.dev/reference/react/act
declare global {
  // eslint-disable-next-line no-var
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

// Node 25 ships an experimental localStorage global (behind --experimental-webstorage)
// whose stub object lacks setItem/getItem/clear and SHADOWS jsdom's working
// window.localStorage when running under Vitest. Install a Map-backed Storage shim
// once per worker so every test sees a functioning localStorage even on Node 25.
// Tests that need an isolated localStorage between runs should clear it themselves
// or rely on the beforeEach reset below.
function installLocalStorageShim(): void {
  if (typeof window === "undefined") {
    return;
  }
  const existing = window.localStorage as Storage | undefined;
  // If the runtime already provides a functioning Storage, leave it alone.
  if (existing && typeof existing.setItem === "function" && typeof existing.getItem === "function") {
    return;
  }
  const store = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string): string | null {
      return store.has(key) ? (store.get(key) as string) : null;
    },
    key(index: number): string | null {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string): void {
      store.delete(key);
    },
    setItem(key: string, value: string): void {
      store.set(key, String(value));
    },
  };
  Object.defineProperty(window, "localStorage", {
    value: shim,
    writable: true,
    configurable: true,
  });
}

installLocalStorageShim();

beforeEach(() => {
  // Re-install in case a prior test replaced the global (e.g. via Object.defineProperty)
  installLocalStorageShim();
  try {
    window.localStorage.clear();
  } catch {
    // ignore: some environments forbid clear on certain stubs
  }
});

afterEach(() => {
  cleanup();
});
