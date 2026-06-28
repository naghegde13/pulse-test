/**
 * W-13 type-switch wiring tests (SPEC-construct-library.md §G-9 / W-13).
 *
 * The construct library's whole reason to exist: replace the generic
 * `configure-transform-dialog` fallbacks (raw-JSON `<Textarea>` for
 * object/object[]; generic `<Select>` for enum; newline `<Textarea>` for
 * string[]) with the purpose-built controls, routed by the #5 UI-construct
 * HINT token — NOT only by `definition.type` (two `object`-typed params route to
 * different controls).
 *
 * Tests render `MetadataDrivenConfig` (the param renderer holding the
 * type-switch) directly, driving one param at a time with a hint, and assert the
 * purpose-built control renders instead of the raw fallback.
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MetadataDrivenConfig } from "./configure-transform-dialog";
import type { Blueprint, BlueprintParamDefinition, SchemaColumn } from "@/types";

vi.mock("@/lib/api", () => ({ api: { post: vi.fn() } }));

const SCHEMA: SchemaColumn[] = [
  { name: "loan_id", type: "long" },
  { name: "status", type: "string" },
  { name: "as_of_date", type: "date" },
];

function makeBlueprint(
  param: BlueprintParamDefinition | BlueprintParamDefinition[],
  outputPorts: { name: string }[] = [{ name: "output" }]
): Blueprint {
  return {
    id: "bp1",
    blueprintKey: "TestBlueprint",
    name: "Test Blueprint",
    description: "",
    category: "TRANSFORM",
    version: "1.0.0",
    paramsSchema: Array.isArray(param) ? param : [param],
    inputPorts: [{ name: "input" }],
    outputPorts,
    runtimeRequirements: {},
    deferred: false,
    pipelineConfig: false,
    createdAt: "",
    updatedAt: "",
  };
}

function renderParam(
  param: BlueprintParamDefinition,
  opts: {
    value?: string;
    outputPorts?: { name: string }[];
    onValueChange?: ReturnType<typeof vi.fn>;
  } = {}
) {
  const onValueChange = opts.onValueChange ?? vi.fn();
  render(
    <MetadataDrivenConfig
      blueprint={makeBlueprint(param, opts.outputPorts)}
      currentParams={{}}
      inheritedFields={[]}
      connectorConfigTemplate={null}
      values={{ [param.name]: opts.value ?? "" }}
      upstreamSchema={SCHEMA}
      onValueChange={onValueChange}
    />
  );
  return { onValueChange };
}

function renderSeededBlueprint({
  blueprintKey,
  paramsSchema,
  values,
  outputPorts,
}: {
  blueprintKey: string;
  paramsSchema: BlueprintParamDefinition[];
  values: Record<string, string>;
  outputPorts?: { name: string }[];
}) {
  return render(
    <MetadataDrivenConfig
      blueprint={{
        ...makeBlueprint(paramsSchema, outputPorts),
        blueprintKey,
        name: blueprintKey,
      }}
      currentParams={{}}
      inheritedFields={[]}
      connectorConfigTemplate={null}
      values={values}
      upstreamSchema={SCHEMA}
      onValueChange={vi.fn()}
    />
  );
}

describe("configure-transform-dialog type-switch — hint routing (W-13)", () => {
  it("`column-picker` hint renders the ColumnPicker (not the generic Input)", () => {
    renderParam({ name: "incremental_field", type: "string", ui_construct: "column-picker" });
    // ColumnPicker (closed combobox) renders a button with the placeholder.
    expect(screen.getByText(/select incremental field/i)).toBeInTheDocument();
  });

  it("`column-picker (multi)` hint renders the badge-toggle MultiColumnPicker", () => {
    renderParam({ name: "primary_key", type: "string[]", ui_construct: "column-picker (multi)" });
    // The multi-picker renders the schema columns as toggle badges.
    expect(screen.getAllByText("loan_id").length).toBeGreaterThan(0);
    expect(screen.getByText("status")).toBeInTheDocument();
    // The string[] "Enter one item per line" caption must NOT show (construct took over).
    expect(screen.queryByText(/one item per line/i)).not.toBeInTheDocument();
  });

  it("`rename-mapper` hint renders the RenameMapper (not the raw-JSON Textarea)", () => {
    renderParam({
      name: "rename_map",
      type: "object",
      ui_construct: "rename-mapper",
      value: JSON.stringify({ old_a: "new_a" }),
    });
    // The mapper exposes the "+ Add mapping" affordance.
    expect(screen.getByText(/add mapping/i)).toBeInTheDocument();
    // The JSON-syntax help caption is suppressed.
    expect(screen.queryByText(/valid JSON/i)).not.toBeInTheDocument();
  });

  it("TWO object-typed params route to DIFFERENT controls by hint", () => {
    // rename-mapper → text right cell; type-cast-mapper → select right cell.
    const { unmount } = render(
      <MetadataDrivenConfig
        blueprint={makeBlueprint({ name: "type_coercions", type: "object", ui_construct: "type-cast-mapper" })}
        currentParams={{}}
        inheritedFields={[]}
        connectorConfigTemplate={null}
        values={{ type_coercions: JSON.stringify({ amount: "double" }) }}
        upstreamSchema={SCHEMA}
        onValueChange={vi.fn()}
      />
    );
    // type-cast-mapper renders a type Select (combobox) for the right cell.
    expect(screen.getAllByRole("combobox").length).toBeGreaterThan(0);
    unmount();
  });

  it("`condition-builder` hint renders the visual SqlFilterBuilder", () => {
    renderParam({
      name: "conditions",
      type: "object[]",
      ui_construct: "condition-builder",
      value: JSON.stringify([{ column: "status", operator: "eq", value: "ACTIVE", logic: "AND" }]),
    });
    expect(screen.getByText("+ Add Condition")).toBeInTheDocument();
  });

  it("`dq-outcome-control` hint renders the port-aware control; quarantine hidden without port", () => {
    renderParam(
      {
        name: "drift_policy",
        type: "enum",
        ui_construct: "dq-outcome-control",
        options: ["quarantine", "block", "warn"],
      },
      { outputPorts: [{ name: "drift_report" }] }
    );
    // block + warn offered; quarantine hidden (no quarantine_output port).
    expect(screen.getByText("Block")).toBeInTheDocument();
    expect(screen.getByText("Warn")).toBeInTheDocument();
    expect(screen.queryByText("Quarantine")).not.toBeInTheDocument();
  });

  it("`dq-outcome-control` offers quarantine WHEN a quarantine_output port exists", () => {
    renderParam(
      {
        name: "on_failure",
        type: "enum",
        ui_construct: "dq-outcome-control",
        options: ["quarantine", "block", "warn"],
      },
      { outputPorts: [{ name: "validated_output" }, { name: "quarantine_output" }] }
    );
    expect(screen.getByText("Quarantine")).toBeInTheDocument();
  });

  it("`sql-chain-editor` hint renders the rich chain editor", () => {
    renderParam({ name: "steps", type: "object[]", ui_construct: "sql-chain-editor" });
    expect(screen.getByText("+ Add Step")).toBeInTheDocument();
  });

  it("`simple-sql-builder` hint renders the validate-only SQL editor", () => {
    renderParam({ name: "source_query", type: "string", ui_construct: "simple-sql-builder" });
    // The Validate button is the source-DB-validate affordance unique to S1b.
    expect(screen.getByRole("button", { name: /validate/i })).toBeInTheDocument();
  });

  it("`cron-builder` hint renders the standalone cron construct", () => {
    const { container } = render(
      <MetadataDrivenConfig
        blueprint={makeBlueprint({
          name: "cron_expression",
          type: "string",
          ui_construct: "cron-builder",
        })}
        currentParams={{}}
        inheritedFields={[]}
        connectorConfigTemplate={null}
        values={{ cron_expression: "@daily" }}
        upstreamSchema={SCHEMA}
        onValueChange={vi.fn()}
      />
    );
    // The cron-builder root + its human summary are present (the summary node is
    // unambiguous; the Radix Select trigger label isn't rendered under jsdom).
    expect(container.querySelector("[data-cron-builder]")).toBeTruthy();
    expect(container.querySelector("[data-cron-summary]")).toHaveTextContent(
      /Daily at midnight/i
    );
  });

  it("a string[] param with NO hint still renders the newline Textarea fallback", () => {
    renderParam({ name: "tags", type: "string[]" });
    expect(screen.getByText(/one item per line/i)).toBeInTheDocument();
  });

  it("an object param with NO hint still renders the raw-JSON Textarea fallback", () => {
    renderParam({ name: "raw_blob", type: "object" });
    expect(screen.getByText(/valid JSON/i)).toBeInTheDocument();
  });

  it("renders a V153-like BulkBackfill payload with construct controls and read-only derived params", () => {
    const { container } = renderSeededBlueprint({
      blueprintKey: "BulkBackfill",
      paramsSchema: [
        {
          name: "source_query",
          type: "string",
          required: true,
          tier: "user",
          accepts_mnemonic: true,
          ui_construct: "simple-sql-builder",
        },
        {
          name: "date_range_start",
          type: "string",
          required: true,
          tier: "user",
          accepts_mnemonic: true,
          ui_construct: "date-mnemonic-picker",
        },
        {
          name: "date_range_end",
          type: "string",
          required: true,
          tier: "user",
          accepts_mnemonic: true,
          ui_construct: "date-mnemonic-picker",
        },
        {
          name: "chunk_size",
          type: "integer",
          default: 100000,
          tier: "derived",
          derivedFrom: "platform_default",
        },
        {
          name: "holiday_calendar_id",
          type: "enum",
          default: "US-FED",
          tier: "derived",
          derivedFrom: "domain.calendar",
        },
        {
          name: "storage_backend",
          type: "enum",
          required: true,
          tier: "derived",
          derivedFrom: "pipeline.storage",
        },
      ],
      values: {
        source_query: "select * from loan_master where as_of_date = [[ PBD ]]",
        date_range_start: "BOM",
        date_range_end: "PBD",
        chunk_size: "100000",
        holiday_calendar_id: "US-FED",
        storage_backend: "",
      },
    });

    expect(screen.getByRole("button", { name: /validate/i })).toBeInTheDocument();
    expect(container.querySelector('[data-derived-param="chunk_size"]')).toHaveTextContent(
      /Derived from Platform default/i
    );
    expect(container.querySelector('[data-derived-param="holiday_calendar_id"]')).toHaveTextContent(
      /Derived from Domain calendar/i
    );
    expect(container.querySelector('[data-derived-param="storage_backend"]')).toHaveTextContent(
      /Resolved by PULSE/i
    );
    expect(screen.getAllByText("Read-only").length).toBeGreaterThanOrEqual(3);
  });

  it("renders a seeded-like BronzeToSilverCleaning payload with mapped controls and derived storage inspectors", () => {
    const { container } = renderSeededBlueprint({
      blueprintKey: "BronzeToSilverCleaning",
      paramsSchema: [
        {
          name: "trim_columns",
          type: "string[]",
          tier: "user",
          ui_construct: "column-picker (multi)",
        },
        {
          name: "rename_map",
          type: "object",
          tier: "user",
          ui_construct: "rename-mapper",
        },
        {
          name: "type_coercions",
          type: "object",
          tier: "user",
          ui_construct: "type-cast-mapper",
        },
        {
          name: "partition_by",
          type: "string[]",
          tier: "derived",
          derivedFrom: "pipeline.storage",
        },
        {
          name: "lake_layer",
          type: "enum",
          default: "silver",
          tier: "derived",
          derivedFrom: "pipeline.storage",
        },
      ],
      values: {
        trim_columns: "status",
        rename_map: JSON.stringify({ loan_id: "loan_key" }),
        type_coercions: JSON.stringify({ loan_id: "string" }),
        partition_by: "ds",
        lake_layer: "silver",
      },
    });

    expect(screen.getAllByText("loan_id").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/add mapping/i).length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText(/one item per line/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/valid JSON/i)).not.toBeInTheDocument();
    expect(container.querySelector('[data-derived-param="partition_by"]')).toHaveTextContent(
      /Derived from Pipeline storage/i
    );
    expect(container.querySelector('[data-derived-param="lake_layer"]')).toHaveTextContent(
      /Read-only/i
    );
  });
});
