import { describe, it, expect } from "vitest";
import type { ConnectorInstance, CredentialStatus, DeployEnvironment } from "@/types";
import {
  resolveSinkWriterKind,
  resolveSinkBlueprintKey,
  resolveWriteDispositions,
  resolveSinkTargetField,
  dispositionParamName,
  buildDefaultSinkConfig,
  buildCredentialWarning,
} from "./sink-capability";

function makeConnector(
  overrides: Partial<ConnectorInstance> & Pick<ConnectorInstance, "id" | "sorId" | "connectorDefinitionId" | "name" | "enabled">
): ConnectorInstance {
  return {
    configTemplate: {},
    createdAt: "2026-06-17T00:00:00Z",
    updatedAt: "2026-06-17T00:00:00Z",
    ...overrides,
  };
}

describe("resolveSinkWriterKind", () => {
  it("uses declared sinkWriterKind when present", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
    });
    expect(resolveSinkWriterKind(c)).toBe("DOCUMENT");
  });

  it("heuristic: mongo maps to DOCUMENT (not LAKE)", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      connectorTypeName: "MongoDB",
    });
    expect(resolveSinkWriterKind(c)).toBe("DOCUMENT");
  });

  it("heuristic: snowflake maps to WAREHOUSE", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Snowflake", enabled: true,
      connectorTypeName: "Snowflake",
    });
    expect(resolveSinkWriterKind(c)).toBe("WAREHOUSE");
  });

  it("heuristic: kafka maps to STREAM", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Kafka", enabled: true,
      connectorTypeName: "Kafka",
    });
    expect(resolveSinkWriterKind(c)).toBe("STREAM");
  });

  it("heuristic: unknown defaults to RELATIONAL (never LAKE)", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "ClickHouse", enabled: true,
    });
    expect(resolveSinkWriterKind(c)).toBe("RELATIONAL");
  });
});

describe("resolveSinkBlueprintKey", () => {
  it.each([
    ["LAKE", "LakeWriter"],
    ["WAREHOUSE", "WarehouseWriter"],
    ["RELATIONAL", "DatabaseWriter"],
    ["DOCUMENT", "DatabaseWriter"],
    ["STREAM", "StreamWriter"],
  ] as const)("kind %s maps to %s", (kind, expected) => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Test", enabled: true,
      sinkWriterKind: kind,
    });
    expect(resolveSinkBlueprintKey(c)).toBe(expected);
  });
});

describe("resolveWriteDispositions", () => {
  it("uses declared writeDispositions when present and non-empty", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
      writeDispositions: [
        { value: "append", label: "Insert documents" },
        { value: "merge_on_pk", label: "Upsert by key" },
      ],
    });
    const result = resolveWriteDispositions(c);
    expect(result).toHaveLength(2);
    expect(result[0].value).toBe("append");
    expect(result[0].label).toBe("Insert documents");
  });

  it("falls back to defaults for the writer kind", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
    });
    const result = resolveWriteDispositions(c);
    expect(result.length).toBeGreaterThan(0);
    expect(result[0].label).toContain("Insert");
  });

  it("Mongo defaults have insert/upsert/replace labels", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
    });
    const result = resolveWriteDispositions(c);
    const labels = result.map((d) => d.label);
    expect(labels).toContain("Insert documents");
    expect(labels).toContain("Upsert by key");
    expect(labels).toContain("Replace collection");
  });

  it("never returns generic append/overwrite/merge for Mongo", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
    });
    const values = resolveWriteDispositions(c).map((d) => d.value);
    expect(values).not.toContain("overwrite");
    expect(values).not.toContain("merge");
  });
});

describe("resolveSinkTargetField", () => {
  it("DOCUMENT targets ask for a collection, not a lake path", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
    });
    const field = resolveSinkTargetField(c);
    expect(field.paramKey).toBe("target_table");
    expect(field.label).toBe("Target Collection");
    expect(field.placeholder).toContain("collection");
  });

  it("LAKE targets ask for output_path", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "S3", enabled: true,
      sinkWriterKind: "LAKE",
    });
    const field = resolveSinkTargetField(c);
    expect(field.paramKey).toBe("output_path");
  });
});

