"use client";

/**
 * SU-6 / BUG-60: Scaffold preview tree.
 *
 * Renders a typed {@link ScaffoldPreview} as an indented tree so the
 * operator can see exactly what PULSE will (or did) write into the
 * tenant repository — top-level scaffolding plus a per-domain section
 * with each path and a per-domain status badge.
 *
 * This component is intentionally presentation-only:
 *   - It does NOT fetch its own data; callers supply the preview.
 *   - It does NOT mutate; "Initialize" lives elsewhere.
 *
 * Used in two places per BUG-60 (wiring owned by the Integrator):
 *   1. OnboardingWizard Step 3 — show what will be created.
 *   2. The post-scaffold tenant-repository card — show what is in place.
 */

import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { ScaffoldPreview, ScaffoldPreviewDomain } from "@/types";

const DOMAIN_STATUS_BADGE: Record<
  ScaffoldPreviewDomain["status"],
  { label: string; className: string }
> = {
  SCAFFOLDED: {
    label: "scaffolded",
    className:
      "bg-green-500/15 text-green-700 dark:text-green-300 border-green-500/30",
  },
  MISSING: {
    label: "missing",
    className:
      "bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30",
  },
  ERROR: {
    label: "error",
    className:
      "bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/30",
  },
};

export interface ScaffoldTreeProps {
  preview: ScaffoldPreview;
  /**
   * When true (default), wraps the tree in a Card with header/title. When
   * false (e.g. embedded inside another card), renders only the inner
   * tree so callers can supply their own framing.
   */
  framed?: boolean;
  /** Optional title override when framed. */
  title?: string;
  /** Optional description override when framed. */
  description?: string;
}

export function ScaffoldTree({
  preview,
  framed = true,
  title = "Scaffold preview",
  description,
}: ScaffoldTreeProps) {
  const body = <ScaffoldTreeBody preview={preview} />;
  if (!framed) {
    return body;
  }
  const computedDescription =
    description ??
    `Repository ${preview.repoType.toLowerCase()} · branch ` +
      `${preview.branchName} · ${preview.domains.length} domain` +
      `${preview.domains.length === 1 ? "" : "s"}`;
  return (
    <Card data-testid="scaffold-tree-card">
      <CardHeader>
        <CardTitle className="text-sm">{title}</CardTitle>
        <CardDescription>{computedDescription}</CardDescription>
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  );
}

function ScaffoldTreeBody({ preview }: { preview: ScaffoldPreview }) {
  const identityReady = preview.gitIdentity.ready;
  return (
    <div className="space-y-3 text-xs" data-testid="scaffold-tree">
      {!identityReady && preview.gitIdentity.required && (
        <p
          role="alert"
          className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-amber-800 dark:text-amber-200"
        >
          {preview.gitIdentity.message ||
            "A valid GitHub PAT identity is required before this scaffold can be initialized."}
        </p>
      )}

      <section data-testid="scaffold-tree-top-level">
        <header className="flex items-center gap-2">
          <span className="font-medium">Top-level paths</span>
          <Badge
            variant="outline"
            className={`text-[10px] ${
              preview.topLevelMissing
                ? DOMAIN_STATUS_BADGE.MISSING.className
                : DOMAIN_STATUS_BADGE.SCAFFOLDED.className
            }`}
          >
            {preview.topLevelMissing ? "to be created" : "in place"}
          </Badge>
        </header>
        {preview.topLevelPaths.length === 0 ? (
          <p className="mt-1 text-muted-foreground">No top-level paths declared.</p>
        ) : (
          <ul
            className="mt-1 space-y-0.5 font-mono text-[11px]"
            data-testid="scaffold-tree-top-level-list"
          >
            {preview.topLevelPaths.map((p) => (
              <li key={p} className="break-all text-muted-foreground">
                <span className="mr-1 text-foreground/40">├─</span>
                {p}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section data-testid="scaffold-tree-domains">
        <header className="font-medium">
          Domains{" "}
          <span className="text-muted-foreground font-normal">
            ({preview.domains.length})
          </span>
        </header>
        {preview.domains.length === 0 ? (
          <p className="mt-1 text-muted-foreground">
            No domains registered. Add domains and re-preview to scaffold them.
          </p>
        ) : (
          <ul className="mt-1 space-y-2" data-testid="scaffold-tree-domains-list">
            {preview.domains.map((d) => {
              const badge = DOMAIN_STATUS_BADGE[d.status];
              return (
                <li
                  key={d.domainId}
                  className="rounded-md border bg-muted/20 p-2"
                  data-testid={`scaffold-tree-domain-${d.domainSlug}`}
                >
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{d.domainName}</span>
                    <span className="text-muted-foreground font-mono text-[10px]">
                      {d.domainSlug}
                    </span>
                    <Badge
                      variant="outline"
                      className={`ml-auto text-[10px] ${badge.className}`}
                    >
                      {badge.label}
                    </Badge>
                  </div>
                  {d.paths.length > 0 && (
                    <ul className="mt-1 space-y-0.5 pl-3 font-mono text-[11px] text-muted-foreground">
                      {d.paths.map((p) => (
                        <li key={p} className="break-all">
                          <span className="mr-1 text-foreground/40">└─</span>
                          {p}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
