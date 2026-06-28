import { defineConfig } from "vitest/config";
import path from "node:path";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    // Discover `Foo.test.tsx` / `Foo.spec.tsx` *and* explicitly-named
    // `FooTest.tsx` files (SU-6 / BUG-59 packet names its RTL specs
    // SettingsOnboardingWizardTest.tsx per operator convention).
    include: [
      "src/**/*.{test,spec}.{ts,tsx}",
      "src/**/*Test.{ts,tsx}",
    ],
    exclude: ["node_modules", "e2e", ".next", "dist"],
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
