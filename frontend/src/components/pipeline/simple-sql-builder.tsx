"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { getTypeColor } from "./column-picker";
import type { SchemaColumn } from "@/types";

/**
 * SimpleSqlBuilder — the PLAIN, validate-only SQL editor used by the
 * SourceSQL (`source_query`) and BulkBackfill (`source_query`) constructs
 * (SPEC-construct-library §1.1b, ADR 0024).
 *
 * Deliberately NOT a rich editor:
 *   - No Spark-function palette and no per-dialect SQL parser. PULSE does
 *     NOT build Oracle/Postgres/etc. parsers. The source database validates
 *     its OWN dialect via a JDBC prepare / getMetaData round-trip and hands
 *     back the result schema — so the only affordance here is a Validate
 *     button that ships the raw SQL to the backend.
 *   - Validation is BUTTON-triggered, not debounced-on-type: a JDBC prepare
 *     against a live source is expensive and not something to fire on every
 *     keystroke (contrast ExpressionInput, which debounces the cheap
 *     in-process Calcite validator).
 *
 * `[[ … ]]` date mnemonics (ADR 0024) are supported inline. The BACKEND
 * substitutes each token to a dummy DATE before the prepare; the frontend
 * just sends the raw SQL with the `[[ ]]` tokens intact.
 */

export interface SimpleSqlBuilderProps {
  /** The SQL text (the `source_query` param value). */
  value: string;
  onChange: (sql: string) => void;
  /** The bound source connector instance this SQL is validated against
   *  (S1b validates the query's dialect/schema against this source). */
  connectorInstanceId?: string;
  placeholder?: string;
}

/**
 * Shape returned by POST /api/v1/sql/source/validate. The source DB does the
 * dialect validation via JDBC prepare/getMetaData and returns either the
 * result-set columns (on success) or a driver error string (on failure).
 */
interface SourceSqlValidateResult {
  valid: boolean;
  columns?: SchemaColumn[];
  error?: string;
}

type ValidateState =
  | { kind: "idle" }
  | { kind: "validating" }
  | { kind: "valid"; columns: SchemaColumn[] }
  | { kind: "invalid"; error: string }
  // The validator endpoint isn't reachable yet — non-fatal, never crashes.
  | { kind: "unavailable"; message: string };

export function SimpleSqlBuilder({
  value,
  onChange,
  connectorInstanceId,
  placeholder,
}: SimpleSqlBuilderProps) {
  const [state, setState] = useState<ValidateState>({ kind: "idle" });

  async function handleValidate() {
    setState({ kind: "validating" });
    try {
      // BACKEND DEPENDENCY (structurally-built): the source-DB JDBC-prepare
      // validator endpoint is OUT of this lane and may not exist yet. We wire
      // the call and degrade gracefully if it 404s / throws (see catch below).
      // The backend is responsible for substituting [[ ]] date mnemonics to a
      // dummy DATE before the prepare — we send the raw SQL with tokens intact.
      const result = await api.post<SourceSqlValidateResult>(
        "/api/v1/sql/source/validate",
        { connectorInstanceId, sql: value }
      );
      if (result.valid) {
        setState({ kind: "valid", columns: result.columns ?? [] });
      } else {
        setState({
          kind: "invalid",
          error: result.error ?? "Validation failed.",
        });
      }
    } catch {
      // Endpoint not built yet / network failure: surface a NON-FATAL notice
      // rather than crashing the construct. Validation simply isn't available.
      setState({
        kind: "unavailable",
        message:
          "Validator unavailable — the source-SQL validation endpoint is not yet implemented.",
      });
    }
  }

  const validating = state.kind === "validating";

  return (
    <div className="space-y-1.5">
      <Textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={
          placeholder ??
          "SELECT loan_id, balance FROM loans WHERE as_of_date = [[ PBD ]]"
        }
        spellCheck={false}
        rows={5}
        className="font-mono text-xs"
      />

      <p className="text-[10px] text-muted-foreground">
        Plain source-dialect SQL. <code className="font-mono">[[ … ]]</code> date
        tokens are supported and resolved at run time. The source database
        validates its own dialect.
      </p>

      <div className="flex items-center gap-2 flex-wrap">
        <Button
          type="button"
          size="xs"
          variant="outline"
          onClick={handleValidate}
          disabled={validating || value.trim().length === 0}
        >
          {validating ? "Validating…" : "Validate"}
        </Button>

        {state.kind === "validating" && (
          <Badge
            variant="outline"
            className="text-[10px] text-muted-foreground"
          >
            validating…
          </Badge>
        )}
        {state.kind === "valid" && (
          <Badge
            variant="outline"
            className="text-[10px] border-emerald-400 text-emerald-700"
          >
            ✓ valid
          </Badge>
        )}
        {state.kind === "invalid" && (
          <Badge
            variant="outline"
            className="text-[10px] border-red-400 text-red-700"
          >
            ✗ invalid
          </Badge>
        )}
        {state.kind === "unavailable" && (
          <Badge
            variant="outline"
            className="text-[10px] border-amber-400 text-amber-700"
          >
            validator unavailable
          </Badge>
        )}
      </div>

      {/* Result schema — the columns the source returned for this query. */}
      {state.kind === "valid" && (
        <div className="space-y-1">
          {state.columns.length === 0 ? (
            <p className="text-[10px] text-muted-foreground">
              Query is valid but returns no columns.
            </p>
          ) : (
            <>
              <p className="text-[10px] text-muted-foreground">
                Result schema ({state.columns.length} column
                {state.columns.length > 1 ? "s" : ""}):
              </p>
              <div className="flex flex-wrap gap-1">
                {state.columns.map((col) => (
                  <span
                    key={col.name}
                    className="inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px]"
                  >
                    <span className="font-mono">{col.name}</span>
                    <span
                      className={cn(
                        "text-[8px] px-1 rounded",
                        getTypeColor(col.type)
                      )}
                    >
                      {col.type}
                    </span>
                  </span>
                ))}
              </div>
            </>
          )}
        </div>
      )}

      {/* Driver error from the source DB (e.g. ORA-00942). */}
      {state.kind === "invalid" && (
        <p className="text-[10px] text-red-600 font-mono">{state.error}</p>
      )}

      {/* Non-fatal: validator endpoint not reachable. */}
      {state.kind === "unavailable" && (
        <p className="text-[10px] text-amber-700">{state.message}</p>
      )}
    </div>
  );
}
