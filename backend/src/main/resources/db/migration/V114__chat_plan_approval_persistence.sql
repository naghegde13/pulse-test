-- =============================================================================
-- V114 — Chat Plan / Apply Mutation Contract (ARCH-009)
-- =============================================================================
--
-- Goal
--   Make the `plans` table the durable, authoritative record of a chat-driven
--   Plan -> Apply mutation cycle ("Chat -> Plan -> Command").  Today the
--   table only captures the preview; this migration adds the columns the
--   apply step needs in order to be: (a) traceable back to the chat
--   session/message that approved it, and (b) executable from a single
--   canonical source of truth.
--
-- Approval provenance
--   When a user clicks "Apply Plan" in a chat surface we now stamp three
--   coordinated fields on the plan row:
--     - approved_at             : when the approval happened (timestamptz)
--     - approved_by_message_id  : the chat_messages row that carried the
--                                 approval (soft reference, no FK; see below)
--     - approved_by_user_id     : opaque user identifier of the approver
--                                 (VARCHAR(64) to accommodate both ULID
--                                 users.id and external IdP subject ids)
--   Together these answer "who approved this, from which chat turn, and
--   when" without having to reconstruct it from the command_log.
--
-- Executable command source
--   `planned_commands` (JSONB) is the ordered list of fully-resolved command
--   objects that constitute the plan, each shaped as:
--       { type, aggregateType, aggregateId, description, payload }
--   apply_plan(plan_id) reads ONLY from this column to execute the plan; the
--   pre-existing `preview_data` and `command_ids` columns remain in place
--   for backwards compatibility but are no longer the executable source.
--   Storing the full command objects (not just ids) makes the plan
--   self-contained and replayable even if downstream draft state changes.
--
-- Compatibility
--   * All new columns are nullable so existing PREVIEW/APPLIED rows remain
--     valid without backfill.
--   * `plans.session_id` is a soft reference to chat_sessions(id) and
--     `approved_by_message_id` is a soft reference to chat_messages(id).
--     We intentionally do NOT add foreign keys here: the plans table
--     predates a clean FK story with the chat tables (chat sessions can be
--     pruned independently, and plans can be created outside of any chat
--     session), so we keep the linkage soft and indexed instead.
--   * Idempotent: every statement uses IF NOT EXISTS so the migration is
--     safe to re-run against partially-migrated environments.
--   * No DDL is performed on chat_messages or chat_sessions.
-- =============================================================================

ALTER TABLE plans ADD COLUMN IF NOT EXISTS session_id              VARCHAR(26);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS approved_at             TIMESTAMPTZ;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS approved_by_message_id  VARCHAR(26);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS approved_by_user_id     VARCHAR(64);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS planned_commands        JSONB;

-- Lookup of plans by chat session (used by the chat surface to hydrate the
-- current plan / plan history for a session).
CREATE INDEX IF NOT EXISTS idx_plans_session ON plans(session_id);

-- Status-only index for queue-style scans across tenants (e.g. find all
-- APPROVED plans awaiting apply).  Note: V1 already created an index named
-- `idx_plans_status` on (tenant_id, status) for tenant-scoped queries; this
-- new index is named distinctly so IF NOT EXISTS does not silently skip it.
CREATE INDEX IF NOT EXISTS idx_plans_status_only ON plans(status);