describe("dispositionParamName", () => {
  it("STREAM uses publish_mode", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Kafka", enabled: true,
      sinkWriterKind: "STREAM",
    });
    expect(dispositionParamName(c)).toBe("publish_mode");
  });

  it("non-STREAM uses write_mode", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Postgres", enabled: true,
      sinkWriterKind: "RELATIONAL",
    });
    expect(dispositionParamName(c)).toBe("write_mode");
  });
});

describe("buildDefaultSinkConfig", () => {
  it("INHERIT connector with LAKE kind synthesizes s3 path from template", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "S3 Object", enabled: true,
      sinkWriterKind: "LAKE",
      credentialStrategy: "INHERIT_TENANT_GCP_SERVICE_ACCOUNT",
      configTemplate: { bucket: "my-bucket", path_prefix: "outgoing" },
    });
    const config = buildDefaultSinkConfig(c);
    expect(config.output_path).toBe("s3://my-bucket/outgoing");
  });

  it("CONNECTOR_SPECIFIC LAKE connector does not synthesize path", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "S3 External", enabled: true,
      sinkWriterKind: "LAKE",
      credentialStrategy: "CONNECTOR_SPECIFIC",
      configTemplate: { bucket: "external-bucket" },
    });
    const config = buildDefaultSinkConfig(c);
    expect(config.output_path).toBeUndefined();
  });

  it("DOCUMENT connector never synthesizes lake path from configTemplate", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
      configTemplate: { storage_path: "/data/mongo", bucket: "should-not-use" },
    });
    const config = buildDefaultSinkConfig(c);
    expect(config.output_path).toBeUndefined();
  });

  it("default write_mode uses first declared disposition", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "MongoDB", enabled: true,
      sinkWriterKind: "DOCUMENT",
      writeDispositions: [
        { value: "append", label: "Insert documents" },
        { value: "merge_on_pk", label: "Upsert by key" },
      ],
    });
    const config = buildDefaultSinkConfig(c);
    expect(config.write_mode).toBe("append");
  });
});

describe("buildCredentialWarning", () => {
  it("VALID status = no warning", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "S3", enabled: true,
      credentialReadiness: { connectorInstanceId: "1", status: "VALID" },
    });
    const w = buildCredentialWarning(c, "dev", "Dev");
    expect(w.tone).toBe("none");
    expect(w.message).toBeNull();
  });

  it("READY status = no warning", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "S3", enabled: true,
      credentialReadiness: { connectorInstanceId: "1", status: "READY" },
    });
    const w = buildCredentialWarning(c, "dev", "Dev");
    expect(w.tone).toBe("none");
  });

  it("UNTESTED = yellow (soft), NOT orange (needs attention)", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Postgres", enabled: true,
      credentialReadiness: { connectorInstanceId: "1", status: "UNTESTED" },
    });
    const w = buildCredentialWarning(c, "dev", "Dev");
    expect(w.tone).toBe("yellow");
    expect(w.message).toContain("not yet validated");
  });

  it("INVALID = orange (needs attention)", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Postgres", enabled: true,
      credentialReadiness: { connectorInstanceId: "1", status: "INVALID" },
    });
    const w = buildCredentialWarning(c, "dev", "Dev");
    expect(w.tone).toBe("orange");
    expect(w.message).toContain("need attention");
  });

  it("falls back to credentialStatuses map when no readiness", () => {
    const c = makeConnector({
      id: "1", sorId: "s1", connectorDefinitionId: "d1", name: "Postgres", enabled: true,
      credentialStatuses: { dev: "VALID" } as Partial<Record<DeployEnvironment, CredentialStatus>> as ConnectorInstance["credentialStatuses"],
    });
    const w = buildCredentialWarning(c, "dev", "Dev");
    expect(w.tone).toBe("none");
  });
});
