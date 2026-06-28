/**
 * SU-6 / BUG-60: Component-render assertion for the new ScaffoldTree.
 *
 * One sub-assertion (BUG-60): the typed ScaffoldPreview is rendered with
 * top-level paths, every domain's name + slug + status badge, and the
 * per-domain paths. This is the runnable assertion required by the
 * operator exit criteria.
 */

import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ScaffoldTree } from "./scaffold-tree";
import type { ScaffoldPreview } from "@/types";

const PREVIEW: ScaffoldPreview = {
  tenantId: "tenant-acme",
  repoType: "REMOTE",
  branchName: "main",
  gitIdentity: {
    required: true,
    ready: true,
    code: "ready",
    message: "Git identity ready.",
  },
  topLevelMissing: false,
  topLevelPaths: ["README.md", "dbt_project/", ".github/workflows/ci.yml"],
  domains: [
    {
      domainId: "dom-1",
      domainName: "Originations",
      domainSlug: "originations",
      status: "SCAFFOLDED",
      paths: [
        "domains/originations/pipelines/",
        "domains/originations/dbt_models/",
      ],
    },
    {
      domainId: "dom-2",
      domainName: "Servicing",
      domainSlug: "servicing",
      status: "MISSING",
      paths: ["domains/servicing/pipelines/"],
    },
  ],
};

describe("ScaffoldTree (BUG-60)", () => {
  it("renders the typed ScaffoldPreview with top-level paths and per-domain sections", () => {
    render(<ScaffoldTree preview={PREVIEW} />);

    // Top-level paths
    const topLevel = screen.getByTestId("scaffold-tree-top-level-list");
    expect(within(topLevel).getByText(/README\.md/)).toBeInTheDocument();
    expect(within(topLevel).getByText(/dbt_project\//)).toBeInTheDocument();
    expect(
      within(topLevel).getByText(/\.github\/workflows\/ci\.yml/),
    ).toBeInTheDocument();

    // Per-domain block — Originations (scaffolded)
    const originations = screen.getByTestId("scaffold-tree-domain-originations");
    expect(within(originations).getByText("Originations")).toBeInTheDocument();
    expect(within(originations).getByText("originations")).toBeInTheDocument();
    expect(within(originations).getByText("scaffolded")).toBeInTheDocument();
    expect(
      within(originations).getByText(/domains\/originations\/pipelines\//),
    ).toBeInTheDocument();

    // Per-domain block — Servicing (missing)
    const servicing = screen.getByTestId("scaffold-tree-domain-servicing");
    expect(within(servicing).getByText("Servicing")).toBeInTheDocument();
    expect(within(servicing).getByText("missing")).toBeInTheDocument();
  });

  it("renders an identity-required alert when git identity is not ready", () => {
    const preview: ScaffoldPreview = {
      ...PREVIEW,
      gitIdentity: {
        required: true,
        ready: false,
        code: "git_identity_required",
        message: "Register a GitHub PAT before initializing.",
      },
    };
    render(<ScaffoldTree preview={preview} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent(/Register a GitHub PAT/i);
  });

  it("renders an empty-domains hint when no domains are registered", () => {
    const preview: ScaffoldPreview = { ...PREVIEW, domains: [] };
    render(<ScaffoldTree preview={preview} />);
    expect(
      screen.getByText(/No domains registered/i),
    ).toBeInTheDocument();
  });
});
