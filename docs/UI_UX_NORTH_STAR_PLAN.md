# PULSE UI/UX North Star Plan

## Status

This document is the comprehensive UI/UX north-star implementation plan for PULSE. It is designed to be implemented in phases without lowering the ambition of the target product experience.

Refresh note: this revision is aligned with `PROJECT_CONTEXT.MD` and `docs/ARCHITECTURE_LEDGER.md` as of 2026-05-14. If this plan conflicts with those files or with code, treat the ledger/code as the higher authority and update this plan before implementation.

PULSE should become a polished enterprise developer tool for building, understanding, generating, and deploying data pipelines. The target experience has two skins:

- **Refined App Skin**: the current application shape made cleaner, more consistent, more legible, and more confidence-inspiring across dashboards, producer/SOR management, pipeline classic views, chat, code generation, Git, deploy, and EBCDIC discovery.
- **Developer/IDE Skin**: a first-class workspace for pipeline builders, with version-scoped chat, code artifacts, composition, orchestration, story, deployability, command search, workspace tabs, and audit/navigation context all operating against the same explicit pipeline version.

Phasing is a risk-control mechanism. It is not a reduction in ambition.

## Non-Negotiable Authority Rules

- `PipelineVersion.id` is the canonical `versionId`.
- Integer revision numbers are display-only labels.
- URL query param `rev` stores a `versionId`, not a revision number.
- `activeVersionId` is display-only status. It is never the implicit source of truth for workspace selection, chat mutation, story generation, codegen editing, tool execution, or deploy/package mutation.
- New contracts must not introduce `revision_id` or `revisionId`.
- Every version-scoped mutation receives an explicit `versionId` from selected workspace state.
- Runtime persona is deployment-level authority. The UI must show the active `GCP_PULSE` or `DPC_PULSE` persona as read-only global context and must not ask users to choose DPC/GCP per pipeline, per leg, per chat turn, or per deploy action.
- `runtime_bindings` plus the runtime-binding authority facade are the deployability and physical-resource authority. Legacy `storage_backends` and flat deployment-target rows are diagnostic/compatibility evidence until cut over; UI pickers must not treat diagnostic rows as deployable.
- Storage/lake placement and physical table facts come from canonical blueprint fields, `BlueprintInstanceConfigurationService`, `StorageAuthorityFacade`, landing/table contracts, and runtime projections. UI previews must not rebuild paths or storage decisions from raw params or ad hoc string rules.
- Chat is a plan/apply control surface. `plan_*` tools are side-effect-free except for plan records; `apply_plan(plan_id)` is the generic mutation path; SSE/tool-result UI must key off structured fields such as `planCreated`, `mutationApplied`, `planId`, `commandIds`, `affectedEntities`, `refreshHints`, and `message`.
- Deprecated or deferred blueprints are read-only compatibility artifacts in add/configure flows. UI, command search, and chat must not reopen normal add/mutation paths for those rows.
- Generated higher-environment runtime packages must be standalone. UI copy, package/deploy checks, command results, and code views must not require or encourage `PULSE_API_URL`, peer Pulse endpoints, internal Pulse brokers, or Pulse Postgres access at runtime.
- Deploy adapters deploy, sync, and activate Airflow artifacts only: Composer for GCP persona or DPC/on-prem Airflow for DPC persona. Deploy time must not submit Spark, Dataproc, dbt, or GX work directly; those execute only when Airflow runs the generated DAG.

## Current Architecture Alignment Overlay

This plan is a UI/UX north star, not a competing architecture source. The following overlays constrain every phase:

- **Runtime persona and settings**: Settings surfaces separate primary runtime bindings from diagnostic legacy rows. Global chrome and deploy panels show the active deployment persona and explain unavailable options as persona/binding constraints, not as user-selectable runtime choices.
- **Deployment target UX**: Deploy target lists are eligibility views over runtime bindings and package/runtime projection. A target row is actionable only when the runtime-binding authority resolves a persona-legal, enabled, schema-valid, binding-complete Airflow target. Diagnostic targets may be inspected but never selected for deploy.
- **Package/projection UX**: Package and deploy surfaces display source package manifest identity, runtime projection hash, projection drift, DDL/table-contract evidence, orchestration namespace, stable DAG ID, and deploy blockers from shared readiness/preflight services. UI must not infer deployability from filenames, target JSON defaults, or adapter-specific guesses.
- **Airflow boundary**: Any smoke-run affordance triggers an Airflow DAG run and polls Airflow run state. No button, command, chat tool, or deploy adapter action submits Spark/Dataproc directly during deploy.
- **AdvanceTimeDimension**: Codegen, deployability, and package panels must surface blockers if `AdvanceTimeDimension` generated artifacts use Pulse API callbacks, `PULSE_API_URL`, `PULSE_PG_*`, or a Pulse metadata database strategy outside local/dev. The acceptable promoted strategy is Airflow-native runtime state plus packaged/projected calendar/evidence assets.
- **Cross-substrate orchestration**: The UI may configure cross-substrate invocation only through design-time plan/apply and runtime projection contracts. It must not imply chat or Pulse runtime services cancel or operate remote DAGs directly.
- **Developer workspace and Git**: Git-aware editing uses DeveloperWorkspace/package/release authority where available. Generated artifact rows are snapshots/cache in Git-aware flows, while promotable packages come from clean Git commits and immutable package evidence.

## Phase 0: Inventory, Tokens, Contract Freeze

Audit all current screens and primary states:

- Global app shell, sidebar, top bar, tenant/auth states, global chat drawer.
- `/chat` assistant page.
- Pipeline classic page.
- Target pipeline IDE workspace.
- Composition panel, DAG view, transform configuration, orchestration, code editor, dbt assets, Git, deploy, deployability, story generation.
- Producer/SOR detail, connector list, dataset list, dataset dialogs.
- EBCDIC discovery.
- Empty, loading, error, stale, conflict, streaming, and long-running states.

Define shared tokens before large UI edits:

- App background, work surface, panel background, panel border, raised overlay.
- Muted/default/active/destructive/warning/success/info text and controls.
- Code/editor background, graph grid, graph edge, selected graph edge, assistant accent.
- Typography scale for dense app surfaces and IDE surfaces.
- Spacing for shells, forms, toolbars, inspectors, tabs, drawers, modals, graph controls, and code editor headers.
- Interaction states for focus, hover, selected, disabled, loading, streaming, stale, conflict, read-only, unsaved, and failed.

No viewport-width font scaling.

Physical token source:

- Token implementation owner: `frontend/src/styles/pulse-tokens.css`.
- Import site: `frontend/src/app/globals.css` imports `../styles/pulse-tokens.css` before component styles.
- Tailwind bridge uses Tailwind 4 CSS-first theming. `frontend/src/app/globals.css` owns the `@theme inline` block and maps `--color-*`, `--spacing-*`, `--radius-*`, `--shadow-*`, and font tokens to the `--pulse-*` variables. Do not introduce `frontend/tailwind.config.ts` for this plan.
- Migration path: first add raw `--pulse-*` variables in `pulse-tokens.css`; then update the existing `@theme inline` declarations in `globals.css` to reference those variables; then migrate component classes/styles to semantic Tailwind tokens backed by the CSS variables.
- Component consumers: shell/layout components, `frontend/src/components/ui/*`, pipeline panels, chat components, EBCDIC components, Git/deploy panels, and command palette consume semantic variables only. Feature components must not introduce raw hex values except temporary chart colors documented next to the chart.

Token naming scheme:

```css
:root {
  --pulse-bg-app: ...;
  --pulse-bg-surface: ...;
  --pulse-bg-panel: ...;
  --pulse-bg-overlay: ...;
  --pulse-border-subtle: ...;
  --pulse-border-strong: ...;
  --pulse-text-primary: ...;
  --pulse-text-secondary: ...;
  --pulse-text-muted: ...;
  --pulse-accent-app: ...;
  --pulse-accent-ide: ...;
  --pulse-status-success: ...;
  --pulse-status-warning: ...;
  --pulse-status-danger: ...;
  --pulse-status-info: ...;
  --pulse-code-bg: ...;
  --pulse-graph-grid: ...;
  --pulse-graph-edge: ...;
  --pulse-graph-edge-selected: ...;
  --pulse-radius-control: 6px;
  --pulse-radius-panel: 8px;
  --pulse-space-1: 4px;
  --pulse-space-2: 8px;
  --pulse-space-3: 12px;
  --pulse-space-4: 16px;
  --pulse-font-ui: ...;
  --pulse-font-mono: ...;
}

[data-pulse-skin="ide"] {
  --pulse-bg-app: ...;
  --pulse-accent-app: var(--pulse-accent-ide);
}
```

Skin switch:

- `AppChrome` renders `data-pulse-skin="app"`.
- `AssistantShell` renders `data-pulse-skin="app"`.
- `WorkspaceShell` renders `data-pulse-skin="ide"`.
- The two skins share component primitives but differ by shell density, toolbar rhythm, panel contrast, and IDE-specific accent tokens.

Acceptance:

- Complete UI inventory exists.
- Token set exists in code and is applied first to shell-level surfaces.
- `rg -n "#[0-9a-fA-F]{3,8}" frontend/src` finds no new unapproved raw colors after token landing.
- No route behavior changes yet.
- No backend contract changes yet.

## Phase 1: Shell, Routing, Version Authority, Chat, And Story Foundations

### Shell Ownership

Create or extract these frontend surfaces:

- `frontend/src/components/shell/authenticated-providers.tsx`
- `frontend/src/components/shell/app-chrome.tsx`
- `frontend/src/components/shell/assistant-shell.tsx`
- `frontend/src/components/shell/workspace-shell.tsx`
- `frontend/src/components/shell/workspace-shell-provider.tsx`
- `frontend/src/components/shell/shortcut-provider.tsx`
- `frontend/src/components/shell/correlation-context.tsx`
- `frontend/src/contexts/feature-flag-context.tsx`
- `frontend/src/components/chat/assistant-ui-context.tsx`

Ownership:

- `frontend/src/app/layout.tsx` owns `AuthProvider`, root `TooltipProvider`, and root `Toaster`.
- `AuthGate` becomes auth-only and renders authenticated children. It does not mount sidebar, top bar, drawers, chat surfaces, tenant providers, or shortcut handlers.
- `AuthenticatedProviders` owns `TenantProvider`, `FeatureFlagProvider`, `CorrelationProvider`, `ChatProvider`, `AssistantUiProvider`, and `ShortcutProvider`.
- `FeatureFlagProvider` mounts inside `TenantProvider` and before providers/components that branch on flags. It fetches `GET /api/v1/tenants/{tenantId}/features` after tenant resolution, exposes conservative defaults while loading, and refetches on tenant change.
- `AppChrome` owns `SidebarProvider`, `AppSidebar`, `TopBar`, and `GlobalChatDrawer`.
- `WorkspaceShellProvider` owns workspace route/layout state and wraps `WorkspaceShell`; `WorkspaceShell` owns only the rendered workspace layout chrome.
- `AssistantShell` owns `/chat` layout.
- `frontend/src/components/ui/sidebar.tsx` removes its local `TooltipProvider` and local Cmd+B listener. `ShortcutProvider` owns shortcuts.

Canonical mount tree:

- `frontend/src/app/layout.tsx`: `AuthProvider` -> root providers -> route children.
- `AuthGate`: auth check only.
- `AuthenticatedProviders`: `TenantProvider` -> `FeatureFlagProvider` -> `CorrelationProvider` -> `ChatProvider` -> `AssistantUiProvider` -> `ShortcutProvider`.
- Standard authenticated routes: `AuthenticatedProviders` -> `AppChrome` -> page.
- Pipeline classic route: `AuthenticatedProviders` -> `AppChrome` -> `PipelineClassicPage`.
- Pipeline IDE route: `AuthenticatedProviders` -> `PipelineRouteStateController` -> `WorkspaceShellProvider` -> `WorkspaceShell` -> IDE page.
- `/chat`: `AuthenticatedProviders` -> `AssistantShell` -> `GlobalChatController` full-page mode.
- `GlobalChatDrawer` is mounted only inside `AppChrome`, never inside `AuthGate`.
- Because `/chat` uses `AssistantShell`, `AppChrome` and `GlobalChatDrawer` are not mounted on `/chat`.

`AssistantUiProvider` contract:

```ts
type AssistantUiState = {
  globalDrawerOpen: boolean;
  lastOpenedFrom: "topbar" | "command" | "shortcut" | "navigation" | null;
  pendingFocus: "composer" | "messages" | null;
};

type AssistantUiActions = {
  openGlobalDrawer(source: AssistantUiState["lastOpenedFrom"], options?: { focus?: AssistantUiState["pendingFocus"] }): void;
  closeGlobalDrawer(): void;
  toggleGlobalDrawer(source: AssistantUiState["lastOpenedFrom"]): void;
  consumePendingFocus(): AssistantUiState["pendingFocus"];
};
```

- `TopBar` calls `openGlobalDrawer("topbar", {focus:"composer"})`.
- Command `OPEN_GLOBAL_ASSISTANT` calls `openGlobalDrawer("command", {focus:"composer"})` only when `CommandActionHost` is mounted under `AppChrome`; inside `AssistantShell` it focuses the full-page composer and does not open a drawer. Workspace assistant side-panel opening is a separate `OPEN_PANEL target:"assistant"` action valid only in `WorkspaceShell`.
- `GlobalChatDrawer` reads `globalDrawerOpen`, calls `closeGlobalDrawer`, and consumes pending focus after opening.
- `/chat` never mounts `GlobalChatDrawer`; `AssistantShell` may read pending focus but ignores drawer open state.
- `AssistantUiProvider` stores only UI chrome state. It never stores chat messages, session ids, pipeline ids, or tenant ids.

Next.js App Router structure:

- Use route groups rather than pathname-conditionally mounting shells.
- Root layout remains `frontend/src/app/layout.tsx`.
- Authenticated shell group:
  - `frontend/src/app/(app)/layout.tsx` renders `AuthGate` -> `AuthenticatedProviders` -> `AppChrome`.
  - Standard authenticated routes move under `(app)`.
  - Pipeline classic route lives under `(app)/pipelines/[pipelineId]`.
- Assistant group:
  - `frontend/src/app/(assistant)/layout.tsx` renders `AuthGate` -> `AuthenticatedProviders` -> `AssistantShell`.
  - `/chat` lives under `(assistant)/chat/page.tsx`.
- Workspace group:
  - `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/layout.tsx` renders `AuthGate` -> `AuthenticatedProviders`; the page file mounts `PipelineRouteStateController` -> `WorkspaceShellProvider` -> `WorkspaceShell` after it has `pipelineId` route params.
  - Public IDE URL is `/workspace/pipelines/{pipelineId}`.
- Do not use `?view=ide` for the public IDE route. The `view` query key is retired once `/workspace/pipelines/{pipelineId}` exists.
- `/pipelines/{pipelineId}` and `/pipelines/{pipelineId}?view=classic` remain classic AppChrome routes.
- `/pipelines/{pipelineId}?view=ide` is a temporary compatibility redirect to `/workspace/pipelines/{pipelineId}`, preserving `rev`, `tab`, `side`, `bottom`, `activity`, and valid hash.
- Do not use pathname-conditionals inside `AuthGate` to decide shell ownership.
- Route-group files may exist while their runtime flags are false. Flags control entry points, redirects, shell affordances, and feature surfaces after `FeatureFlagProvider` hydrates; they do not require unmounting the provider tree itself.
- If `ui.workspace.ideRoute=false`, direct `/workspace/pipelines/{pipelineId}` requests render a feature-disabled state with a link to classic instead of mounting version-mutating IDE panels. If `ui.shell.groups=false`, route groups render legacy-equivalent chrome and hide new grouped-shell affordances while the structural extraction remains in place.

Canonical future route files:

- Classic public route: `frontend/src/app/(app)/pipelines/[pipelineId]/page.tsx`.
- Classic page body: `frontend/src/app/(app)/pipelines/[pipelineId]/pipeline-classic-page.tsx`.
- IDE public route: `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx`.
- IDE layout: `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/layout.tsx`.
- Full-page assistant route: `frontend/src/app/(assistant)/chat/page.tsx`.
- Shared route state controller: `frontend/src/components/pipeline/pipeline-route-state-controller.tsx`.

Workspace shell state contract:

```ts
type WorkspaceTab = "composition" | "code" | "dbt" | "orchestration" | "story" | "deployability" | "git";
type WorkspaceSidePanel = "assistant" | "inspector" | "none";
type WorkspaceBottomPanel = "problems" | "runs" | "diffs" | "events" | "audit" | "none";
type WorkspaceActivity = "explorer" | "composition" | "code" | "assistant" | "deploy" | "history";
type WorkspaceBottomParams = {
  problemsIssueId?: string;
  runId?: string;
  runKind?: "codegen" | "deployment";
  artifactId?: string;
  eventId?: string;
  correlationId?: string;
};

type WorkspaceShellState = {
  tab: WorkspaceTab;
  side: WorkspaceSidePanel;
  bottom: WorkspaceBottomPanel;
  activity: WorkspaceActivity;
  hashAnchor: string | null;
  bottomParams: WorkspaceBottomParams;
  leftRailCollapsed: boolean;
  sideWidthPx: number;
  bottomHeightPx: number;
};

type WorkspaceShellActions = {
  setTab(tab: WorkspaceTab): void;
  setSide(side: WorkspaceSidePanel): void;
  setBottom(bottom: WorkspaceBottomPanel): void;
  setActivity(activity: WorkspaceActivity): void;
  openPanel(target: "assistant" | "inspector" | "problems" | "runs" | "diffs" | "events" | "audit", params?: WorkspaceBottomParams): void;
  closeSide(): void;
  closeBottom(): void;
  navigateAnchor(anchor: string | null): void;
  setLayoutSize(next: { sideWidthPx?: number; bottomHeightPx?: number; leftRailCollapsed?: boolean }): void;
};
```

URL/state rules:

- `tab`, `side`, `bottom`, `activity`, and hash are source-of-truth route state owned by `WorkspaceShellProvider`.
- Bottom panel filters/selections are source-of-truth route state owned by `WorkspaceShellProvider` when they are encoded in URL query keys: `problemsIssueId`, `runId`, `runKind`, `artifactId`, `eventId`, and `correlationId`.
- `PipelineRouteStateController` is mounted above `WorkspaceShellProvider`. It reads pipeline params/search first, validates/canonicalizes only `rev`, and exposes `routeReady`, `selectedVersionId`, `selectedVersion`, `pipeline`, version paging state, and `canonicalizationInProgress` through `PipelineRouteStateContext`.
- `WorkspaceShellProvider` consumes `PipelineRouteStateContext`. It does not normalize `tab`, `side`, `bottom`, `activity`, or hash until `routeReady=true` or the route is in a no-version state that will not trigger another `rev` replacement.
- `WorkspaceShellProvider` reads initial workspace state from search params/hash, validates against the union types above, and writes canonical replacements for invalid or omitted workspace values only after the route controller has settled.
- `PipelineRouteStateController` does not validate or rewrite workspace keys. It owns only pipeline/version route authority, especially `rev`.
- Defaults are `tab=composition`, `side=assistant`, `bottom=problems`, and `activity=composition`.
- `setTab`, `setSide`, `setBottom`, `setActivity`, `openPanel`, `closeSide`, `closeBottom`, and `navigateAnchor` update URL search params/hash through router replace for canonicalization and router push for user-initiated navigation.
- Layout dimensions and `leftRailCollapsed` are the only workspace state persisted in localStorage, under `pulse.workspaceLayout:{tenantId}:{pipelineId}`.
- Hidden side/bottom panels preserve in-memory loaded state for the current route key `{tenantId}:{pipelineId}:{selectedVersionId}`. Changing `selectedVersionId` clears panel data that is version-scoped.
- `OPEN_PANEL target:"assistant"` sets `side=assistant` inside `WorkspaceShell` only; `target:"inspector"` sets `side=inspector`; `target:"runs"|"events"|"audit"|"diffs"|"problems"` sets `bottom` to that value and serializes supported `bottomParams` to query keys. `OPEN_PANEL` never opens the global chat drawer and never changes `tab` unless the command target is a `NAVIGATE` URL that includes `tab`.
- Bottom query keys are stripped when `bottom=none`. On version switch, `runId`, `artifactId`, and `eventId` are cleared unless the new version resolves the referenced entity; `correlationId` may persist for cross-scope audit filtering.

IDE tab mount map:

| Tab | Mounted component owner | Scope |
| --- | --- | --- |
| `composition` | `frontend/src/components/pipeline/composition-panel.tsx` | PIPELINE_VERSION |
| `code` | shared editor primitives from `frontend/src/components/pipeline/code-editor-panel.tsx` | PIPELINE_VERSION |
| `dbt` | `frontend/src/components/pipeline/dbt-asset-panel.tsx` | PIPELINE_VERSION |
| `orchestration` | `frontend/src/components/pipeline/orchestration-panel.tsx` | PIPELINE_VERSION |
| `story` | `frontend/src/components/pipeline/story-pane.tsx` | PIPELINE_VERSION |
| `deployability` | deploy/readiness pane backed by `frontend/src/components/pipeline/deploy-panel.tsx` primitives | PIPELINE_VERSION |
| `git` | Git pane backed by `frontend/src/components/pipeline/git-panel.tsx` primitives | PIPELINE_VERSION |

Side/bottom mount map:

- `side=assistant`: `frontend/src/components/pipeline/workspace-assistant-panel.tsx`.
- `side=inspector`: tab-specific inspector registered by the active tab; absent inspector shows empty inspector state and does not fetch.
- `bottom=problems|runs|diffs|events|audit`: bottom panel contracts defined in the Workspace Events and Codegen sections.
- `side=none` or `bottom=none` unmounts the visible pane shell but preserves provider state for the current route key.

Bottom panel route contract:

- Runs selection URL: `bottom=runs&runKind=codegen|deployment&runId=<id>`.
- Diffs selection URL: `bottom=diffs&artifactId=<artifactId>`.
- Events selection URL: `bottom=events&eventId=<eventId>`.
- Audit filter URL: `bottom=audit&correlationId=<correlationId>`.
- Problems issue focus URL: `bottom=problems&problemsIssueId=<issueId>`.
- `selectedRunKind` is the command-search request field used with `selectedRunId`; without it the backend may only return generic `Open runs panel`, not run-kind-specific commands.
- Back/forward restores bottom panel, filters, selected rows, and expanded detail using these query keys. Invalid referenced ids keep the panel open, clear selection, and show the not-found toast defined in the anchor matrix.

Activity behavior:

| Activity | Owner | Visible surface | Route side effects | Default transition |
| --- | --- | --- | --- | --- |
| `explorer` | `WorkspaceShellProvider` + active tab | Left activity rail highlights Explorer; side panel becomes `inspector` when the active tab has an inspector. | Does not change `tab` or `bottom`; may set `side=inspector` when invoked from the rail. | From no side panel: `side=inspector`; otherwise preserve side. |
| `composition` | Composition workspace | Highlights composition tools and graph navigator. | Does not force `tab`, but command targets that open composition use `tab=composition`. | New IDE routes default to `activity=composition`. |
| `code` | Code workspace | Highlights artifact tree/editor tools. | Does not force `tab`, but code/dbt command targets use `activity=code`. | Opening Code or dbt assets sets `activity=code`. |
| `assistant` | Workspace assistant | Highlights assistant rail item and opens assistant side panel. | Sets `side=assistant`; does not change `tab` or `bottom`. | Story commands use `activity=assistant` and preserve bottom. |
| `deploy` | Deploy/Git workspace | Highlights deploy/release tools. | Does not force `tab`, but deployability/Git commands use `activity=deploy`. | Opening Deployability or Git sets `activity=deploy`. |
| `history` | Version history rail | Highlights history/version controls. | Does not force `tab`; may open version selector/history rail. | `Open history rail` uses `tab=composition`, `activity=history`, `side=assistant`, `bottom=problems`. |

`WorkspaceShellProvider` owns the activity rail click behavior. Activity changes may update `side` as listed above, but only explicit `NAVIGATE` command targets may change `tab`.

Explorer/history rail:

- Owner component: `frontend/src/components/shell/workspace-activity-rail.tsx`.
- Detail rail component: `frontend/src/components/shell/workspace-left-rail.tsx`.
- `activity=explorer` forces `leftRailCollapsed=false` and renders explorer content for the active tab: composition node tree for `tab=composition`, artifact tree for `tab=code`, dbt asset tree for `tab=dbt`, deploy/Git navigation groups for `tab=deployability|git`, and story outline for `tab=story`.
- `activity=history` forces `leftRailCollapsed=false` and renders version history from `PipelineRouteState.versions`, `pinnedSelectedVersion`, `loadMoreVersions`, `hasMoreVersions`, and selected version metadata. It never fetches active/latest independently.
- Explorer and history are mutually exclusive left-rail modes selected by `activity`; switching between them preserves each mode's scroll position for the current `{tenantId}:{pipelineId}:{selectedVersionId}` route key.
- User exits the rail by clicking the active rail icon again or the rail close button, which sets `leftRailCollapsed=true` and preserves `activity` for command context. Selecting another activity with rail content reopens it.
- Version selection inside history rail pushes a new `rev` while preserving `tab`, `side`, `bottom`, and supported bottom params unless the selected entity does not exist in the new version, in which case the owning panel clears that param.

Migration file movement:

- Move/split the current `frontend/src/app/pipelines/[pipelineId]/page.tsx` into the classic route file and the IDE route file above.
- Move the current `frontend/src/app/chat/page.tsx` placeholder into `frontend/src/app/(assistant)/chat/page.tsx` and replace its drawer-opening behavior with the full-page assistant surface.
- Do not leave public route ownership in both grouped and ungrouped paths after the migration.

Current-file migration map:

| Current file | Target decision |
| --- | --- |
| `frontend/src/components/layout/auth-gate.tsx` | Retain path, reduce to auth-only gate; remove sidebar/topbar/drawer/provider ownership. |
| `frontend/src/components/layout/top-bar.tsx` | Retain path under `AppChrome`; remove `/chat` special handling and open only `GlobalChatDrawer`. |
| `frontend/src/components/layout/app-sidebar.tsx` | Retain path under `AppChrome`; remove shell/global provider assumptions. |
| `frontend/src/contexts/chat-context.tsx` | Retain during Phase 1, then migrate to `statesByScopeKey`; remove single global `pipelineId` steering after controller split. |
| `frontend/src/components/pipeline/chat-panel.tsx` | Replace/split into `frontend/src/components/pipeline/pipeline-chat-panel.tsx`, shared `frontend/src/components/chat/chat-stream.ts`, `frontend/src/components/chat/chat-message-list.tsx`, and `frontend/src/components/chat/chat-composer.tsx`; delete or leave only as a temporary compatibility wrapper that imports `PipelineChatPanel`. |
| `frontend/src/components/pipeline/code-editor-panel.tsx` | Retain path for classic, then share editor primitives with IDE code tab; migrate codegen API calls to tenant-scoped routes. |
| `frontend/src/components/pipeline/deploy-panel.tsx` | Retain path for classic deploy; migrate to explicit selectedVersionId and tenant-scoped deploy APIs. |
| `frontend/src/components/pipeline/git-panel.tsx` | Retain path for classic Git display/actions; IDE Git tab may reuse presentational parts but must receive explicit selectedVersionId. |
| `frontend/src/components/pipeline/user-story-dialog.tsx` | Retain path as modal/dialog wrapper only; add `storyScope`, `selectedVersionId`, JSON body send, and cache invalidation contract. |
| `frontend/src/components/pipeline/story-pane.tsx` | New persistent IDE story tab surface. Uses shared story fetching/rendering hook and never renders modal chrome. |
| `frontend/src/components/pipeline/composition-panel.tsx` | Retain path; require explicit selectedVersionId for versioned mutations. |
| `frontend/src/components/pipeline/orchestration-panel.tsx` | Retain path; require explicit selectedVersionId. |
| `frontend/src/components/pipeline/dbt-asset-panel.tsx` | Retain path; require explicit selectedVersionId. |
| `frontend/src/types/index.ts` | Retain path; add/update DTOs for route state, chat, story, codegen, deploy, Git, workspace events, command search, and EBCDIC. |
| `backend/src/main/java/com/pulse/chat/model/ChatSession.java` | Add scoped chat fields and migration compatibility behavior. |
| `backend/src/main/java/com/pulse/chat/model/ChatMessage.java` | Add version, turn, correlation, and metadata fields. |
| `backend/src/main/java/com/pulse/chat/repository/ChatSessionRepository.java` | Add exact GLOBAL, PIPELINE, and PIPELINE_VERSION latest/list queries. |
| `backend/src/main/java/com/pulse/chat/repository/ChatMessageRepository.java` | Add cursor-paged history queries. |
| `backend/src/main/java/com/pulse/chat/service/ChatToolExecutor.java` | Enforce registry preflight and explicit version authority. |
| `backend/src/main/java/com/pulse/chat/service/PhaseDetector.java` | Receive explicit context and stop inferring version from active/latest. |
| `backend/src/main/java/com/pulse/codegen/controller/CodeGenController.java` | Add tenant-scoped canonical codegen reads/mutations and legacy shim behavior. |

Authenticated route migration inventory:

- `frontend/src/app/page.tsx` moves to `frontend/src/app/(app)/page.tsx`.
- `frontend/src/app/pipelines/page.tsx` moves to `frontend/src/app/(app)/pipelines/page.tsx`.
- `frontend/src/app/producers/page.tsx` moves to `frontend/src/app/(app)/producers/page.tsx`.
- `frontend/src/app/producers/[sorId]/page.tsx` moves to `frontend/src/app/(app)/producers/[sorId]/page.tsx`.
- `frontend/src/app/commands/page.tsx` moves to `frontend/src/app/(app)/commands/page.tsx`.
- `frontend/src/app/settings/page.tsx` moves to `frontend/src/app/(app)/settings/page.tsx`.
- `frontend/src/app/domains/page.tsx` moves to `frontend/src/app/(app)/domains/page.tsx`.
- `frontend/src/app/targets/page.tsx` moves to `frontend/src/app/(app)/targets/page.tsx`.
- `frontend/src/app/targets/[targetId]/page.tsx` moves to `frontend/src/app/(app)/targets/[targetId]/page.tsx`.
- `frontend/src/app/blueprints/page.tsx` moves to `frontend/src/app/(app)/blueprints/page.tsx`.
- `frontend/src/app/ebcdic-discovery/page.tsx` moves to `frontend/src/app/(app)/ebcdic-discovery/page.tsx`.
- Add `frontend/src/app/(app)/settings/pipelines/[pipelineId]/git/page.tsx` as the canonical AppChrome route for versionless pipeline Git attachment setup. Public URL is `/settings/pipelines/{pipelineId}/git`.
- `frontend/src/app/chat/page.tsx` does not move to `(app)`; it moves to `(assistant)`.
- `frontend/src/app/pipelines/[pipelineId]/page.tsx` is split between `(app)` classic and `(workspace)` IDE as listed above.
- Public URLs do not change when routes move into route groups.

### Correlation And Turn Identity

`CorrelationProvider` owns route/action correlation for the authenticated app.

Correlation lifecycle:

- A `correlationId` identifies a visible user action or workflow burst.
- `CorrelationProvider` creates a new id when a user initiates a top-level action: chat send, story generate, revision create, lifecycle transition, direct composition edit, orchestration edit, DQ apply action, code generate, artifact save, deploy/package action, Git attach/edit/detach, Git branch switch/create, Git pull-request create/status action, command palette action, EBCDIC upload/run/profile save.
- A correlation id is reused for child operations caused by that action, including tool calls, navigation events, workspace events, codegen runs, artifact edits, and assistant follow-up events.
- A route change caused by a tool/navigation event keeps the same correlation id for the navigation event only; the next independent user action gets a new id.
- Idle background polling does not create a new correlation id. Poll responses may include server ids but do not overwrite the active user-action correlation.

Format:

- Client-generated id: `corr_<ulid>`.
- Server fallback id: `{domain}:{entityId}`, for example `gen:{runId}` or `ebcdic:{runId}` when a client did not provide one.

`turnId` lifecycle:

- A chat turn starts when the user sends a message.
- Client creates `turnId` as `turn_<ulid>` and sends it in the chat request.
- Server preserves that turn id for user row, assistant row, tool rows, SSE events, navigation metadata, and workspace events emitted by that turn.
- If missing, server generates `turn_<ulid>` once and returns/emits it in the first streamed event.
- Tool calls inside a turn get separate `toolCallId`s but share the same `turnId`.

Propagation:

- Chat send body includes `correlationId` and `turnId`.
- Tool results persist both in `chat_messages.metadata`.
- Workspace events include `correlationId`; chat-related events include `turnId` in `payload`.
- Direct composition/orchestration mutation requests include `correlationId`; services emit `COMPOSITION_CHANGED` with the same id.
- Revision and lifecycle requests include `correlationId`; services emit version lifecycle events with the same id.
- DQ apply requests include `correlationId`; services emit DQ events with the same id.
- Codegen generate/save requests include `correlationId`; generation run metadata stores `correlationId`.
- Artifact save stores `generated_artifacts.metadata.lastEditCorrelationId`.
- Git attach/branch/PR requests include `correlationId`; Git service persists it to repo/branch/PR audit metadata and workspace events.
- EBCDIC requests include optional `correlationId`; service generates fallback when omitted.

Tests:

- A chat turn persists the same `turnId` across user, assistant, and tool rows.
- Tool navigation workspace events share the chat turn correlation id.
- Codegen run and artifact save events share the initiating correlation id.
- Composition, orchestration, DQ, Git, revision, and lifecycle direct UI actions each carry a fresh top-level correlation id and preserve it across mutation response, workspace event, and any command/audit refresh.
- Polling does not rotate or overwrite correlation id.

### Actor, Correlation, And Idempotency Headers

Canonical source of truth:

- `X-Correlation-Id` is the canonical request header for all mutating APIs and streaming sends that create durable side effects. JSON body `correlationId` remains accepted during migration but must match the header when both are present; mismatch returns `400 CORRELATION_ID_MISMATCH`.
- `Idempotency-Key` is the canonical request header for retryable mutating APIs. JSON body `idempotencyKey` remains accepted during migration but must match the header when both are present; mismatch returns `400 IDEMPOTENCY_KEY_MISMATCH`.
- Actor identity is server-derived from authenticated context when available, using the same resolver pattern as deploy `CallerContext`. Request body fields such as `userId`, `createdBy`, and `actorId` are compatibility hints only during the current dev-auth period.
- If authenticated actor exists, server ignores body actor fields for authority and persists the resolved actor id. If no authenticated actor exists in dev mode, server may use body actor field, then `current-user` fallback, and must mark payload/metadata `actorSource="DEV_FALLBACK"`.
- New frontend code sends headers through `frontend/src/lib/api.ts`; feature components must not hand-roll these headers.

Mutation request cleanup:

- New canonical request DTOs may keep actor fields optional for compatibility, but docs and tests treat `X-Correlation-Id`, `Idempotency-Key`, and server actor resolution as authoritative.
- Compatibility shims that derive idempotency keys must do so deterministically and include the derived key in service calls and workspace events.
- All workspace events persist `actor` from server resolution, not from request body actor hints.

Dev-auth transport and permission derivation:

- Until JWT claims carry tenant roles/permissions, `frontend/src/contexts/auth-context.tsx` persists the `/api/v1/auth/me` identity into localStorage keys `pulse.userId`, `pulse.userRole`, and `pulse.userPermissions` after login/restore, and clears them on logout.
- `frontend/src/lib/api.ts` sends dev-auth headers from those keys on every JSON, raw/SSE, and multipart request: `X-Pulse-User-Id`, `X-Pulse-Roles`, and `X-Pulse-Tenant-Id`. `X-Pulse-Roles` carries the policy role names understood by `ActorResolverService`; if only the legacy user role is available, frontend maps it through the same conservative compatibility table documented by `PulseRole.fromLegacy`.
- The frontend never sends `X-Pulse-Permissions` as an authority header. Permission strings in `pulse.userPermissions` are for UI affordances only.
- Backend permission-string authority for `PulseGuard.requirePermission(...)` is server-derived from `CallerContext.roles`, not from the request body and not from a client-sent permissions header.
- Add `backend/src/main/java/com/pulse/auth/policy/LegacyPermissionCatalog.java` as the single mapping from `CallerContext.roles` to legacy permission strings (`pipeline:read`, `pipeline:write`, `pipeline:deploy:*`, `pipeline:approve`, `producer:write`, `chat:use`, `commands:view`, `admin:users`, `admin:allowlist`). It mirrors the current `/api/v1/auth/me` permission vocabulary but is owned by the backend policy package.
- `AuthController` should use `LegacyPermissionCatalog` when returning `/api/v1/auth/me` permissions so UI affordances and `PulseGuard.requirePermission(...)` cannot drift.
- In dev-auth mode, if `X-Pulse-Roles` is absent, `ActorResolverService` may keep its current permissive dev default for local/test compatibility and must mark `actorSource="DEV_FALLBACK"` in emitted audit/workspace metadata. Denial tests set `X-Pulse-Roles` explicitly.
- Production hardening replaces these headers with JWT/session-derived roles but preserves the same `CallerContext` and `LegacyPermissionCatalog` contract.

Legacy role to policy-role transport table:

| `/auth/me` legacy role | `X-Pulse-Roles` value in dev-auth |
| --- | --- |
| `CITIZEN` | `TENANT_USER` |
| `DATA_ENGINEER` | `TENANT_USER,PIPELINE_DEVELOPER` |
| `DEPLOYER` | `TENANT_USER,DEPLOYMENT_OPERATOR,PULL_REQUEST_APPROVER` |
| `ADMIN` | `TENANT_USER,PIPELINE_DEVELOPER,DEPLOYMENT_OPERATOR,PULL_REQUEST_APPROVER,TENANT_ADMIN` |

`frontend/src/lib/auth-headers.ts` owns this table for dev-auth header construction. A backend test for `PulseRole.fromLegacy` and a frontend unit test for `auth-headers.ts` must use the same fixture values listed above.

### Permission And Capability Matrix

The plan uses the live permission vocabulary exposed by `backend/src/main/java/com/pulse/auth/controller/AuthController.java` until a later auth hardening project intentionally expands it. `backend/src/main/java/com/pulse/config/SecurityConfig.java` currently permits `/api/v1/**`; this plan must not treat that as authorization. Every new canonical endpoint still implements service/controller permission checks and returns structured `403 PULSE_FORBIDDEN` responses.

Backend guard owner:

- Existing `backend/src/main/java/com/pulse/auth/policy/ActorResolverService.java`, `CallerContext.java`, and `AuthorizationPolicyService.java` remain the canonical server-side authority stack. Do not create a parallel authorization service with a different model.
- New controllers call one guard facade before invoking mutation services: `backend/src/main/java/com/pulse/auth/policy/PulseGuard.java`.
- `PulseGuard` is the single concrete guard API for this plan. It resolves `CallerContext` through `ActorResolverService.resolve(surface, tenantId)`, evaluates policy-backed actions through `AuthorizationPolicyService.check(caller, action, ActionContext...)`, evaluates legacy permission-string checks for surfaces without `PulseAction`, and throws `ApiException(403, "PULSE_FORBIDDEN", ...)` on denial.
- `PulseGuard` may use an internal mapper such as `CapabilityDecisionMapper`, but that mapper must wrap `AuthorizationPolicyService` and must not own independent allow/deny rules.
- Controllers must not call `AuthorizationPolicyService` directly after `PulseGuard` lands, except legacy deploy code before migration. The deploy controller pattern is the reference behavior; `PulseGuard` is the normalized implementation surface for new work.
- `DeployController` remains the reference implementation for package/deploy actor resolution, but migrated endpoints replace direct `ResponseStatusException` deny responses with the shared `{code,message,details}` `PULSE_FORBIDDEN` envelope.
- Mutating services re-check critical decisions when invoked by legacy command paths, chat tools, or scheduled/background code.
- Frontend capability DTOs are display affordances only. The backend remains authoritative.

Guard API:

```java
CallerContext requirePolicy(
    CallerSurface surface,
    String tenantId,
    PulseAction action,
    ActionContext target,
    String requiredPermission,
    String targetType,
    String targetId
);

CallerContext requirePermission(
    CallerSurface surface,
    String tenantId,
    String requiredPermission,
    String targetType,
    String targetId
);
```

Call order for every new controller:

1. Parse path/query/body enough to identify tenant, target ids, and requested operation.
2. Resolve target ownership needed for authorization, such as pipeline tenant/domain/version relationship or deployment environment.
3. Call `PulseGuard.requirePolicy(...)` when the operation maps to a `PulseAction`; otherwise call `PulseGuard.requirePermission(...)`.
4. If allowed, pass the returned `CallerContext` to the owning service and persist actor/audit fields from it.
5. Services invoked from chat tools or `CommandService` repeat the same `PulseGuard` call when they can be reached without the canonical controller.

Role/action and permission-string reconciliation:

- `AuthorizationPolicyService` role actions are the enforcement model for deploy-productization actions: `COMMIT`, `PACKAGE_BUILD`, `DEPLOY`, `APPROVE`, `PROMOTE`, `TARGET_CONFIG`, and `SECRET_METADATA`.
- Legacy permission strings from `/api/v1/auth/me` are the frontend display and coarse capability vocabulary until auth is hardened.
- Mapping from plan permissions to policy actions:
  - `pipeline:write` for Git commit-like actions maps to `PulseAction.COMMIT`.
  - `pipeline:write` for package creation maps to `PulseAction.PACKAGE_BUILD`.
  - `pipeline:deploy:{env}` maps to `PulseAction.DEPLOY` with `ActionContext.forTenantAndEnv(tenantId, env)`.
  - `pipeline:approve` maps to `PulseAction.APPROVE`.
  - Deployment promotion actions map to `PulseAction.PROMOTE`.
  - Deployment target configuration maps to `PulseAction.TARGET_CONFIG`.
  - Secret/credential metadata actions map to `PulseAction.SECRET_METADATA`.
- Actions not yet represented in `PulseAction` but introduced by this plan, such as composition edit, version create/lifecycle, story/codegen generation, artifact save, dbt wrapper save, workspace-event read, command search, producer/SOR mutation, and EBCDIC mutation, use `PulseGuard.requirePermission(...)` until `PulseAction` is expanded in a later auth-hardening pass.
- The adapter's deny response includes both `requiredPermission` and, when applicable, `policyAction` and `policyDenyReason`, so UI affordances can explain denials without hiding the underlying role-policy result.
- `ActorResolverService` is the only source of `CallerContext`; request-body `userId`, `actorId`, or `createdBy` never constructs policy identity.

Frontend capability owner:

- `frontend/src/contexts/auth-context.tsx` exposes current permissions from `/api/v1/auth/me`.
- `frontend/src/lib/capabilities.ts` maps permission strings and backend capability DTOs to disabled states.
- Shells and panels do not duplicate permission logic inline; they consume helper results and backend `capabilities`.
- Disabled controls remain visible when useful for discoverability and explain `reasonLabel` through tooltip/help text.

Permission matrix:

| Surface / operation | Required permission | Backend guard owner | UI behavior when denied |
| --- | --- | --- | --- |
| Pipeline summary, version list, version detail, read-only composition/story/codegen/deployability/Git status | `pipeline:read` | Pipeline/version/read controllers via `PulseGuard.requirePermission(...)` | Show read-only or forbidden state; do not fetch child mutation data after 403. |
| Aggregate story generation and cached story read | `pipeline:read` | Story controller/service via `PulseGuard.requirePermission(...)` | Aggregate story action is disabled only when pipeline read is denied. |
| Version story cached read with no metadata write | `pipeline:read` | Story controller/service via `PulseGuard.requirePermission(...)` | Story pane/dialog can show cached fresh/stale story in read-only mode. |
| Version story create/regenerate or any write to `PipelineVersion.metadata.generatedStory` | `pipeline:write` | Story controller/service via `PulseGuard.requirePermission(...)` | Regenerate/create story action disabled; cached read remains available when `pipeline:read` exists. |
| Version create, draft save, lifecycle mutation except approval, composition/orchestration edit, DQ apply, codegen generate, artifact save, dbt wrapper save | `pipeline:write` | Owning controller plus service re-check for command/chat-tool callers | Disable primary action, keep read view available when `pipeline:read` exists. |
| Promote/approve lifecycle stage that represents approval | `pipeline:approve` | Lifecycle endpoint and compatibility `CommandService` path | Show approval action disabled with `MISSING_PERMISSION`. |
| Package creation | `pipeline:write` | Package controller/service | Package action disabled; deploy-existing remains visible if user has deploy permission and eligible package exists. |
| Deploy to dev/int/uat/prod | `pipeline:deploy:dev`, `pipeline:deploy:int`, `pipeline:deploy:uat`, `pipeline:deploy:prod` by target environment | Deploy controller through `PulseGuard.requirePolicy(...)` with `PulseAction.DEPLOY` after migration; legacy deploy keeps existing direct policy call until then | Target visible but disabled with environment-specific missing-permission reason. |
| Deployment target read and eligibility reads, including version/package target lists | `pipeline:read` | Deploy target controller/service | Target selectors render forbidden/unavailable state instead of empty target lists. |
| Deployment target create/update | `pipeline:write` and policy action `TARGET_CONFIG` when migrated | Deploy target controller/service through `PulseGuard.requirePolicy(...)` where possible | Target management forms are read-only or submit-disabled with `MISSING_PERMISSION`. |
| Producer/SOR/connector/dataset mutation | `producer:write` | SOR, connector, dataset controllers | Forms open read-only or hide submit actions. |
| Tenant Git onboarding/status/settings read, including `/onboarding-status`, tenant `GET /git-repo`, and tenant branch read | `pipeline:read` | Git controller/service | Settings Git page shows read-only missing/onboarded state or forbidden state. |
| Tenant Git onboarding/settings mutation, including `/onboard`, tenant `PUT /git-repo`, and tenant branch switch/create | `pipeline:write` plus provider capability | Git controller/service | Tenant Git setup/update/branch controls are disabled with `MISSING_PERMISSION` or provider reason. |
| Pipeline-scoped Git attach/edit/detach, branch switch/create, PR create/status update | `pipeline:write` plus provider capability | Git controller/service | Git tab visible read-only; mutating buttons disabled with `MISSING_PERMISSION` or provider reason. |
| Domain Git attach/edit/detach from pipeline workspace | `producer:write` and valid pipeline/domain relationship | Git controller/service | DOMAIN scope option is disabled unless permission and domain relationship are valid. TENANT scope is read/link-only from pipeline workspace and routes to Settings for mutation. |
| Workspace events read for pipeline/version audit | `pipeline:read` | Workspace events controller | Audit tab shows forbidden state, not empty history. |
| Workspace event writes emitted by product mutations | Same permission as owning mutation | Owning service transaction or documented best-effort emitter | If mutation is denied, no workspace event is emitted except optional security telemetry. |
| Global chat send/read | `chat:use` | Chat controller/service | Chat input disabled; history hidden or forbidden based on endpoint. |
| Pipeline/version chat send/read | `chat:use` and `pipeline:read`; tool calls additionally require target mutation permission | Chat controller plus `ChatToolExecutor` preflight | Assistant can answer read-only when allowed; mutating tools emit blocked-tool SSE. |
| Command log page and command search read | `commands:view` | Command log/search controller | Command search entry points hidden or disabled. |
| Command execution / command action handoff | `commands:view` and target operation permission | Command controller, `CommandService`, and target service re-check | Command appears read-only or blocked with target permission reason. |
| EBCDIC upload/run/profile save unscoped to pipeline | `producer:write` | EBCDIC controller/service | Upload/run/save disabled. |
| EBCDIC pipeline-scoped attach or workspace event emission | `producer:write` and `pipeline:write` when it mutates pipeline state | EBCDIC service plus pipeline service for attachment | Keep analysis read-only if possible; disable pipeline attachment. |
| Admin-only user/allowlist management if surfaced later | `admin:users` or `admin:allowlist` | Admin controllers | Do not add admin UI in this plan without explicit route-level gating. |

Structured 403 contract:

```json
{
  "code": "PULSE_FORBIDDEN",
  "message": "You do not have permission to perform this action.",
  "details": {
    "requiredPermission": "pipeline:write",
    "reasonCode": "MISSING_PERMISSION",
    "targetType": "PIPELINE_VERSION",
    "targetId": "..."
  }
}
```

Capability DTOs:

- Existing or new `capabilities` objects use `canRead`, `canWrite`, `canDeploy`, `canApprove`, `canUseChat`, `canViewCommands`, and surface-specific booleans.
- Disabled reasons use stable codes: `MISSING_PERMISSION`, `READ_ONLY_STAGE`, `NO_VERSION_SELECTED`, `PROVIDER_AUTH_REQUIRED`, `UNSUPPORTED_PROVIDER`, `CONFLICT`, `LOCKED`, `UNKNOWN`.
- Contract tests must prove that denied backend operations return 403 even when the frontend would normally hide the action.

### Feature Flags And Rollout Controls

All risky cutovers in this plan land behind explicit runtime flags. Phases describe implementation order; flags control exposure and rollback.

Flag owners:

- Backend controller owner: add `backend/src/main/java/com/pulse/config/controller/FeatureFlagController.java`.
- Backend service owner: add `backend/src/main/java/com/pulse/config/service/FeatureFlagService.java`.
- Canonical registry owner: add `backend/src/main/java/com/pulse/config/FeatureFlagKey.java`. This enum/list is the source of truth for known flags, default values, rollout stage, and description.
- Backend DTO owners: add `FeatureFlagSnapshotDTO` and `FeatureFlagDTO` under `backend/src/main/java/com/pulse/config/dto/`.
- Backend defaults live under `pulse.features` in `backend/src/main/resources/application.yml`.
- Frontend reads flags through `GET /api/v1/tenants/{tenantId}/features` and caches them in `frontend/src/contexts/feature-flag-context.tsx`.
- `frontend/src/lib/feature-flags.ts` mirrors `FeatureFlagKey` names as TypeScript constants for type safety, but it is not authoritative. If frontend constants and backend registry disagree, backend wins and a frontend test must fail.
- Local development may override frontend defaults with `NEXT_PUBLIC_PULSE_FEATURE_OVERRIDES` containing comma-separated `flag=true|false` entries. Overrides are logged once in development only.
- Production fallback is conservative: unknown flags are false unless the table below says the legacy behavior must remain true.

Feature endpoint:

```http
GET /api/v1/tenants/{tenantId}/features
```

Response:

```ts
type FeatureFlagDTO = {
  key: string;
  enabled: boolean;
  source: "DEFAULT" | "TENANT_OVERRIDE" | "ENV_OVERRIDE";
  rolloutStage: "OFF" | "INTERNAL" | "BETA" | "ON";
  updatedAt: string | null;
};

type FeatureFlagSnapshotDTO = {
  tenantId: string;
  flags: Record<string, FeatureFlagDTO>;
  generatedAt: string;
};
```

Merge precedence:

1. Registry defaults from `FeatureFlagKey`.
2. Static config overrides from `pulse.features` in `application.yml`, allowed only for keys in `FeatureFlagKey`.
3. Environment override `PULSE_FEATURE_<UPPER_SNAKE_FLAG>` parsed by `FeatureFlagService`, allowed only for keys in `FeatureFlagKey`.
4. Tenant override rows, if present, from optional table `tenant_feature_flags(tenant_id, flag_key, enabled, rollout_stage, updated_at, updated_by)`, allowed only for keys in `FeatureFlagKey`.
5. Development-only frontend override `NEXT_PUBLIC_PULSE_FEATURE_OVERRIDES`, applied only in the browser and reported in telemetry as `source="DEV_FRONTEND_OVERRIDE"`.

Tenant override behavior:

- `tenant_feature_flags` is created by the rollout-control migration before feature flag rollout. It is not optional once `FeatureFlagController` lands.
- Tenant rows override env/default values for that tenant.
- Unknown flag keys from tenant rows, `application.yml`, or environment overrides are ignored, logged, and surfaced in a backend health/test warning; they are never returned by `GET /features`.
- `GET /features` requires tenant membership; on denial it returns `403 PULSE_FORBIDDEN`.

Flag table:

| Flag | Default | Controls | Enable criteria | Rollback behavior |
| --- | --- | --- | --- | --- |
| `ui.tokens.v1` | true after Phase 0 | Semantic tokens and skin attributes | Token CSS merged and visual smoke passes | Revert to previous CSS import if visual regression is severe. |
| `ui.shell.groups` | false | `(app)`, `(assistant)`, `(workspace)` layouts and `AuthenticatedProviders` ownership | Auth, tenant, shortcuts, toasts, and global chat smoke pass | Route back to existing app layout; providers remain compatible. |
| `ui.pipeline.routeStateController` | false | `PipelineRouteStateController` and `rev` canonicalization | Version endpoint contract tests and stale-link tests pass | Classic route falls back to legacy page state. |
| `ui.workspace.ideRoute` | false | `/workspace/pipelines/{pipelineId}` public IDE route and redirect from `?view=ide` | Route state, shell, version gate, and no-version states pass | Hide IDE entry points and keep classic route live. |
| `chat.scopedSessions` | false | Scoped chat schema/API and `ChatProvider.statesByScopeKey` | Migration, history, SSE, and restore-race tests pass | Providers use pre-split latest/create compatibility routes. |
| `events.workspace.read` | false | Workspace events read API and audit panels | Migration and list/paging tests pass | Audit panels show legacy/empty unavailable state. |
| `events.workspace.emitters` | false | Product mutation workspace-event emitters | Read API exists, emitter tests pass, dual command-log emission proven | Stop new emits; existing events remain readable. |
| `commands.palette` | false | Command search UI, pending action handoff, command action host entry points | `CommandActionHost`, permissions, and target preflight pass | Hide palette; pending actions expire safely. |
| `git.ide` | false | IDE Git tab, provider sync, branch/PR actions | Permission matrix, provider-auth states, and workspace-event contracts pass | Hide IDE tab; settings Git remains available. |
| `deploy.ide` | false | IDE package/deploy dialogs and target selector | Package/deploy idempotency, target permission, and deployability tests pass | Hide IDE deploy commands; existing deploy panel remains available. |
| `ebcdic.workspace` | false | Workspace EBCDIC scope, events, and profile flows | Migration, upload/run/save tests, live-region QA pass | Hide workspace EBCDIC entry; standalone EBCDIC remains available. |
| `ui.ide.skin` | false | IDE density/accent skin beyond route availability | Visual QA passes for IDE shell and major panels | Route can remain with app skin while polish is repaired. |

Rollout rules:

- A feature may be merged with flag default false and tests enabled.
- A flag may default true only after its enable criteria and final-gate subset pass in CI/local verification.
- New UI entry points check both route availability and capability state; permission denial is not a feature-flag substitute.
- Rollback must not require database rollback. Migrations are forward-compatible; disabled flags hide or stop new writes while preserving readable data.
- Each flag emits telemetry when evaluated for a surfaced UI entry point.

### Telemetry Contract

Telemetry is used for rollout proof, debugging, and UX adoption. It is not an authorization, audit, or analytics replacement for workspace events.

Owners:

- Frontend helper: `frontend/src/lib/telemetry.ts`.
- Backend controller: add `backend/src/main/java/com/pulse/telemetry/controller/TelemetryController.java`.
- Backend service: add `backend/src/main/java/com/pulse/telemetry/service/TelemetryService.java`.
- Backend repository/model: add `TelemetryEvent` and `TelemetryEventRepository` under `backend/src/main/java/com/pulse/telemetry/`.
- Backend endpoint: `POST /api/v1/tenants/{tenantId}/telemetry/events`.
- Backend DTO: `TelemetryEventDTO { eventName, correlationId?, turnId?, route, surface, entityType?, entityId?, pipelineId?, versionId?, featureFlag?, result, reasonCode?, durationMs?, metadata }`.
- Server validates tenant and actor, truncates oversized metadata, and writes best-effort. Telemetry write failures never fail product actions.
- Telemetry must not include chat content, code artifact content, uploaded file contents, secrets, tokens, or provider credentials.

Top-level versus metadata rule:

- Top-level fields are the queryable dimensions used for rollout dashboards and support triage: `eventName`, `tenantId`, `actorId`, `correlationId`, `turnId`, `route`, `surface`, `entityType`, `entityId`, `pipelineId`, `versionId`, `featureFlag`, `result`, `reasonCode`, `durationMs`, and `createdAt`.
- Event-specific properties not listed as top-level are stored inside `metadata`.
- If an event property corresponds to a top-level field, it must be sent top-level and must not be duplicated only in `metadata`.
- Server validation is event-name aware: `TelemetryService` owns a registry of required top-level fields and required metadata keys for each event in the table below.
- Unknown metadata keys are allowed after redaction/truncation; unknown top-level fields are rejected by DTO binding/tests.

Telemetry persistence and response:

- Migration: create the first rollout-control Flyway file after the physical repo head at implementation time. Resolve the head with `find backend/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*.sql' | sed 's#.*/##' | sort -V | tail -1`; at this plan revision that command returns `V112__tenant_git_identity_scaffold.sql`, so the expected filename is `backend/src/main/resources/db/migration/V113__rollout_control_plane.sql`.
- `V113__rollout_control_plane.sql` creates both rollout tables before any gated UI cutover: `tenant_feature_flags(tenant_id, flag_key, enabled, rollout_stage, updated_at, updated_by)` and `telemetry_events(id, tenant_id, actor_id, event_name, correlation_id, turn_id, route, surface, entity_type, entity_id, pipeline_id, version_id, feature_flag, result, reason_code, duration_ms, metadata_json, created_at)`.
- The same migration also adds command/plan bridge columns required before compatibility mutation cutover: `command_log.correlation_id`, `command_log.canonical_idempotency_key`, and `command_log.canonical_event_id`, plus indexes on `(tenant_id, correlation_id)` and `(tenant_id, canonical_idempotency_key)` where non-null.
- Indexes: `(tenant_id, created_at desc, id desc)`, `(tenant_id, correlation_id, created_at desc)`, `(tenant_id, event_name, created_at desc)`, `(tenant_id, pipeline_id, created_at desc)`, and `(tenant_id, version_id, created_at desc)`.
- `POST /telemetry/events` returns `202 Accepted` with `{ "accepted": true, "eventId": "..." }` after validation and enqueue/persist success.
- If persistence is temporarily unavailable, the controller still returns `202 Accepted` with `{ "accepted": false, "eventId": null, "reasonCode": "TELEMETRY_DROPPED" }` and logs server-side. Product flows must not fail because telemetry dropped.
- Invalid DTOs return structured `400 TELEMETRY_INVALID` only for direct telemetry calls; callers must catch and suppress telemetry failures when telemetry is side-effecting another UX flow.
- Metadata is capped at 8 KB serialized JSON after redaction; larger payloads are truncated with `metadata.truncated=true`.
- Retention is operational, not product audit: default 30 days unless `pulse.telemetry.retentionDays` overrides it.

Required event names:

| Event | Required top-level fields | Required metadata keys |
| --- | --- |
| `feature_flag_evaluated` | `featureFlag`, `surface`, `result` | `defaulted` |
| `route_rev_canonicalized` | `pipelineId`, `result` | `fromRev`, `toVersionId` |
| `route_rev_rejected` | `pipelineId`, `reasonCode` | none |
| `route_focus_restored` | `route`, `surface`, `result` | none |
| `version_gate_blocked` | `pipelineId`, `surface`, `reasonCode` | none |
| `scoped_chat_restore` | `surface`, `result`, `durationMs` | `scope` |
| `chat_sse_protocol_violation` | `entityType="CHAT_SESSION"`, `entityId`, `reasonCode` | none |
| `chat_tool_preflight_blocked` | `reasonCode` | `toolName`, optional `requiredPermission` |
| `workspace_event_emit_result` | `entityType="WORKSPACE_EVENT"`, `result` | `eventType`, `scope` |
| `artifact_save_conflict` | `entityType="ARTIFACT"`, `entityId`, `versionId`, `reasonCode` | none |
| `provider_sync_result` | `result`, `durationMs` | `providerType`, `operation` |
| `git_action_result` | `result` | `operation`, `attachmentScope` |
| `package_create_result` | `versionId`, `result`, `durationMs` | `packageType` |
| `deploy_submit_result` | `entityType="PACKAGE"`, `entityId`, `result`, `durationMs` | `targetId`, `environment` |
| `pending_command_action_result` | `result` | `actionType`, `ageMs` |
| `command_search_result` | `result` | `queryLength`, `resultCount`, optional `selectedActionType` |
| `ebcdic_run_result` | `entityType="EBCDIC_SESSION"`, `entityId`, `result`, `durationMs` | none |
| `accessibility_escape_close` | `surface`, `result` | none |

Instrumentation rules:

- Every event includes `correlationId` when tied to a visible user action.
- Events emitted from chat turns include `turnId`.
- Failure events use the same stable `reasonCode` values as structured errors or capability DTOs.
- `TelemetryService` rejects missing required top-level fields or required metadata keys with `400 TELEMETRY_INVALID` for direct calls; product-flow telemetry callers catch and suppress that failure after logging in development.
- UI catch blocks emit telemetry before showing toast when the failure is part of a required event above.
- Contract tests cover event DTO validation and PII/content redaction. Frontend tests cover at least one success and one failure emission per major surface.

### Accessibility Contract

Accessibility is part of the executable plan, not Phase 6 cleanup only.

Global shell rules:

- `AppChrome`, `AssistantShell`, and `WorkspaceShell` each expose one skip link to the primary content region.
- Route changes move focus to the route `<h1>` or the first meaningful panel heading after data loads. Canonical `rev` replacement must not double-announce the page.
- Top-level landmarks use `header`, `nav`, `main`, `aside`, and `footer` semantics consistently. Nested panel regions use labelled `section`/`region`.
- Icon-only buttons use accessible names and tooltips; tooltip text is not the only accessible name.
- All focus rings are visible in both skins and meet contrast requirements.

Dialogs, drawers, and overlays:

- Modal dialogs trap focus, close on Escape unless a destructive operation is in progress, and restore focus to the invoking control.
- Non-modal drawers expose labelled complementary regions and do not trap focus unless opened as modal on small screens.
- `PlanPreviewDialog`, Git dialogs, deploy/package dialogs, EBCDIC upload/profile dialogs, and command palette all share the same focus-return helper.
- Toasts do not steal focus. Blocking errors are also rendered inline near the failed control.

Command palette keyboard model:

- Trigger opens with focus in search input.
- Arrow Up/Down moves active option; Enter activates; Escape closes and returns focus; Tab exits only when the palette is closed.
- Results use `role="listbox"`/`role="option"` or a combobox pattern consistently; active item is exposed with `aria-activedescendant`.
- Group labels are announced without becoming selectable.
- Disabled commands remain focusable only when they explain the disabled reason; otherwise they are skipped.

Streaming and long-running status:

- Chat streaming exposes a polite live region for assistant state changes and an assertive live region for failed sends.
- EBCDIC upload/run/profile status uses live regions for queued, running, completed, failed, and validation-blocked states.
- Codegen, package, deploy, provider sync, and Git operations expose determinate or indeterminate progress with accessible names.
- SSE protocol violations are announced as recoverable errors with retry actions when possible.

Graph, editor, and dense IDE surfaces:

- Composition graph has a keyboard path to select nodes, inspect node details, and open configure actions without pointer input.
- Graph canvas provides a non-canvas list/table fallback of nodes and edges for screen readers.
- Code editor panels expose file tree, active artifact, dirty state, save conflict, and validation messages outside Monaco-only affordances.
- Workspace tabs, side panels, bottom panels, and splitters are keyboard reachable; resizable splitters expose orientation, value, and min/max where implemented.
- No essential action relies on hover-only controls.

Acceptance:

- Add accessibility tests for command palette, dialogs/drawers, route focus, chat streaming live region, EBCDIC status live region, and graph fallback.
- Manual QA covers keyboard-only flows for classic pipeline, IDE version switch, chat send, story generation, code save conflict, deploy, Git action, command search, and EBCDIC run.
- Visual QA includes focus-visible states in app and IDE skins.

### Structured Errors

All new APIs that return `{code,message,details}` use one frontend error contract.

Backend structured-error owner:

- `backend/src/main/java/com/pulse/common/exception/GlobalExceptionHandler.java` becomes the enforcement point for structured API errors.
- Add `backend/src/main/java/com/pulse/common/exception/ApiException.java` with fields `HttpStatus status`, `String code`, `String message`, and `Map<String,Object> details`.
- Add `backend/src/main/java/com/pulse/common/exception/ApiErrorBody.java` as the serialized `{code,message,details}` DTO.
- `GlobalExceptionHandler` maps `ApiException` to `ResponseEntity<ApiErrorBody>`.
- New and migrated controllers throw `ApiException` for expected 4xx/409 cases instead of returning ad hoc `{error}`, `ProblemDetail`, or plain strings.
- `ResourceNotFoundException` and `ResponseStatusException` remain supported for legacy endpoints, but endpoints named in Required consumers must convert expected failures to `ApiException`.
- Story code in `backend/src/main/java/com/pulse/pipeline/controller/PipelineController.java` must stop returning `{error}` for the migrated story routes.

`frontend/src/lib/api.ts` must expose:

```ts
export type ApiErrorBody = {
  code: string;
  message: string;
  details?: Record<string, unknown>;
};

export class ApiError extends Error {
  status: number;
  body: ApiErrorBody;
}
```

Rules:

- `api.ts` parses `{code,message,details}` responses and throws `ApiError`.
- If a response has `{detail}`, wrap it as `ApiError` with `code: "API_ERROR"` and `message: detail`.
- If parsing fails, use response text or HTTP status text as message.
- Flows that use raw `fetch` for SSE still call the shared `parseApiErrorResponse(response)` helper before creating UI error messages.
- UI code branches on `error.body.code`, never by string-matching message text.
- `parseApiErrorResponse(response)` is the only HTTP error parser for chat and non-chat APIs.
- `frontend/src/components/chat/chat-stream.ts` may normalize SSE `error` events into `ParsedChatError`, but it must reuse the same parse rules and type shape from `ApiErrorBody`. It must not introduce a second response parser.
- Preserve the Git-auth exception behavior: an HTTP 401 is normalized to `ApiError` with `code: "GIT_AUTH_FAILED"` and must not remove `pulse_token`, clear auth state, or dispatch global logout/session-expired behavior when any of these exact conditions are true: parsed `{code}` is `GIT_AUTH_FAILED`; parsed `{message}` contains case-insensitive `git authentication failed`; parsed `{error}` contains case-insensitive `git authentication failed`; plain response text contains case-insensitive `git authentication failed`.
- Only application-auth 401 responses with no Git-auth exception clear `pulse_token` and trigger the existing global auth teardown path.

Transport parity for raw fetch/SSE:

- `frontend/src/lib/api.ts` remains the only owner of request auth/tenant/session behavior.
- Add and use a helper such as `buildApiRequestInit(options)` or `apiFetchRaw(input, init)` for streaming and other raw-fetch callers that cannot use JSON helpers.
- Raw fetch/SSE callers must send the same headers as normal API calls: `Authorization: Bearer <pulse_token>` when present, `X-Pulse-Tenant-Id: <tenantId>` when tenant context is available, and JSON `Content-Type`/`Accept` where applicable. SSE send uses `Accept: text/event-stream`.
- Raw fetch/SSE callers must reuse the same application-auth 401 handling and Git-auth exception behavior as normal API calls.
- Raw fetch/SSE callers must treat `204 No Content` consistently with `api.ts`: no JSON parse attempt, null/empty result by endpoint contract, and no false error toast.
- `chat-stream.ts`, global/pipeline/workspace chat controllers, EBCDIC stream consumers, and any future command/deploy streams are forbidden from manually reading `localStorage.pulse_token`, hand-building tenant headers, or implementing their own 401/session-expired behavior.
- Tests cover that chat SSE and EBCDIC SSE include auth/tenant headers, parse non-OK responses through `parseApiErrorResponse`, preserve Git-auth 401 exceptions, and do not JSON-parse 204 responses.

Multipart/form transport parity:

- `frontend/src/lib/api.ts` keeps multipart/file upload behavior in the same transport family as JSON and raw fetch. Existing or new `api.postForm`/`api.uploadForm` helpers must use the same base URL, `Authorization`, `X-Pulse-Tenant-Id`, application-auth 401 handling, Git-auth exception behavior, 204 handling, and `parseApiErrorResponse(response)` path.
- Multipart helpers must not set `Content-Type` manually; the browser owns the `multipart/form-data` boundary. They may set `Accept: application/json`.
- EBCDIC copybook/data uploads must use the shared multipart helper. Upload components and contexts must not call raw `fetch` directly for file upload unless they delegate request init and error handling to `api.ts`.
- Multipart non-OK responses normalize to `ApiError` with `{code,message,details}` exactly like JSON/SSE non-OK responses. Upload validation errors surface parsed `code` values such as `EBCDIC_UPLOAD_INVALID`, `EBCDIC_SCOPE_INVALID`, or `EBCDIC_SESSION_NOT_FOUND`.
- Tests cover EBCDIC upload success, structured 4xx upload error, application-auth 401 logout behavior, Git-auth 401 exception preservation if a Git-backed upload/import later reuses multipart, and 204 no-content handling for any multipart endpoint that returns no body.

Required consumers:

- Version validation and canonicalization.
- Chat send/history/session APIs.
- Aggregate and version story APIs.
- Codegen save guard and run APIs.
- Deploy/package/deployability APIs.
- Git APIs.
- Workspace events.
- EBCDIC profile save/latest-profile-run.
- Command search.

Tests:

- `{code,message,details}` is preserved.
- `{detail}` is normalized.
- Plain text fallback is normalized.
- Chat SSE non-OK path uses the same parser.

### Canonical Route Map

Canonical pipeline URLs:

- Bare compatibility: `/pipelines/{pipelineId}` immediately replaces to `/pipelines/{pipelineId}?view=classic`.
- Classic: `/pipelines/{pipelineId}?view=classic&rev={versionId?}`.
- IDE: `/workspace/pipelines/{pipelineId}?rev={versionId}&tab={tab}&side={side}&bottom={bottom}&activity={activity}#{anchor?}`.
- Temporary compatibility redirect: `/pipelines/{pipelineId}?view=ide...` redirects to `/workspace/pipelines/{pipelineId}?...`.

Allowed IDE query values:

- `tab`: `composition`, `code`, `dbt`, `orchestration`, `story`, `deployability`, `git`.
- `side`: `assistant`, `inspector`, `none`.
- `bottom`: `problems`, `runs`, `events`, `diffs`, `audit`, `none`.
- `activity`: `explorer`, `composition`, `code`, `assistant`, `deploy`, `history`.

Classic canonicalization is intentionally minimal:

- Classic URLs preserve only `view=classic` and optional `rev=<versionId>`.
- Classic drops every other query key, including `tab`, `side`, `bottom`, `activity`, and any future or unknown key.
- Classic strips all hash anchors.
- No hash is meaningful in classic in this plan.
- IDE exclusively owns hash anchors and IDE workspace keys.
- Classic batches query cleanup and version canonicalization into one `router.replace` whenever possible.
- Classic one-shot replace guard key: `pipelineId:classic:incomingRevOrMissing:latestVersionIdOrNone`.

Valid IDE hash anchors:

- `#node:<id>`
- `#artifact:<id>`
- `#story:<storyId>`

Invalid hash anchors are ignored with a small toast and never trigger active/latest fallback.

Run and event selection are not hash anchors in the canonical IDE route. They are encoded only as bottom-panel query state: `bottom=runs&runKind=...&runId=...` and `bottom=events&eventId=...`. During migration, an incoming legacy `#run:codegen:<id>`, `#run:deployment:<id>`, or `#event:<id>` is canonicalized once into the matching query keys and the hash is stripped. Query keys win over legacy hash if both are present.

IDE hash anchor resolution:

| Anchor | Required tab/panel changes | Selected entity owner | Highlight and scroll behavior | Not-found behavior |
| --- | --- | --- | --- | --- |
| `#node:<id>` | Set `tab=composition`; preserve side/bottom unless current side is `none`, then use `side=inspector`. | `composition-panel.tsx` selection state for the selected node. | Select node, center graph on node, flash focus ring once, and open inspector if available. | Keep route, clear node selection, toast `Node not found in this version`. |
| `#artifact:<id>` | Set `tab=code`; preserve side; set `bottom=diffs` only when a diff is already active for that artifact. | Code editor artifact tree/editor state. | Open artifact, focus editor header, and highlight artifact tree row. | Keep route, clear artifact selection, toast `Artifact not found for this version`. |
| `#story:<storyId>` | Set `tab=story`, `activity=assistant`; preserve side/bottom. | Story pane state. | Load matching story, scroll top of story pane, flash story header. | Keep route, render story not-found empty state, toast `Story not found`. |

`WorkspaceShellProvider` parses and validates anchor shape. The owning tab/panel resolves the id after its data load and reports found/not-found back to the provider so route state and toasts stay centralized.

### Version Endpoints And Selection

Pipeline aggregate detail:

```http
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}
```

`PipelineRouteStateController` owns this request for both classic and IDE routes. Classic and IDE pages consume the controller state instead of fetching pipeline metadata independently.

Pipeline list summary:

```http
GET /api/v1/tenants/{tenantId}/pipelines/summary?domainId=&status=&q=&limit=&cursor=
```

- Owner: existing pipeline controller/service path that backs `frontend/src/app/(app)/pipelines/page.tsx` after route-group migration.
- This endpoint is the canonical data source for dashboard/home pipeline work queue cards and the pipelines list. Those surfaces must not issue one version-list request per pipeline for row-level latest/open-IDE state.
- Query semantics: `limit` default 50 and max 100; `cursor` is base64url JSON `{ "updatedAt": "ISO-8601", "id": "..." }`; default sort is `updatedAt DESC, id DESC`; `status` may appear multiple times or as comma-separated values; `domainId` filters exact domain; `q` searches case-insensitive name/description.
- Response is `{ "items": PipelineSummaryDTO[], "nextCursor": null }`.

`PipelineSummaryDTO` minimum fields:

```ts
type PipelineSummaryDTO = {
  id: string;
  tenantId: string;
  domainId: string | null;
  name: string;
  description: string | null;
  status: string;
  activeVersionId: string | null;
  latestVersionId: string | null;
  versionCount: number;
  hasVersions: boolean;
  updatedAt: string;
};
```

- `hasVersions` is `versionCount > 0`.
- `latestVersionId` is computed by the same ordering as the version page endpoint.
- Pipeline list and dashboard `Open IDE` CTA is enabled only when `hasVersions=true`; it routes to `/workspace/pipelines/{pipelineId}?rev=<latestVersionId>` when `latestVersionId` exists and otherwise to `/workspace/pipelines/{pipelineId}` for no-version empty state.
- `activeVersionId` remains display-only and is never used as mutation authority.

Pipeline summary status:

- `Pipeline.status` is not a new persisted column in this plan. The `status` field in `PipelineSummaryDTO` and `PipelineDTO` is a DTO presentation value computed by the pipeline summary service.
- Source of truth: version count plus the latest/active version lifecycle stages already persisted on `PipelineVersion`; the aggregate `Pipeline` row does not own lifecycle state.
- Allowed values: `NO_VERSIONS`, `DRAFTING`, `IN_DEVELOPMENT`, `READY_FOR_RELEASE`, `RELEASED`, `PRODUCTION`.
- Computation:
  - `NO_VERSIONS` when `versionCount=0`.
  - `DRAFTING` when latest version stage is `ENGINEERING`.
  - `IN_DEVELOPMENT` when latest version stage is `DEV_DEPLOYED`, `DEV_VALIDATED`, or `INTEGRATION_QUALIFIED`.
  - `READY_FOR_RELEASE` when latest version stage is `PUBLISHED`.
  - `RELEASED` when latest version stage is `UAT_DEPLOYED`.
  - `PRODUCTION` when latest version stage is `PRODUCTION`.
- If latest version is unavailable but `activeVersionId` points to a version, use the active version stage for display only and include `metadata.statusSource="activeVersionFallback"`.
- `status` filtering on `/pipelines/summary` filters this computed DTO status. The service may implement it with SQL projection or post-query filtering as long as cursor semantics remain stable.
- Aggregate story generation must not infer narrative business status from this DTO status. The status exists for list/dashboard filtering and chips only.

`PipelineDTO` minimum fields:

```json
{
  "id": "...",
  "tenantId": "...",
  "domainId": "...",
  "name": "...",
  "description": "...",
  "status": "...",
  "activeVersionId": "...",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601",
  "metadata": {}
}
```

Nullable fields return JSON null, never omitted, except `metadata` defaults to `{}`.

Pipeline validation errors:

```json
{
  "code": "PIPELINE_NOT_FOUND",
  "message": "Pipeline not found",
  "details": {
    "pipelineId": "..."
  }
}
```

```json
{
  "code": "PIPELINE_FORBIDDEN",
  "message": "Not allowed to access this pipeline",
  "details": {
    "pipelineId": "..."
  }
}
```

The endpoint returns `404 PIPELINE_NOT_FOUND` when the pipeline does not exist in the tenant and `403 PIPELINE_FORBIDDEN` when the tenant/user lacks access. It must use the shared `{code,message,details}` error envelope so `api.ts` and `PipelineRouteStateController` can branch without string parsing.

Version list:

```http
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/page?limit=100&cursor=&sort=createdAtDesc
```

Rules:

- `limit` default 100, max 100.
- `sort` supports `createdAtDesc` in Phase 1.
- `latestVersionId` is computed across all versions by `createdAt DESC, id DESC`, not just the current page.
- `cursor` is base64url JSON `{ "createdAt": "ISO-8601", "id": "..." }`.
- Page order is `createdAt DESC, id DESC`.
- Cursor predicate is `(createdAt < cursor.createdAt) OR (createdAt = cursor.createdAt AND id < cursor.id)`.
- `nextCursor` is derived from the last item in the returned page when more rows exist; otherwise null.

Compatibility:

- The current array-returning route must not be broken in place.
- `GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions` continues returning the legacy `PipelineVersion[]` shape during migration.
- `GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/page` is the canonical paginated envelope endpoint.
- Existing consumers that expect `PipelineVersion[]` continue using the legacy array response until they are migrated.
- `PipelineRouteStateController` uses the paginated envelope endpoint.
- Once all consumers are migrated and tests prove no array-shape callers remain, the legacy shape can be removed in a separate compatibility cleanup.

Response:

```json
{
  "items": ["PipelineVersionDTO"],
  "nextCursor": null,
  "latestVersionId": "01...",
  "activeVersionId": "01..."
}
```

Direct validation/detail:

```http
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}
```

`PipelineVersionDTO`:

```json
{
  "id": "...",
  "pipelineId": "...",
  "revision": 3,
  "lifecycleStage": "ENGINEERING",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601",
  "createdBy": "...",
  "changeSummary": "...",
  "isActive": false,
  "commitHash": "...",
  "slaConfig": {},
  "scheduleCron": "...",
  "catchupEnabled": false,
  "maxActiveRuns": 1,
  "dependsOnPast": false,
  "dqReadinessScore": 80,
  "metadata": {}
}
```

Nullable fields return JSON null, never omitted, except `metadata` defaults to `{}`.

Errors:

```json
{
  "code": "VERSION_NOT_FOUND",
  "message": "Version not found for this pipeline",
  "details": {
    "pipelineId": "...",
    "versionId": "..."
  }
}
```

```json
{
  "code": "VERSION_FORBIDDEN",
  "message": "Not allowed to access this version",
  "details": {
    "pipelineId": "...",
    "versionId": "..."
  }
}
```

IDE canonicalization order:

1. Validate pipeline.
2. Fetch version list.
3. If `rev` is present, call direct validation even when it appears in the first page.
4. Missing `rev` plus latest exists: replace once with `rev=<latestVersionId>` and default IDE keys.
5. Missing `rev` and no latest: render no-version empty state and disable mutation controls.
6. Invalid `rev` plus latest exists: replace once to latest and toast stale link.
7. Invalid `rev` and no latest: render no-version empty state.
8. Direct validation 403: access-denied, no latest fallback.
9. Valid off-page `rev`: pin the full direct-validation DTO as `selectedVersion`.
10. One-shot replace guard key: `pipelineId:view:incomingRevOrMissing:latestVersionIdOrNone`.

IDE query canonicalization:

- `WorkspaceShellProvider` owns IDE workspace-key canonicalization for `tab`, `side`, `bottom`, `activity`, and hash.
- Missing `tab`, `side`, `bottom`, or `activity` is replaced once by `WorkspaceShellProvider` with the default values.
- Invalid `tab`, `side`, `bottom`, or `activity` is replaced once by `WorkspaceShellProvider` with the default value for that key.
- Unknown extra query keys are dropped from IDE URLs by `WorkspaceShellProvider` unless explicitly added to this plan later.
- `PipelineRouteStateController` owns only `rev` canonicalization and must not rewrite workspace keys.
- Replacement ordering is deterministic: `PipelineRouteStateController` first resolves/replaces `rev`; after the route has a stable `selectedVersionId` or no-version state, `WorkspaceShellProvider` normalizes workspace keys. Each owner has its own one-shot replace guard.
- If a hash anchor is invalid, `WorkspaceShellProvider` strips the hash and shows a toast; it does not replace `rev`.
- If a hash anchor is valid but points to a resource that cannot be found after data load, `WorkspaceShellProvider` keeps the route, clears the selection, and shows a not-found toast.
- Hash anchors are never used as authority for version selection.

Off-page selected version behavior:

- When direct validation succeeds for a version not present in the first page, store it as `pinnedSelectedVersion`.
- Version selector renders a top group labelled `Selected version` containing the pinned version.
- The normal paged list renders below under `Recent versions`.
- If the user pages until the selected version appears in the normal list, the selector de-duplicates it and may remove the pinned duplicate.
- Version history uses `nextCursor` to load older pages.
- Version switch from either pinned group or paged list uses the same `router.push` path and preserves current workspace query state.
- Mutating panels use `selectedVersion`, not the paged list row, so off-page selection remains fully functional.

Classic behavior:

- Classic may include `rev`, and every versioned classic panel receives an explicit `selectedVersionId`.
- Classic validates the pipeline before version work.
- Classic uses the paginated version envelope to read `latestVersionId` and uses direct version validation for any incoming `rev`, even if that version appears on the first page.
- Missing `rev` plus latest exists: replace once to `/pipelines/{pipelineId}?view=classic&rev=<latestVersionId>`.
- Missing `rev` and no latest: render aggregate panels, set `selectedVersionId` to null, show no-version state for versioned panels, and disable versioned mutation controls.
- Valid `rev`: set `selectedVersionId` to the validated version id.
- Valid off-page `rev`: pin the direct-validation DTO as `selectedVersion` with the same selected-version affordance used by the IDE version selector.
- Invalid `rev` plus latest exists: replace once to latest and show stale-link feedback.
- Invalid `rev` and no latest: clear the invalid rev, render aggregate panels, set `selectedVersionId` to null, and disable versioned panels.
- Direct validation 403: show access denied for that version and do not fall back to latest.
- Classic never treats active/latest as mutation authority. `latestVersionId` may only be used for route canonicalization, after which mutation authority is the explicit `selectedVersionId`.

### Existing Classic Versioned Panels

Phase 1 keeps current classic panels for continuity, but every versioned panel must receive explicit `selectedVersionId` from `PipelineRouteStateController`.

Panels include:

- Composition.
- Code editor/codegen.
- Orchestration.
- Deploy/versioned deployability.
- Versioned story.
- Any Git/deploy UI that mutates a versioned package or artifact.
- DQ score/readiness displays and any DQ mutation.
- Lifecycle stage transitions.
- Revision creation and version history selection.
- Any `DqScoreBadge` or readiness badge tied to a version.
- Pipeline chat steering from the classic header.

If a panel cannot be migrated to explicit `selectedVersionId` in Phase 1, render it read-only or disabled with an Open IDE/select-version CTA. It must not fall back to active/latest.

Classic route authority rules:

- `frontend/src/app/(app)/pipelines/[pipelineId]/pipeline-classic-page.tsx` receives all version state from `PipelineRouteStateController`.
- Revision creation uses explicit base `selectedVersionId` when it is version-derived; if no selected version exists, the action is disabled.
- Lifecycle transitions mutate only `selectedVersionId` and are disabled on no-version, invalid-rev, canonicalizing, or version-forbidden states.
- DQ score/readiness reads use `selectedVersionId`; aggregate fallback is display-only and must be labelled aggregate.
- Pipeline chat steering in classic remains PIPELINE aggregate and must not smuggle `selectedVersionId` into tool authority until the IDE assistant is enabled.

### Version Mutation Contracts

Primary owners:

- Backend controller: `backend/src/main/java/com/pulse/pipeline/controller/PipelineController.java`.
- Backend service: `backend/src/main/java/com/pulse/pipeline/service/PipelineService.java`.
- Frontend route state: `frontend/src/components/pipeline/pipeline-route-state-controller.tsx`.
- Frontend version UI owners: `frontend/src/components/pipeline/version-selector.tsx` for the compact top-bar selector and `frontend/src/components/pipeline/version-history-rail.tsx` for the left history rail. Both consume `PipelineRouteStateContext`; neither fetches versions independently.

Canonical endpoints:

```http
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions
PATCH /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/lifecycle
```

Compatibility:

- Existing unscoped or legacy revision/lifecycle routes remain shims only during migration.
- Shims derive tenant/pipeline/version from persisted rows, apply the same checks, and return the same structured errors.
- New IDE and migrated classic UI call only the tenant-scoped canonical endpoints.

Existing command/plan lane integration:

- Current revision/lifecycle flows driven by `CommandService`, `PlanService`, and `PlanPreviewDialog` are preserved as preview/orchestration surfaces during migration, not as separate mutation authorities.
- `PlanPreviewDialog` may continue to collect confirmation and show diff/plan detail, but its final submit calls the canonical tenant-scoped version create or lifecycle endpoint.
- `CommandService` command handlers that currently mutate pipeline versions become compatibility callers into the same `PipelineService` methods used by the canonical endpoints. They must pass resolved actor, correlation id, and idempotency key into those service methods.
- `PipelineService` owns the actual version create/lifecycle transaction boundary, stage validation, idempotency lookup, and `VERSION_CREATED` / `VERSION_LIFECYCLE_CHANGED` workspace-event write.
- Once classic and IDE UI no longer call command-driven mutation endpoints directly and command logs show zero direct mutation usage for the migration window, the command mutation routes can be retired or kept read-only as command history.

Command/plan correlation and idempotency bridge:

- Add `backend/src/main/java/com/pulse/command/model/CommandExecutionContext.java` with `tenantId`, `actorId`, `planId`, `commandId`, `correlationId`, `commandIdempotencyKey`, and `canonicalIdempotencyKey`.
- Extend `CommandLog` with nullable columns `correlation_id`, `canonical_idempotency_key`, and `canonical_event_id`; keep existing `idempotency_key` as the command-log replay key.
- `PlanService.createPreview(...)` accepts or creates a stable `correlationId` and stores it in `Plan.previewData.correlationId`.
- `PlanPreviewDialog` creates one correlation id when the preview opens, passes it through preview/apply requests, and uses the same id for any final canonical mutation or compatibility command execution.
- `PlanService.apply(...)` accepts an apply-level idempotency key from the UI. If absent in legacy callers, it derives `plan:apply:{planId}:{correlationId}`. Reapplying the same plan with the same key returns the previous applied/failed command list instead of creating new commands.
- `CommandService.execute(...)` gains an overload accepting `CommandExecutionContext`. Legacy callers use an adapter that derives a deterministic command key `command:{tenantId}:{planIdOrNone}:{commandType}:{aggregateId}:{correlationId}` instead of the current random UUID key.
- For command paths that delegate to canonical version create/lifecycle, `canonicalIdempotencyKey` is derived from the canonical formula, not from the command id: `version:create:{pipelineId}:{baseVersionId}:{correlationId}`, `version:seed:{pipelineId}:{correlationId}`, or `version:lifecycle:{versionId}:{targetStage}:{correlationId}`.
- Command handlers pass `CommandExecutionContext.canonicalIdempotencyKey` and `correlationId` into `PipelineService`; `PipelineService` writes or replays the canonical mutation and returns the canonical event id.
- `CommandService` stores the canonical event id on `CommandLog.canonical_event_id` when present. It must not write a second workspace event for the same product mutation.
- Replay behavior: same command key returns the previous `CommandLog`; same canonical key returns the previous canonical mutation response/event from `PipelineService`; mismatched command payload for the same key returns `409 COMMAND_IDEMPOTENCY_CONFLICT`.
- Tests cover preview apply retry, browser double-submit, legacy command adapter execution, canonical mutation replay, and no duplicate `VERSION_CREATED` / `VERSION_LIFECYCLE_CHANGED` workspace events.

No-version bootstrap:

```http
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/seed
```

- Purpose: recover or initialize a pipeline that has no versions. It is not used for ordinary version branching.
- `PipelineService.seedInitialVersion` owns the transaction. `PipelineCommandHandlers` may continue seeding during pipeline creation, but orphan/no-version recovery and IDE/classic no-version CTAs use this canonical endpoint.

```ts
type PipelineVersionSeedRequest = {
  changeSummary: string;
  template?: "EMPTY" | "FROM_PIPELINE_METADATA";
  correlationId?: string;
  idempotencyKey?: string;
};
```

Rules:

- Allowed only when the pipeline currently has zero versions; otherwise return `409 VERSION_SEED_NOT_ALLOWED` with `details.latestVersionId`.
- Requires `pipeline:write`, `X-Correlation-Id`, and `Idempotency-Key`; body `correlationId` and `idempotencyKey` are compatibility hints only.
- UI idempotency key formula: `version:seed:{pipelineId}:{correlationId}`. Replay returns the original seeded version and does not duplicate workspace events.
- Same idempotency key with different normalized body returns `409 VERSION_SEED_CONFLICT`.
- Creates revision display label `1`, matching current `PipelineCommandHandlers` seeding behavior. First-version numbering is not configurable in this plan.
- `template="EMPTY"` creates the minimum valid empty composition/orchestration/codegen metadata required by existing services. `FROM_PIPELINE_METADATA` may prefill names/descriptions only; it must not invent datasets, nodes, Git settings, deploy targets, or chat facts.
- Emits `VERSION_CREATED` with `baseVersionId=null`, `revision`, `seeded=true`, and the same actor/correlation/idempotency values.
- Response shape is the same `PipelineVersionCreateResponse`, with `baseVersionId:null` and route query `rev=<seededVersion.id>`.
- UI contract: classic and IDE no-version states show a single primary CTA `Create first version` when `pipeline:write` is allowed; otherwise they show read-only no-version guidance. Successful seed routes to the returned `rev` and enables normal versioned panels.

`PipelineVersionCreateRequest`:

```ts
type PipelineVersionCreateRequest = {
  baseVersionId: string;
  changeSummary: string;
  createdBy?: string;
  correlationId?: string;
  idempotencyKey?: string;
};
```

Rules:

- `baseVersionId` is required and must belong to `{tenantId,pipelineId}`.
- `changeSummary` is required non-empty after trim.
- `X-Correlation-Id` and `Idempotency-Key` headers are required on canonical routes. Body `correlationId`, `idempotencyKey`, and `createdBy` are compatibility hints only and follow the global actor/header rules.
- The server creates the new version by copying versioned composition/codegen/deployability-relevant fields from `baseVersionId` and assigning the next integer `revision` display label.
- The new version is the response authority; the client must route to `rev=<response.version.id>` after success.
- The action never uses `activeVersionId` or latest fallback.
- Idempotency key formula from UI: `version:create:{pipelineId}:{baseVersionId}:{correlationId}`. Replay with the same key returns the original created version and does not duplicate workspace events.

`PipelineVersionCreateResponse`:

```ts
type PipelineVersionCreateResponse = {
  version: PipelineVersionDTO;
  baseVersionId: string;
  route: {
    pathname: `/workspace/pipelines/${string}`;
    query: {
      rev: string;
      tab: "composition";
      side: "assistant" | "inspector" | "none";
      bottom: "problems" | "runs" | "events" | "diffs" | "audit" | "none";
      activity: "history" | "composition";
    };
  };
};
```

Create sequence:

1. Validate tenant, pipeline, base version, and write permission.
2. Check idempotency key for an existing create result.
3. Create the new `PipelineVersion` and cloned version state.
4. Record `VERSION_CREATED` in the same DB transaction using idempotency key `version:create:{newVersionId}`.
5. Return `201 Created` with `PipelineVersionCreateResponse`; replay returns `200 OK`.

Lifecycle request:

```ts
type PipelineVersionLifecycleRequest = {
  targetStage: PipelineStage;
  expectedCurrentStage: PipelineStage;
  actorId?: string;
  comment?: string;
  correlationId?: string;
  idempotencyKey?: string;
};
```

`PipelineStage` is the existing product enum, not a new UX-only lifecycle:

```ts
type PipelineStage =
  | "ENGINEERING"
  | "DEV_DEPLOYED"
  | "DEV_VALIDATED"
  | "INTEGRATION_QUALIFIED"
  | "PUBLISHED"
  | "UAT_DEPLOYED"
  | "PRODUCTION";
```

Rules:

- Path `versionId` is the only mutation authority.
- `expectedCurrentStage` must match persisted stage or return `409 VERSION_STAGE_CONFLICT`.
- `X-Correlation-Id` and `Idempotency-Key` headers are required on canonical routes. Body `actorId`, `correlationId`, and `idempotencyKey` are compatibility hints only and follow the global actor/header rules.
- Allowed transitions are configured in `PipelineService`; illegal transitions return `400 VERSION_STAGE_TRANSITION_INVALID`.
- This plan does not rename or migrate existing lifecycle values in `backend/src/main/java/com/pulse/pipeline/model/PipelineStage.java` or `frontend/src/types/index.ts`.
- Version mutability is explicit in this plan: `ENGINEERING`, `DEV_DEPLOYED`, `DEV_VALIDATED`, and `INTEGRATION_QUALIFIED` are editable; `PUBLISHED`, `UAT_DEPLOYED`, and `PRODUCTION` are immutable for composition, orchestration, codegen generate, artifact save, DQ apply, and version-scoped chat tool mutations. Deploy/package/approval reads and release actions may still operate on immutable stages when their own deployability/approval guards pass.
- Mutating endpoints that edit version content must call a shared backend guard such as `PipelineService.assertVersionContentMutable(versionId, action)` and return `409 VERSION_IMMUTABLE` with `details.lifecycleStage` for `PUBLISHED`, `UAT_DEPLOYED`, and `PRODUCTION`.
- Frontend panels use the same rule to render read-only state before submit; backend remains authoritative.
- Idempotency key formula from UI: `version:lifecycle:{versionId}:{targetStage}:{correlationId}`.

Lifecycle response:

```ts
type PipelineVersionLifecycleResponse = {
  version: PipelineVersionDTO;
  previousStage: string;
  currentStage: string;
  eventId: string;
};
```

Lifecycle sequence:

1. Validate tenant, pipeline, version, expected stage, transition, and write permission.
2. Persist the stage transition and record `VERSION_LIFECYCLE_CHANGED` in one transaction.
3. Return `200 OK` with the updated version DTO and event id.
4. Replay with the same idempotency key returns the already-updated version if the stored transition matches; mismatched replay returns `409 VERSION_IDEMPOTENCY_CONFLICT`.

Errors:

| Condition | HTTP | Code | Details |
| --- | --- | --- | --- |
| Pipeline missing | 404 | `PIPELINE_NOT_FOUND` | `pipelineId` |
| Base/version missing or outside pipeline | 404 | `VERSION_NOT_FOUND` | `pipelineId`, `versionId` |
| Tenant/user cannot mutate | 403 | `VERSION_FORBIDDEN` | `pipelineId`, `versionId` |
| Missing selected/base version | 400 | `VERSION_REQUIRED` | `pipelineId` |
| Base version mismatch | 400 | `VERSION_PIPELINE_MISMATCH` | `pipelineId`, `baseVersionId` |
| Expected stage mismatch | 409 | `VERSION_STAGE_CONFLICT` | `versionId`, `expectedCurrentStage`, `actualStage` |
| Illegal stage transition | 400 | `VERSION_STAGE_TRANSITION_INVALID` | `versionId`, `fromStage`, `toStage` |
| Unknown stage value | 400 | `VERSION_STAGE_INVALID` | `targetStage`, `allowedStages` |
| Same idempotency key with different payload | 409 | `VERSION_IDEMPOTENCY_CONFLICT` | `idempotencyKey` |
| Workspace event write failed | 500 | `WORKSPACE_EVENT_WRITE_FAILED` | `eventType`, `correlationId`, `idempotencyKey` |

Post-mutation client refresh:

- Create success: insert/update `pinnedSelectedVersion`, invalidate the versions page cache, route to the returned `rev`, preserve supported workspace keys unless the route response overrides them, and clear version-scoped bottom selections that no longer exist.
- Lifecycle success: replace `selectedVersion` with `response.version`, update matching paged/pinned rows, refresh problems/deployability/Git capabilities if the new stage affects mutability, and keep the route `rev` unchanged.
- Both flows append/merge the returned workspace event into events/audit panels when visible because they emit PIPELINE_VERSION events. Off-scope events, such as PIPELINE-scoped Git setup events, are handled by their owning workflow rules and are not injected into the default PIPELINE_VERSION feed.

### Composition And Orchestration Contracts

Primary owners:

- Backend controller: `backend/src/main/java/com/pulse/pipeline/controller/CompositionController.java`.
- Backend service: `backend/src/main/java/com/pulse/pipeline/service/CompositionService.java`.
- Compile-plan reader: `backend/src/main/java/com/pulse/codegen/service/CompilePlanService.java`.
- Frontend composition: `frontend/src/components/pipeline/composition-panel.tsx`.
- Frontend graph: `frontend/src/components/pipeline/dag-view.tsx`.
- Frontend transform dialog: `frontend/src/components/pipeline/configure-transform-dialog.tsx`.
- Frontend orchestration: `frontend/src/components/pipeline/orchestration-panel.tsx`.

Canonical endpoints:

```http
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition
PUT /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition
PATCH /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition/nodes/{nodeId}
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition/nodes
DELETE /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition/nodes/{nodeId}
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition/edges
DELETE /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/composition/edges/{edgeId}
PUT /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/orchestration
```

Compatibility:

- Current `/api/v1/versions/{versionId}/composition` endpoints become compatibility shims during migration.
- Shims derive tenant and pipeline from `versionId`, call the canonical service path, and return the same DTOs/errors.
- New IDE and migrated classic panels use only tenant-scoped canonical routes.

Read response:

```ts
type CompositionWorkbenchDTO = {
  tenantId: string;
  pipelineId: string;
  versionId: string;
  revision: number;
  lifecycleStage: PipelineStage;
  readOnly: boolean;
  readOnlyReasonCode: "NO_VERSION" | "VERSION_FORBIDDEN" | "VERSION_PUBLISHED" | "VERSION_UAT_DEPLOYED" | "VERSION_PRODUCTION" | "MISSING_PERMISSION" | null;
  nodes: CompositionNodeDTO[];
  edges: CompositionEdgeDTO[];
  orchestration: OrchestrationDTO;
  compileSnapshot?: CompilePlanSummaryDTO | null;
  problems: ReadinessIssueDTO[];
  etag: string;
  updatedAt: string;
};
```

Minimum node/edge DTOs:

```ts
type CompositionNodeDTO = {
  id: string;
  type: "SOURCE" | "TRANSFORM" | "SINK" | "DQ" | "ORCHESTRATION" | string;
  label: string;
  blueprintId?: string | null;
  datasetId?: string | null;
  position: { x: number; y: number };
  config: Record<string, unknown>;
  ports: Array<{ id: string; direction: "IN" | "OUT"; dataType?: string | null }>;
  status: "VALID" | "WARNING" | "ERROR" | "UNKNOWN";
  metadata: Record<string, unknown>;
};

type CompositionEdgeDTO = {
  id: string;
  sourceNodeId: string;
  sourcePortId?: string | null;
  targetNodeId: string;
  targetPortId?: string | null;
  metadata: Record<string, unknown>;
};

type OrchestrationDTO = {
  sensingBlueprintId?: string | null;
  scheduleCron?: string | null;
  periodicity?: string | null;
  catchupEnabled: boolean;
  maxActiveRuns: number;
  dependsOnPast: boolean;
  policy: Record<string, unknown>;
};

type CompilePlanSummaryDTO = {
  versionId: string;
  compilePlanId: string | null;
  status: "CURRENT" | "STALE" | "MISSING" | "FAILED";
  generatedAt: string | null;
  nodeCount: number;
  edgeCount: number;
  artifactCount: number;
  warnings: ReadinessIssueDTO[];
  blockers: ReadinessIssueDTO[];
};
```

Mutation envelope:

```ts
type CompositionMutationRequest<T> = {
  expectedVersionId: string;
  expectedEtag: string;
  operationId: string;
  correlationId: string;
  payload: T;
};

type CompositionMutationResponse = {
  composition: CompositionWorkbenchDTO;
  changedNodeIds: string[];
  changedEdgeIds: string[];
  eventId: string;
};
```

Rules:

- `expectedVersionId` must equal path `versionId`.
- `expectedEtag` must equal the current composition etag or return `409 COMPOSITION_CONFLICT`.
- `operationId` is required, client-generated, and unique per user mutation attempt. Formula from UI: `comp:{versionId}:{ulid}`.
- `PUBLISHED`, `UAT_DEPLOYED`, and `PRODUCTION` versions return `409 VERSION_IMMUTABLE` for composition/orchestration mutation attempts.
- Each mutation validates tenant, pipeline, version relationship, node/edge existence, port compatibility, blueprint/dataset accessibility, and lifecycle write permission before writing.
- `PUT .../composition` is bulk replacement for graph layout/import only; normal UI editing should use node/edge targeted endpoints.
- `PUT .../orchestration` mutates only orchestration fields and emits `COMPOSITION_CHANGED` with `changeType="orchestration_update"`.

Mutation sequence:

1. Validate tenant, pipeline, version, lifecycle mutability, expected version, and etag.
2. Apply composition/orchestration mutation and recompute etag/problems in the same DB transaction.
3. Record `COMPOSITION_CHANGED` with idempotency key `composition:{versionId}:{operationId}:COMPOSITION_CHANGED`.
4. Return `CompositionMutationResponse`.
5. Replay with the same `operationId` and identical payload returns the existing mutation result; same `operationId` with different payload returns `409 COMPOSITION_IDEMPOTENCY_CONFLICT`.

Structured errors:

| Condition | HTTP | Code | Details |
| --- | --- | --- | --- |
| Version missing/outside pipeline | 404 | `VERSION_NOT_FOUND` | `pipelineId`, `versionId` |
| Version path/body mismatch | 400 | `COMPOSITION_VERSION_MISMATCH` | `versionId`, `expectedVersionId` |
| Etag stale | 409 | `COMPOSITION_CONFLICT` | `versionId`, `expectedEtag`, `actualEtag` |
| Published/UAT/production version | 409 | `VERSION_IMMUTABLE` | `versionId`, `lifecycleStage` |
| Node missing | 404 | `COMPOSITION_NODE_NOT_FOUND` | `nodeId` |
| Edge missing | 404 | `COMPOSITION_EDGE_NOT_FOUND` | `edgeId` |
| Port/wiring invalid | 400 | `COMPOSITION_WIRING_INVALID` | `sourceNodeId`, `targetNodeId`, `reasonCode` |
| Blueprint/dataset inaccessible | 403 | `COMPOSITION_REFERENCE_FORBIDDEN` | `referenceType`, `referenceId` |
| Same operation id with different payload | 409 | `COMPOSITION_IDEMPOTENCY_CONFLICT` | `operationId` |
| Workspace event write failed | 500 | `WORKSPACE_EVENT_WRITE_FAILED` | `eventType`, `correlationId`, `idempotencyKey` |

Post-mutation client refresh:

- Composition panel replaces local graph state from `response.composition`; it does not patch by inference when the server returns a full workbench DTO.
- Problems bottom panel replaces problem rows for the current `versionId` from `response.composition.problems` and clears `problemsIssueId` if the focused issue no longer exists.
- Inspector selection is preserved only if the selected node/edge still exists; otherwise it is cleared with a small toast.
- Codegen and deployability caches for the version are marked stale after any composition/orchestration mutation.
- Events/audit panels merge the returned `eventId` when visible and otherwise refresh on next open.

## Story Contract

### S0 Aggregate Story Safety

Before adding version story behavior, make legacy `/story` safe.

Legacy aggregate endpoint:

```http
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/story
```

S0 behavior:

- Routes to `StoryGenerationService.generatePipelineStoryReadOnly`.
- Recomputes an uncached aggregate DTO on every request.
- Accepts optional JSON body `{ "regenerate": false }`. During migration only, existing query string `?regenerate=true|false` is accepted as a compatibility shim and mapped into the same body contract.
- `regenerate` is a no-op because S0 has no cache.
- Writes no `PipelineVersion.metadata.generatedStory`.
- Writes no pipeline metadata cache.
- Does not read chat transcripts, `ChatService.getSessionFacts`, tool facts, composition graph, generated code/artifacts, orchestration policy, or active/latest version content.
- May use pipeline metadata/name/domain, version count, and display-only active/latest labels.
- Does not invent pipeline status from version lifecycle stage.

Live DTO shape:

```json
{
  "title": "Pipeline story: <pipeline.name>",
  "storyMarkdown": "# Pipeline story: <pipeline.name>\n\n<aggregate summary>\n\n## Version-specific work\nVersion-specific composition, code, orchestration, and chat-derived facts require selecting a pipeline version.",
  "tasks": [
    {
      "title": "Review aggregate pipeline context",
      "description": "Review pipeline-level metadata and choose a version for implementation details.",
      "estimate": "S"
    }
  ],
  "sourceScope": "PIPELINE_AGGREGATE",
  "storyId": "pipeline:<pipelineId>:aggregate",
  "pipelineId": "...",
  "versionId": null,
  "versionSectionsAvailable": false,
  "generatedAt": "ISO-8601"
}
```

S0 response always includes `storyId`, `sourceScope`, `pipelineId`, `versionId`, `versionSectionsAvailable`, and `generatedAt` in addition to the live `title`, `storyMarkdown`, and `tasks` fields. Frontend types and rendering must accept these fields in the same step.

Canonical `PipelineStoryDTO`:

```ts
type PipelineStoryDTO = {
  storyId: string;
  title: string;
  storyMarkdown: string;
  tasks: Array<{ title: string; description: string; estimate: "S" | "M" | "L" | string }>;
  sourceScope: "PIPELINE_AGGREGATE" | "PIPELINE_VERSION";
  pipelineId: string;
  versionId: string | null;
  versionSectionsAvailable: boolean;
  generatedAt: string;
  freshness: "FRESH" | "STALE" | "NOT_CACHEABLE";
  staleReasons: string[];
  sourceFingerprint: string | null;
};
```

Aggregate and version story endpoints both return `PipelineStoryDTO`.
Aggregate stories return `freshness="NOT_CACHEABLE"`, `staleReasons=[]`, and `sourceFingerprint=null` because S0 aggregate stories are read-only summaries and do not persist version-scoped freshness state.
`storyId` is deterministic: aggregate story uses `pipeline:<pipelineId>:aggregate`; version story uses `pipeline:<pipelineId>:version:<versionId>`. The `#story:<storyId>` anchor uses this value URL-encoded.

Story identity and replay contract:

- Aggregate and version story POST endpoints require `X-Correlation-Id`. They also require `Idempotency-Key` whenever workspace-event emission or metadata write is enabled.
- Actor identity for both endpoints is resolved by `PulseGuard`: aggregate story requires `pipeline:read`; version story requires `pipeline:read` when returning an existing fresh/stale cached story without durable writes, and `pipeline:write` whenever the request creates, recomputes, overwrites, or otherwise writes `PipelineVersion.metadata.generatedStory`.
- Request-body actor fields are not accepted for authority. Any legacy body `userId` is ignored for actor resolution.
- Aggregate S0 story is read-only before workspace events exist and does not require replay storage when it emits no durable side effect. After workspace-event emission is enabled, aggregate story idempotency key formula is `story:aggregate:{pipelineId}:{regenerate}:{correlationId}`. Replay returns the original `PipelineStoryDTO` and does not duplicate `STORY_GENERATED`.
- Version story idempotency key formula is `story:version:{pipelineId}:{versionId}:{regenerate}:{sourceFingerprintOrUnknown}:{correlationId}`. Replay returns the original `PipelineStoryDTO` and does not duplicate metadata writes or `STORY_GENERATED`.
- Same idempotency key with a different normalized path/body/fingerprint returns `409 STORY_IDEMPOTENCY_CONFLICT`.
- Story workspace events are emitted only once per accepted idempotency key. Event payload includes `actor`, `correlationId`, `idempotencyKey`, `storyId`, `sourceScope`, `pipelineId`, `versionId`, `generatedAt`, and `regenerated`.

Tests:

- Calling S0 twice with both regenerate values writes no pipeline or version metadata.
- Existing `UserStoryDialog` renders the DTO and copies markdown without crash.
- S0 does not call chat repositories/services, facts extraction, composition, codegen, orchestration, or version-content loaders.
- Seeded transcript-derived fact strings do not appear in S0 response.

### Version Story

Owners:

- Backend route owner: `backend/src/main/java/com/pulse/pipeline/controller/PipelineController.java`.
- Backend generation owner: `backend/src/main/java/com/pulse/pipeline/service/StoryGenerationService.java`.
- Shared frontend hook: `frontend/src/components/pipeline/use-pipeline-story.ts`.
- Modal frontend owner: `frontend/src/components/pipeline/user-story-dialog.tsx`.
- IDE pane frontend owner: `frontend/src/components/pipeline/story-pane.tsx`.

Add:

```http
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/story
```

Behavior:

- Calls `StoryGenerationService.generateVersionStory(PipelineVersionStoryContext)`.
- Requires explicit `versionId`.
- Validates version belongs to pipeline and tenant.
- Reads/writes only `PipelineVersion.metadata.generatedStory` for that exact version.
- Never uses active/latest fallback.
- Full transcript/tool sections are enabled only after scoped chat migration and filtered by `PIPELINE_VERSION` session/message `versionId`.
- Before scoped chat migration, version story must omit transcript/tool/facts sections entirely. It may use only pipeline metadata, selected version metadata, composition, codegen/deployability summaries explicitly tied to `versionId`, and orchestration policy tied to `versionId`.
- Pre-migration version story must not read legacy PIPELINE chat sessions, because those sessions are not version-scoped.

Request body:

```json
{
  "regenerate": false,
  "expectedVersionId": "..."
}
```

Rules:

- Body is optional.
- `expectedVersionId` is optional, but when supplied it must equal the path `versionId` or return `400 STORY_VERSION_MISMATCH`.
- Because `versionId` is path-required, `STORY_VERSION_REQUIRED` applies only to client/controller invocation attempts before an HTTP request, not to the path endpoint itself.
- `regenerate=true` recomputes and overwrites `PipelineVersion.metadata.generatedStory` for the exact path version only.
- `UserStoryDialog` always sends JSON body for both aggregate and version story after cutover. It does not mix query-string and body authority.
- Version story response uses `sourceScope="PIPELINE_VERSION"`, `versionId=<path versionId>`, and `versionSectionsAvailable=true` once version-specific sections are included.
- Pre-scoped-chat version story may still return `versionSectionsAvailable=true` for composition/codegen/orchestration sections, but transcript/tool sections are omitted until scoped chat migration.

Story error contract:

- Aggregate and version story endpoints return the shared `{code,message,details}` envelope for all non-2xx responses.
- Aggregate story 404: `PIPELINE_NOT_FOUND`.
- Aggregate story 403: `PIPELINE_FORBIDDEN`.
- Version story 404 for missing pipeline: `PIPELINE_NOT_FOUND`.
- Version story 404 for missing version in the pipeline: `VERSION_NOT_FOUND`.
- Version story 403 for forbidden pipeline: `PIPELINE_FORBIDDEN`.
- Version story 403 for forbidden version: `VERSION_FORBIDDEN`.
- Version story 400 when request body `expectedVersionId` mismatches the path version: `STORY_VERSION_MISMATCH`.
- Story replay conflict: `409 STORY_IDEMPOTENCY_CONFLICT`.
- Local UI/controller attempt to open version story without selected version: `STORY_VERSION_REQUIRED`, no backend request.
- `frontend/src/lib/api.ts` parses story errors through the same `ApiError` path as version, chat, codegen, workspace-events, EBCDIC, and command-search APIs.

Frontend split:

- `usePipelineStory({tenantId,pipelineId,storyScope,selectedVersionId})` owns endpoint choice, cache key, load/regenerate actions, stale/fresh state, and parsed `ApiError` state.
- `UserStoryDialog` is modal-only. It wraps the shared hook, owns dialog open/close, and may be used from classic pipeline or other AppChrome routes.
- `StoryPane` is the persistent IDE tab for `tab=story`. It wraps the same hook, renders toolbar actions inline, reads selected version from `PipelineRouteStateController`, and never mounts dialog chrome or controls `open/onOpenChange`.
- Story markdown/task rendering is extracted to `frontend/src/components/pipeline/story-content.tsx` so dialog and pane cannot drift.

`UserStoryDialog` gains:

- `selectedVersionId?: string`.
- `storyScope: "PIPELINE_AGGREGATE" | "PIPELINE_VERSION"`.

The page mount passes selected version and scope before relying on version story behavior.

Dialog invalidation:

- `usePipelineStory` derives cache key from `{tenantId,pipelineId,storyScope,selectedVersionId}`. `UserStoryDialog` and `StoryPane` consume that key through the hook.
- When `storyScope` or `selectedVersionId` changes, the dialog clears local story DTO, error, and loading state before the next request.
- Aggregate story cache key uses `selectedVersionId=null`.
- Version story cache key requires `selectedVersionId`.
- `regenerate=true` invalidates only the current cache key.

Version story freshness:

- `PipelineVersion.metadata.generatedStory` stores a structured envelope, not only markdown: `{ storyId, storyMarkdown, generatedAt, contentVersion, sourceFingerprint, staleReasons: [] }`.
- `sourceFingerprint` is a server hash over version-scoped inputs used by the story: `version.updatedAt`, composition etag, orchestration updated timestamp, latest completed codegen run id/artifact hash, deployability assessment id/status, and scoped chat facts high-water mark once PIPELINE_VERSION chat facts are enabled.
- Normal `regenerate=false` behavior: if no story exists, generate it; if a story exists and fingerprint matches, return it with `freshness="FRESH"`; if fingerprint differs, return the cached story with `freshness="STALE"` and `staleReasons`, unless request body sets `regenerate=true`.
- `regenerate=true` recomputes the story, updates `sourceFingerprint`, clears `staleReasons`, and returns `freshness="FRESH"`.
- Composition/orchestration mutations, codegen completion, artifact edit/save, deployability assessment, and future version-scoped chat fact persistence do not synchronously regenerate the story; they make freshness detectable by changing one of the fingerprint inputs.
- Frontend story pane displays stale state as a refresh/regenerate affordance and must not label a stale story as current evidence.
- Story cache invalidation in `usePipelineStory` is still keyed by route/scope/version; freshness is server-authoritative and returned with the DTO.

Caller behavior:

- Classic with valid selected version defaults `UserStoryDialog` to `PIPELINE_VERSION` and calls `POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{selectedVersionId}/story`.
- Classic with no selected version defaults `UserStoryDialog` to `PIPELINE_AGGREGATE` and calls `POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/story`.
- Classic may offer an explicit aggregate-story affordance, but the label must make clear it is pipeline-level and excludes version implementation detail.
- IDE always uses `PIPELINE_VERSION`. It requires `PipelineRouteStateController.selectedVersionId` before enabling story generation.
- IDE no-version state disables story generation and shows the same no-version/select-version CTA used by other versioned panels.
- If `storyScope` is `PIPELINE_VERSION` and `selectedVersionId` is null, the dialog must not open a backend request; it renders a local disabled/no-version state.
- `PIPELINE_AGGREGATE` is allowed only on classic and global/pipeline overview surfaces. It is not used by IDE workspace tabs.
- `usePipelineStory` owns endpoint choice from `storyScope` plus `selectedVersionId`.
- `frontend/src/app/(app)/pipelines/[pipelineId]/pipeline-classic-page.tsx` passes aggregate or version props based on `PipelineRouteStateController`.
- `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx` passes only version props and disables the story tab/action when no selected version exists.

Story event payload:

```json
{
  "sourceScope": "PIPELINE_AGGREGATE|PIPELINE_VERSION",
  "storyScope": "PIPELINE_AGGREGATE|PIPELINE_VERSION",
  "pipelineId": "...",
  "versionId": null,
  "generatedAt": "ISO-8601",
  "regenerated": false
}
```

Rules:

- Aggregate story emits workspace event scope `PIPELINE` with payload `sourceScope="PIPELINE_AGGREGATE"` and `versionId=null`.
- Version story emits workspace event scope `PIPELINE_VERSION` with payload `sourceScope="PIPELINE_VERSION"` and exact `versionId`.
- `storyScope` is kept as a payload alias for UI compatibility and must equal `sourceScope`.

## Chat Contract

### Atomic Pre-Split Compatibility

Before splitting global and pipeline chat hosts, land exact backend/frontend compatibility atomically.

Latest route:

```http
GET /api/v1/tenants/{tenantId}/chat/sessions/latest?userId={userId}
GET /api/v1/tenants/{tenantId}/chat/sessions/latest?userId={userId}&pipelineId={pipelineId}
```

Rules:

- Omitted `pipelineId` means GLOBAL and returns only `pipeline_id IS NULL`.
- Non-empty `pipelineId` means legacy PIPELINE and returns only that exact `pipeline_id`.
- Blank `pipelineId` returns 400:

```json
{
  "code": "CHAT_PIPELINE_INVALID",
  "message": "pipelineId must be omitted or a non-empty value",
  "details": {}
}
```

- No fallback between global and pipeline.
- Ordering is `updatedAt DESC, id DESC`.
- Add repository/service methods for latest global and latest pipeline.

Create route:

```http
POST /api/v1/tenants/{tenantId}/chat/sessions
```

Rules:

- Omitted `pipelineId` creates GLOBAL.
- Non-empty `pipelineId` creates legacy PIPELINE.
- Blank `pipelineId` returns `CHAT_PIPELINE_INVALID`.
- Optional `scope` may be accepted later; during this patch it must agree with `pipelineId` if present.

Touch parent session:

- `ChatService` touches/saves parent `ChatSession.updatedAt` after every persisted user message, assistant message, tool row, or persisted error.
- For streaming, touch at least once when user message is persisted and again when assistant/tool persistence completes.
- Latest ordering therefore means latest active conversation.

Frontend pre-split restore:

- `chat-context.tsx` restore key is `{tenantId}:{userId}:{pipelineId ?? "global"}`.
- The restore effect depends on tenant, user, and `pipelineId`.
- The fetch URL includes `pipelineId` when set.
- Any global-to-pipeline or pipeline-to-global change clears current session/messages, marks loading, and aborts or ignores stale requests.
- `loadedFor` is the full key.
- `resetChat` preserves current pre-split `pipelineId` until the split lands.

Tests:

- Global latest ignores newer pipeline session.
- Pipeline latest ignores newer global session.
- Blank pipelineId returns stable error body.
- `updatedAt DESC, id DESC` tie-break works.
- Sending in older session makes it latest.
- `setPipelineId` after mount cannot be overwritten by slower global restore.
- Switching away from pipeline cannot be overwritten by slower pipeline restore.
- Pipeline New Chat creates pipeline session.
- Global New Chat creates global session.

### Tenant-Scoped Send Endpoints

Add before `PipelineChatPanel` uses them:

```http
POST /api/v1/tenants/{tenantId}/chat/sessions/{sessionId}/messages/stream
POST /api/v1/tenants/{tenantId}/chat/sessions/{sessionId}/messages
```

Body:

```json
{
  "content": "...",
  "userId": "...",
  "scope": "GLOBAL|PIPELINE|PIPELINE_VERSION",
  "pipelineId": "...",
  "versionId": "...",
  "workspaceTab": "...",
  "route": "...",
  "activity": "...",
  "side": "...",
  "bottom": "...",
  "anchor": "...",
  "correlationId": "...",
  "turnId": "..."
}
```

Rules:

- Path tenant must match `ChatSession.tenantId`, or 400 `CHAT_TENANT_MISMATCH`.
- `.../messages/stream` is the canonical SSE send route.
- `.../messages` is the canonical non-stream JSON send route.
- Both routes validate the request scope against the persisted `ChatSession` scope before executing. Mismatches return `CHAT_SCOPE_MISMATCH`; pipeline/version mismatches return `CHAT_PIPELINE_MISMATCH` or `CHAT_VERSION_MISMATCH`.
- Before the scoped chat migration adds `ChatSession.scope`, tenant-scoped send/history validation derives effective persisted scope from legacy columns: `pipelineId == null` means GLOBAL, and `pipelineId != null` means PIPELINE. PIPELINE_VERSION sends are rejected with `CHAT_VERSION_REQUIRED` until scoped sessions exist.
- The same pre-split derived-scope rule applies to tenant-scoped send, tenant-scoped history, latest, create, reset, and restore flows.
- `GlobalChatController`, `PipelineChatPanel`, and `WorkspaceAssistantPanel` use `.../messages/stream` for normal assistant sends.
- `ChatProvider.sendMessage` uses `.../messages/stream` when streaming UI is enabled and may use `.../messages` only for explicit non-stream/test sends.
- `.../messages/stream` returns `text/event-stream` with the SSE event contract in the Chat Component Split section.
- `.../messages` returns JSON and does not emit SSE.
- `workspaceTab`, `activity`, `side`, `bottom`, and `anchor` are audit/navigation context fields. They use the same allowed values as `ChatSendMetadata`; omitted fields are persisted as null.
- `route` is required. IDE `PIPELINE_VERSION` sends require `workspaceTab`; classic/global sends omit IDE-only fields.

Non-stream success response:

```json
{
  "turnId": "...",
  "correlationId": "...",
  "messages": [
    {
      "id": "...",
      "sessionId": "...",
      "role": "USER|ASSISTANT|TOOL|SYSTEM",
      "content": "...",
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601",
      "scope": "GLOBAL|PIPELINE|PIPELINE_VERSION",
      "pipelineId": "...",
      "versionId": "...",
      "turnId": "...",
      "correlationId": "...",
      "toolCalls": [],
      "toolResults": [],
      "metadata": {}
    }
  ],
  "navigationEvents": []
}
```

Controller-to-endpoint map:

- `frontend/src/components/chat/global-chat-controller.tsx`: `.../messages/stream` for full-page and drawer sends; legacy shim only before tenant-scoped global cutover.
- `frontend/src/components/pipeline/pipeline-chat-panel.tsx`: `.../messages/stream` from the first split; scope `PIPELINE`.
- `frontend/src/components/pipeline/workspace-assistant-panel.tsx`: `.../messages/stream` after authority gate; scope `PIPELINE_VERSION`.
- Backend owner: `backend/src/main/java/com/pulse/chat/controller/ChatController.java` and `backend/src/main/java/com/pulse/chat/service/ChatService.java`, with the legacy route retained only as compatibility shim.
- Legacy SSE shim remains:

```http
POST /api/v1/chat/sessions/{sessionId}/messages
```

- The legacy shim derives tenant from `ChatSession.tenantId`.
- If legacy body includes tenantId, it must match the session tenant.

### Chat Message History

Pre-split compatibility route:

```http
GET /api/v1/chat/sessions/{sessionId}/messages
```

Rules:

- Retained for current global drawer compatibility.
- Returns messages for the session id.
- During pre-split compatibility, callers must already have obtained the session through exact GLOBAL or legacy PIPELINE latest/create rules.

Tenant-scoped route:

```http
GET /api/v1/tenants/{tenantId}/chat/sessions/{sessionId}/messages?limit=&cursor=&direction=
```

Rules:

- Path tenant must match `ChatSession.tenantId`, or 400 `CHAT_TENANT_MISMATCH`.
- Default `limit=100`, max 200.
- Default `direction=before` for restore flows.
- `direction=before` returns newest messages first using order `createdAt DESC, id DESC`; the client reverses the page for display.
- `direction=before` with a cursor from the oldest loaded message loads older history.
- `direction=after` with a cursor from the newest loaded message loads newer messages for live-follow catch-up; order is `createdAt ASC, id ASC`.
- Cursor is base64url JSON `{ "createdAt": "ISO-8601", "id": "..." }`.
- For `direction=before`, predicate is `(createdAt < cursor.createdAt) OR (createdAt = cursor.createdAt AND id < cursor.id)`.
- For `direction=after`, predicate is `(createdAt > cursor.createdAt) OR (createdAt = cursor.createdAt AND id > cursor.id)`.
- Initial restore omits cursor and uses `direction=before`, so the first page contains the newest messages without draining all pages.
- Response is canonical `ChatMessageDTO` envelope:

```json
{
  "items": [
    {
      "id": "...",
      "sessionId": "...",
      "role": "USER|ASSISTANT|TOOL|SYSTEM",
      "content": "...",
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601",
      "scope": "GLOBAL|PIPELINE|PIPELINE_VERSION",
      "pipelineId": "...",
      "versionId": "...",
      "turnId": "...",
      "correlationId": "...",
      "toolCalls": [],
      "toolResults": [],
      "metadata": {
        "navigationEvents": []
      },
      "legacyToolCalls": null,
      "legacyToolResults": null
    }
  ],
  "nextCursor": null
}
```

Formal DTO:

```ts
type ChatMessageDTO = {
  id: string;
  sessionId: string;
  role: "USER" | "ASSISTANT" | "TOOL" | "SYSTEM";
  content: string;
  createdAt: string;
  updatedAt: string;
  scope: "GLOBAL" | "PIPELINE" | "PIPELINE_VERSION";
  pipelineId: string | null;
  versionId: string | null;
  turnId: string | null;
  correlationId: string | null;
  toolCalls: ToolCallDTO[];
  toolResults: ToolResultDTO[];
  metadata: Record<string, unknown>;
  legacyToolCalls: unknown | null;
  legacyToolResults: unknown | null;
};
```

Empty response:

```json
{
  "items": [],
  "nextCursor": null
}
```

Controller usage:

- Pre-split global drawer may continue using the legacy route after exact session restore.
- `PipelineChatPanel` uses the tenant-scoped route from the start.
- After scoped chat migration, GlobalChatController, PipelineChatPanel, and WorkspaceAssistantPanel all use the tenant-scoped route.
- Version switch in IDE changes the restore key, loads latest PIPELINE_VERSION session, then loads history through the tenant-scoped route.

### Chat Component Split

Do not make one component own all chat behavior.

Create:

- `frontend/src/components/chat/chat-stream.ts`: SSE reader/parser for `chunk`, `tool_call`, `tool_result`, `navigate`, `error`, including dual-shape `tool_result`.
- `frontend/src/components/chat/chat-message-list.tsx`: presentation.
- `frontend/src/components/chat/chat-composer.tsx`: presentation.
- `frontend/src/components/chat/global-chat-controller.tsx`: owns GLOBAL session restore/create/reset/send, stream state, file attachment state, router navigation, toasts, tenant refresh hooks.
- `frontend/src/components/pipeline/pipeline-chat-panel.tsx`: owns PIPELINE local session restore/create/reset/send, stream state, file attachment state, router navigation, toasts, tenant refresh hooks relevant to pipeline context.
- `frontend/src/components/pipeline/workspace-assistant-panel.tsx`: owns PIPELINE_VERSION IDE assistant state after the authority gate passes.

Component relationship:

- `GlobalChatController`, `PipelineChatPanel`, and `WorkspaceAssistantPanel` are separate controllers.
- They share `frontend/src/components/chat/chat-stream.ts`, `frontend/src/components/chat/chat-message-list.tsx`, and `frontend/src/components/chat/chat-composer.tsx`.
- They do not share session state.
- `WorkspaceAssistantPanel` is not a wrapper around `PipelineChatPanel`; it is a separate controller because it uses PIPELINE_VERSION scope and selectedVersionId.

Stream callbacks:

- `onChunk(text)`
- `onToolCall(name, payload?)`
- `onToolResult(result)`
- `onNavigate(payload)`
- `onError(parsedError)`
- `onDone()`

SSE payload contract:

- `chunk`: data is plain text delta. Parser calls `onChunk(text)`.
- `tool_call`: data may be a plain tool name string or JSON `{ "toolCallId": "...", "toolName": "...", "arguments": {} }`. Parser normalizes to `onToolCall(toolName, payload?)`.
- `tool_result`: data may be legacy plain tool name string or canonical JSON `ToolResultDTO`. Parser normalizes legacy strings to `{toolName:string,status:"success",result:{legacyText:string}}`.
- `navigate`: data is canonical JSON `ChatNavigateEvent`.
- `error`: data may be plain text, `{ "message": "..." }`, or `{ "code": "...", "message": "...", "details": {} }`. Parser normalizes to `ParsedChatError`.
- `done`: data is optional JSON `{ "turnId": "...", "assistantMessageId": "...", "persisted": true }`. Parser calls `onDone()` after flushing any buffered chunk text.

`ParsedChatError`:

```ts
type ParsedChatError = {
  code: string;
  message: string;
  details?: Record<string, unknown>;
};
```

Canonical chat navigation event:

```ts
type ChatNavigateEvent = {
  targetRoute: string;
  scope: "GLOBAL" | "PIPELINE" | "PIPELINE_VERSION";
  pipelineId: string | null;
  versionId: string | null;
  anchor: string | null;
  resource: null | { type: "SOR"; id: string } | { type: "PIPELINE"; id: string } | { type: "EBCDIC_SESSION"; id: string };
  correlationId: string;
};
```

Rules:

- `targetRoute` is authoritative and must be a fully resolved internal route beginning with `/`.
- The client must not synthesize a route from `pipelineId`, `versionId`, or `anchor`.
- `pipelineId`, `versionId`, `anchor`, and `resource` are validation/evidence fields. If they conflict with `targetRoute` path, `rev`, hash, or resource path segment, the client rejects the navigation event, logs `CHAT_NAVIGATION_MISMATCH`, and shows a non-blocking toast.
- For `PIPELINE_VERSION`, `targetRoute` must include `rev=<versionId>` and must use `/workspace/pipelines/<pipelineId>` unless it is a classic compatibility route explicitly emitted during migration.
- Legacy raw-string `navigate` events are accepted only by the legacy shim before tenant-scoped chat cutover; the shim normalizes them to `ChatNavigateEvent` with `scope="GLOBAL"`, null ids, and generated correlation id, then applies the same internal-route validation.

SSE ordering:

- A send starts with client optimistic USER message and an empty transient assistant stream buffer.
- Zero or more `chunk`, `tool_call`, `tool_result`, and `navigate` events may arrive before terminal event.
- Exactly one terminal event must arrive: `done` or `error`.
- `done` must be the final event for a successful stream.
- `error` must be the final event for a failed stream.
- Events after `done` or `error` are ignored and logged as protocol violations.
- Partial assistant text remains transient until `done`.
- On `done`, controller commits buffered assistant text into the canonical message store. If `assistantMessageId` is supplied, use it; otherwise reconcile by `turnId`.
- On `error`, controller preserves the user message, marks the transient assistant buffer failed, and does not create a successful assistant message.
- Persisted message history remains authoritative after reload; optimistic/transient stream state is discarded on restore.

Canonical `ToolResultDTO` SSE data:

```json
{
  "toolCallId": "call_...",
  "toolName": "plan_wiring",
  "status": "success|error",
  "result": {
    "planCreated": false,
    "mutationApplied": false,
    "planId": null,
    "commandIds": [],
    "affectedEntities": [],
    "refreshHints": [],
    "message": null
  },
  "error": null,
  "timestamp": "ISO-8601"
}
```

Rules:

- `plan_*` success sets `result.planCreated=true`, `result.planId`, and a user-facing `result.message`; it sets `mutationApplied=false`.
- `apply_plan` success sets `result.mutationApplied=true`, `result.planId`, `result.commandIds`, `result.affectedEntities`, `result.refreshHints`, and `result.message`.
- Read-only tool success may leave plan/apply fields false/null but still uses the canonical object shape.
- Controllers must not treat a tool result as mutating unless `result.mutationApplied=true`.

Controller responsibilities:

- Controllers own session lifecycle, message state, attachment state, routing side effects, toast side effects, and tenant refresh side effects.
- Presentational components never fetch, send, parse SSE, navigate, or mutate tenant state.
- `chat-stream.ts` has no React state and performs no side effects except invoking callbacks.

Message rendering contract:

- `chat-message-list.tsx` renders USER and ASSISTANT messages only.
- TOOL-role rows and assistant rows with empty/whitespace content are hidden from the main transcript but may contribute status chips, tool timelines, or debug metadata.
- Tool calls/results render as compact inline status rows attached to the associated assistant turn when `turnId` exists; orphaned legacy tool events render in a collapsed `Tool activity` group.
- DAG previews render when a message or tool result contains either fenced code block language `dag`, `mermaid`, or `json` with top-level `{ "nodes": [], "edges": [] }`, or a `ToolResultDTO.result.graph` object with `nodes` and `edges`. Nodes require `id` and `label`; edges require `source` and `target`. Invalid graph payloads render as collapsed JSON with an error badge.
- Table previews render when a message or tool result contains `ToolResultDTO.result.table` shaped as `{ "columns": string[] | Array<{ "key": string, "label": string }>, "rows": Array<Record<string, unknown> | unknown[]> }`. Tables are horizontally scrollable, cap visible rows at 100 with a `Show all` affordance, and never expand raw JSON inline by default.
- Unknown JSON renders in a collapsed structured block with copy action rather than raw unbounded text.
- Navigation events render as a small route/action affordance in the turn and never auto-open external URLs.
- Presentational components receive already-normalized `ChatMessageDTO`, `ToolResultDTO`, and transient stream state; they never parse legacy shapes.

Client side-effect matrix:

| Stream/result event | Controller action |
| --- | --- |
| `navigate` with internal `/pipelines/<id>` or `/workspace/pipelines/<id>` | Validate internal route, route through Next router, preserve current `correlationId` for the navigation event only. |
| `plan_*` tool result with `planCreated=true` | Render plan preview/approval affordance, store `planId` in the turn, and perform no product-state refresh except optional plan/history refresh. |
| `apply_plan` result with `mutationApplied=true` and domain/SOR `affectedEntities` | Refresh tenant context/domain/SOR/dataset views indicated by `refreshHints`; if current route is affected SOR detail, refetch that page data. |
| `apply_plan` result with `mutationApplied=true` and pipeline aggregate `affectedEntities` | Refresh tenant pipeline list and current pipeline aggregate detail according to `refreshHints`; navigate only when a valid internal `navigate` event or action is supplied. |
| `apply_plan` result with `mutationApplied=true` and composition/orchestration `affectedEntities` | Refetch composition for the explicit `versionId`, invalidate deployability/problems for that version, and do not switch versions. |
| `apply_plan` result with `mutationApplied=true` and DQ `affectedEntities` | Refetch DQ/readiness and deployability/problem contributors for the explicit `versionId`; keep current tab unless a navigate event is also supplied. |
| `generate_code`, codegen tool success, or `CODEGEN_*` result metadata | Refetch codegen runs/artifacts for the explicit `versionId`; stale editor buffers follow the Codegen freshness contract. |
| Tool result `status:"error"` | Show parsed error toast/message metadata, add problem contributor for current route scope when applicable, and do not mutate local domain/composition/codegen state. |
| HTTP/SSE `error` terminal event | Preserve user message, mark transient assistant failed, and run no success side effects. |

Chat navigate allowlist:

| Route pattern | Permitted scopes | Required validation | Side effects |
| --- | --- | --- | --- |
| `/pipelines` | GLOBAL, PIPELINE | internal route only | Navigate; refresh tenant pipeline summaries if previous tool mutated pipelines. |
| `/pipelines/<pipelineId>` | PIPELINE, PIPELINE_VERSION | `pipelineId` in payload must match route | Navigate classic route; refresh pipeline aggregate. |
| `/workspace/pipelines/<pipelineId>` | PIPELINE_VERSION | `pipelineId` and `versionId` must match route and `rev` | Navigate IDE; version state controller validates `rev`. |
| `/producers` | GLOBAL, PIPELINE | internal route only | Navigate; refresh SOR list if previous tool mutated SORs. |
| `/producers/<sorId>` | GLOBAL, PIPELINE | If `resource` is supplied it must be `{type:"SOR", id:"<sorId>"}`; otherwise route-only validation is sufficient. | Navigate; refetch SOR detail. |
| `/commands` | GLOBAL | internal route only | Navigate; no mutation side effect. |
| `/settings` | GLOBAL | internal route only | Navigate; no mutation side effect. |
| `/ebcdic-discovery` | GLOBAL, PIPELINE | If query `scope=PIPELINE`, payload `pipelineId` must match query. | Navigate; EBCDIC context restores/creates route-scoped session. |

Any other `targetRoute`, external URL, protocol-relative URL, or route/payload mismatch is rejected with `CHAT_NAVIGATION_REJECTED`, logged, and rendered as a non-blocking message affordance. The client never synthesizes a route from ids.

Tenant refresh hooks are controller-local. Global chat may refresh tenant/global resources but never refetch version composition unless a result includes explicit `versionId` and an internal navigation has placed the user in that route. Pipeline chat may refresh aggregate pipeline/SOR resources. Workspace assistant may refresh only the selected version and aggregate summaries for its `PipelineRouteState`.

After split:

- `AppChrome` mounts `GlobalChatDrawer`, with no pipelineId prop.
- `top-bar.tsx` opens `GlobalChatDrawer` only.
- `/chat` is one full-page global chat surface and closes/hides the drawer on mount.
- `TopBar` is not mounted on `/chat` because `/chat` uses `AssistantShell`, not `AppChrome`.
- `frontend/src/app/(app)/pipelines/[pipelineId]/pipeline-classic-page.tsx` owns `isPipelineChatOpen`, renders header `Pipeline assistant` button, renders `PipelineChatPanel`, and has no `useChat()` import/call.
- `PipelineChatPanel` must not call `useChat()`.

`/chat` handoff:

- Replace the current drawer-opening placeholder by moving it to `frontend/src/app/(assistant)/chat/page.tsx` and making that route render `GlobalChatController` in full-page mode under `AssistantShell`.
- `GlobalChatDrawer` is mounted by `AppChrome` for normal app routes only.
- No `top-bar.tsx` `/chat` handling is required because `TopBar` is absent from `AssistantShell`.
- Navigating to `/chat` must close any open global drawer before rendering the full-page surface.
- Test asserts only one global chat surface is visible on `/chat`.

UI errors:

- Non-OK chat sends call `parseApiErrorResponse(response)` from `frontend/src/lib/api.ts`, display parsed message, and store code/details in UI message metadata.
- `chat-stream.ts` handles SSE `error` events by normalizing event data into the same `{code,message,details}` shape; HTTP response parsing remains owned by `api.ts`.
- Legacy SSE shim, tenant-scoped SSE send, tenant-scoped non-stream send, message history, session latest/list/create, and chat reset all use `parseApiErrorResponse(response)` for non-OK HTTP responses.
- Use a UI type:

```ts
type ChatUiMessage = ChatMessage & {
  metadata?: {
    errorCode?: string;
    errorDetails?: Record<string, unknown>;
    [key: string]: unknown;
  };
};
```

### Scoped Chat Migration

Physical backend owners:

- Migration: create the next available Flyway file after the rollout-control migration. If the repo head remains `V112__tenant_git_identity_scaffold.sql`, rollout control is `V113__rollout_control_plane.sql` and scoped chat is `V114__scoped_chat_sessions.sql`.
- Entity owners: `backend/src/main/java/com/pulse/chat/model/ChatSession.java` and `backend/src/main/java/com/pulse/chat/model/ChatMessage.java`.
- Repository owners: `backend/src/main/java/com/pulse/chat/repository/ChatSessionRepository.java` and `backend/src/main/java/com/pulse/chat/repository/ChatMessageRepository.java`.
- Service/controller owners: `backend/src/main/java/com/pulse/chat/service/ChatService.java` and `backend/src/main/java/com/pulse/chat/controller/ChatController.java`.
- Migration ordering: rollout control first, scoped chat second, workspace events third, Git scope/PR sync fourth, EBCDIC scoped sessions fifth. If the repo head remains `V112__tenant_git_identity_scaffold.sql`, the concrete sequence is `V113__rollout_control_plane.sql`, `V114__scoped_chat_sessions.sql`, `V115__workspace_events.sql`, `V116__git_repo_scope_and_pr_sync.sql`, and `V117__ebcdic_scoped_sessions.sql`; otherwise assign the next five consecutive available Flyway numbers in that same relative order.

After split:

- Add `chat_sessions.scope`, `version_id`, `metadata`.
- Add `chat_messages.version_id`, `correlation_id`, `turn_id`, `metadata`.
- Add checks/FKs/triggers for GLOBAL, PIPELINE, PIPELINE_VERSION consistency.
- Add `statesByScopeKey` in `ChatProvider`.
- Keep legacy shims until removal gates pass.

ChatProvider ownership after scoped migration:

- Before `statesByScopeKey`, `GlobalChatController`, `PipelineChatPanel`, and `WorkspaceAssistantPanel` own separate local session/message state as described above.
- After `statesByScopeKey`, `ChatProvider` becomes the canonical per-scope session/message store.
- Controllers no longer own canonical session/message arrays after that cutover; they select a scope key and dispatch provider actions.
- Controllers still own local UI-only state such as composer draft, attachment picker visibility, panel open state, scroll position, and transient optimistic UI controls.
- `GlobalChatDrawer` open/close state remains in `AssistantUiProvider`, not in the per-scope message store.
- `PipelineClassicPage` owns `isPipelineChatOpen`.
- `WorkspaceShellProvider` owns IDE panel layout state.

Provider actions after `statesByScopeKey`:

- `loadLatest(scopeDescriptor)`
- `loadMessages(scopeKey, cursor?, direction?)`
- `createSession(scopeDescriptor)`
- `sendMessage(scopeDescriptor, content, metadata)`
- `resetSession(scopeDescriptor)`
- `appendStreamEvent(scopeKey, event)`

This resolves the interim split: local controller state is temporary; `ChatProvider.statesByScopeKey` is the long-term canonical store.

Post-cutover send metadata contract:

```ts
type ChatSendMetadata = {
  workspaceTab?: "composition" | "code" | "dbt" | "orchestration" | "story" | "deployability" | "git";
  route: string;
  activity?: "explorer" | "composition" | "code" | "assistant" | "deploy" | "history";
  side?: "assistant" | "inspector" | "none";
  bottom?: "problems" | "runs" | "events" | "diffs" | "audit" | "none";
  anchor?: string;
  correlationId?: string;
  turnId?: string;
};
```

Ownership:

- `GlobalChatController` supplies `route` from the current pathname/search and omits `workspaceTab`, `activity`, `side`, `bottom`, and `anchor`.
- `PipelineChatPanel` supplies `route` from the classic pipeline URL and omits workspace-only keys.
- `WorkspaceAssistantPanel` reads canonical IDE route state from `PipelineRouteStateController`/`WorkspaceShellProvider` and supplies `workspaceTab`, `route`, `activity`, `side`, `bottom`, and `anchor`.
- `ChatProvider.sendMessage(scopeDescriptor, content, metadata)` passes these metadata fields unchanged into the tenant-scoped send body.
- `route` is required for every send. If a controller cannot compute it, send is blocked locally and no backend request is made.
- `workspaceTab` is required only for `PIPELINE_VERSION` sends from the IDE. Missing IDE `workspaceTab` returns local validation error before request.
- Backend treats `route` and workspace metadata as audit/navigation context, not as scope authority. Scope authority remains `scopeDescriptor` plus the persisted session.

Session/message invariants:

- `chat_sessions.scope=GLOBAL`: `pipeline_id` null and `version_id` null.
- `chat_sessions.scope=PIPELINE`: `pipeline_id` non-null and `version_id` null.
- `chat_sessions.scope=PIPELINE_VERSION`: `pipeline_id` non-null and `version_id` non-null.
- `chat_sessions.version_id` must belong to `chat_sessions.pipeline_id`, enforced by trigger.
- `chat_messages.session_id` determines message scope.
- For GLOBAL and PIPELINE sessions, `chat_messages.version_id` must be null.
- For PIPELINE_VERSION sessions, `chat_messages.version_id` must equal parent `chat_sessions.version_id`.
- `chat_messages.pipelineId` is not duplicated as a column in this plan; DTO pipelineId is derived from parent session.
- `turn_id` and `correlation_id` live in dedicated columns and are also duplicated in `chat_messages.metadata.turnId` and `chat_messages.metadata.correlationId` during migration for replay compatibility.
- Column values are authoritative when column and metadata disagree.

Physical schema details:

- `chat_sessions.scope varchar(32) not null default 'GLOBAL' check (scope in ('GLOBAL','PIPELINE','PIPELINE_VERSION'))`.
- `chat_sessions.version_id varchar(26) null references pipeline_versions(id) on delete restrict`.
- `chat_sessions.metadata jsonb not null default '{}'`.
- Existing `chat_sessions.pipeline_id` remains nullable `varchar(26)` and is constrained by scope rules.
- `chat_messages.version_id varchar(26) null references pipeline_versions(id) on delete restrict`.
- `chat_messages.turn_id varchar(96) null`.
- `chat_messages.correlation_id varchar(96) null`.
- `chat_messages.metadata jsonb not null default '{}'`.
- Indexes:
  - `idx_chat_sessions_scope_latest` on `(tenant_id, user_id, scope, pipeline_id, version_id, updated_at desc, id desc)`.
  - `idx_chat_sessions_scope_list` on `(tenant_id, user_id, scope, pipeline_id, version_id, created_at desc, id desc)`.
  - `idx_chat_messages_session_cursor` on `(session_id, created_at desc, id desc)`.
  - `idx_chat_messages_turn` on `(tenant_id, turn_id)` where `turn_id is not null`.
  - `idx_chat_messages_correlation` on `(tenant_id, correlation_id)` where `correlation_id is not null`.
- No uniqueness constraint prevents multiple sessions per `{tenant,user,scope,pipeline,version}`; latest is selected by `updated_at desc, id desc`.
- Trigger `chat_session_scope_guard` enforces scope nullability and verifies `version_id` belongs to `pipeline_id` for PIPELINE_VERSION.
- Trigger or service guard `chat_message_scope_guard` enforces message `version_id` consistency with parent session.

Backfill:

- Existing sessions with valid non-null `pipeline_id` become `PIPELINE`, `version_id=null`.
- Existing sessions with null `pipeline_id` become `GLOBAL`.
- Existing sessions whose pipeline no longer exists become `GLOBAL`, clear `pipeline_id`, and store `metadata.legacyPipelineState='deleted_pipeline'` plus `metadata.legacyPipelineId`.
- No existing session becomes `PIPELINE_VERSION` automatically.
- Existing messages inherit parent session scope for DTO output.
- Existing message `version_id`, `turn_id`, and `correlation_id` columns are null.
- Adapter may read legacy turn/correlation from metadata when present, otherwise emits null.

Post-migration session API:

```http
POST /api/v1/tenants/{tenantId}/chat/sessions
GET /api/v1/tenants/{tenantId}/chat/sessions/latest?userId=&scope=&pipelineId=&versionId=
GET /api/v1/tenants/{tenantId}/chat/sessions?userId=&scope=&pipelineId=&versionId=&limit=&cursor=
```

Create body:

```json
{
  "userId": "...",
  "scope": "GLOBAL|PIPELINE|PIPELINE_VERSION",
  "pipelineId": "...",
  "versionId": "...",
  "title": "...",
  "metadata": {}
}
```

Scope validation:

- GLOBAL forbids `pipelineId` and `versionId`.
- PIPELINE requires `pipelineId` and forbids `versionId`.
- PIPELINE_VERSION requires both `pipelineId` and `versionId`.
- `versionId` must belong to `pipelineId`, or the server returns `400 CHAT_VERSION_PIPELINE_MISMATCH`.
- Missing required fields return `CHAT_PIPELINE_REQUIRED` or `CHAT_VERSION_REQUIRED`.
- Forbidden fields return `CHAT_PIPELINE_FORBIDDEN` or `CHAT_VERSION_FORBIDDEN`.

Latest behavior:

- GLOBAL latest filters tenant, user, scope GLOBAL.
- PIPELINE latest filters tenant, user, scope PIPELINE, exact pipeline.
- PIPELINE_VERSION latest filters tenant, user, scope PIPELINE_VERSION, exact pipeline and exact version.
- Latest order is `updatedAt DESC, id DESC`.
- 204 on none.
- There is no fallback between GLOBAL, PIPELINE, and PIPELINE_VERSION.

Session list behavior:

- Consumer: chat history/session switcher surfaces in `GlobalChatController`, `PipelineChatPanel`, `WorkspaceAssistantPanel`, and later `ChatProvider.statesByScopeKey`.
- `limit` default 50, max 100.
- Order is `updatedAt DESC, id DESC`.
- Cursor is base64url JSON `{ "updatedAt": "ISO-8601", "id": "..." }`.
- Cursor predicate is `(updatedAt < cursor.updatedAt) OR (updatedAt = cursor.updatedAt AND id < cursor.id)`.
- Empty result returns `200` with `{ "items": [], "nextCursor": null }`, never 204.
- Scope filters are exact and have no fallback.
- `nextCursor` is derived from the last returned session when more rows exist.

Session list response:

```json
{
  "items": [
    {
      "id": "...",
      "tenantId": "...",
      "userId": "...",
      "scope": "GLOBAL|PIPELINE|PIPELINE_VERSION",
      "pipelineId": "...",
      "versionId": "...",
      "title": "...",
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601",
      "metadata": {}
    }
  ],
  "nextCursor": null
}
```

Frontend restore keys after scoped migration:

- GLOBAL: `chat:global:{tenantId}:{userId}`.
- PIPELINE: `chat:pipeline:{tenantId}:{userId}:{pipelineId}`.
- PIPELINE_VERSION: `chat:pipeline-version:{tenantId}:{userId}:{pipelineId}:{versionId}`.

IDE assistant bootstrap:

- `WorkspaceAssistantPanel` is disabled until `PipelineRouteStateController.selectedVersionId` is non-null and canonical route replacement is complete.
- On first send, it creates or loads latest with `scope=PIPELINE_VERSION`, `pipelineId`, and `versionId`.
- It never reuses a PIPELINE or GLOBAL session.
- Switching versions switches the restore key and loads/creates that version's own session.

`ChatScopeState` fields:

- `key`
- `scope`
- `tenantId`
- `userId`
- `pipelineId?`
- `versionId?`
- `session?`
- `messagesById`
- `orderedMessageIds`
- `pages`
- `nextCursor?`
- `isLoadingSession`
- `isLoadingMessages`
- `isStreaming`
- `streamingTurns`
- `optimisticIndex`
- `status`
- `error?`
- `lastLoadedAt?`

Scoped migration tests:

- GLOBAL latest does not return PIPELINE or PIPELINE_VERSION.
- PIPELINE latest does not return GLOBAL or PIPELINE_VERSION.
- PIPELINE_VERSION latest does not return PIPELINE for the same pipeline.
- Version switch changes key and session.
- No-version IDE assistant cannot send.
- Version mismatch returns `CHAT_VERSION_PIPELINE_MISMATCH`.

### Chat Tool Payloads

Use `ChatMessageDTO` before changing entity JSON shape.

Storage decision:

- DB columns remain `jsonb`.
- Entity boundary moves to `JsonNode toolCalls` and `JsonNode toolResults`.
- New rows default to `[]`.
- `ChatToolPayloadAdapter` accepts `JsonNode` and normalizes legacy object-root, canonical array-root, null/missing.
- Canonical writer stores array-root JSON only after DTO shield and adapter readers pass.

Canonical rows:

- Assistant: `tool_calls = [ToolCallDTO...]`, `tool_results = []`.
- Tool: `tool_calls = []`, `tool_results = [ToolResultDTO]`.

Adapter coverage:

- History replay.
- `tool_call_id`.
- Facts.
- Prompt memory.
- Phase detection.
- Message/facts endpoints.

Legacy-to-canonical normalization:

| Legacy/current shape | Canonical output | Consumers |
| --- | --- | --- |
| Persisted role `assistant`, `user`, `tool`, `system` lowercase | DTO role `ASSISTANT`, `USER`, `TOOL`, `SYSTEM`; provider replay converts back to provider-required lowercase where needed | Message DTOs, history replay |
| Assistant row `tool_calls = { "calls": [ ...provider tool calls... ] }` | `toolCalls[]` with `id`, `name`, parsed `arguments`, `status` default `pending`; `toolResults=[]` | History replay, message list |
| Tool row `tool_calls = { "tool_call_id": "call_..." }` and `tool_results = { "tool_name": "...", "facts": [...] }` | `toolResults[]` one item with `toolCallId`, `toolName`, `status`, `result.facts`; `toolCalls=[]` | History replay, facts, PhaseDetector |
| Tool row `tool_results = { "tool_call_id": "...", ... }` | `toolResults[]` one item; `toolCallId` from `tool_call_id`; remaining map in `result.legacyPayload` unless mapped to known fields | History replay, facts |
| Canonical array-root `tool_calls = [ ... ]` | Return as canonical `toolCalls[]` after validation/defaulting missing status | New rows |
| Canonical array-root `tool_results = [ ... ]` | Return as canonical `toolResults[]` after validation/defaulting timestamp when missing | New rows |
| Null/missing tool calls/results | Empty arrays | All consumers |

History replay contract:

- Assistant messages sent back to the LLM provider use lowercase role `assistant` and provider-shaped `tool_calls` reconstructed from canonical `toolCalls[]`.
- Tool messages sent back to the LLM provider use lowercase role `tool` and `tool_call_id` reconstructed from canonical `ToolResultDTO.toolCallId`.
- If a legacy tool row lacks a usable `tool_call_id`, adapter generates `legacy:{messageId}:0` and uses it consistently for that replay pass.

Facts and phase detection:

- `ChatService.getSessionFacts` reads only adapter-normalized `ToolResultDTO.result.facts`.
- `PhaseDetector.getLastToolName` reads the last adapter-normalized `toolName`.
- `PhaseDetector.getLastBlueprintKey` reads adapter-normalized facts/result values, not raw map keys.
- Direct reads such as `toolResults.get("facts")`, `toolResults.get("tool_name")`, `toolCalls.get("calls")`, or `toolCalls.get("tool_call_id")` are allowed only inside `ChatToolPayloadAdapter` and tests.

### Chat Tool Registry

Plan/apply alignment:

- `ChatToolRegistry` must expose tool metadata using the ARCH-009/ARCH-018 taxonomy: canonical reads, side-effect-free `plan_*` tools, `apply_plan(plan_id)`, and UI intents.
- Legacy `create_*`, `propose_*`, `configure_*`, `update_*`, `wire_*`, `remove_*`, or `apply_*` tool names may remain only as compatibility aliases that create plan records or return blocked structured tool results. They must not mutate product entities directly.
- `apply_plan(plan_id)` is the only generic chat mutation tool. It requires a plan approved for the same session/scope, executes through `PlanService`, and emits structured SSE/tool-result fields `mutationApplied`, `planId`, `commandIds`, `affectedEntities`, `refreshHints`, and `message`.
- Frontend chat controllers refresh UI state from `refreshHints` and `affectedEntities`; they must not infer mutation success solely from a tool name or from assistant prose.
- Read tools that expose readiness, package, runtime projection, table-contract, storage-preview, connector-schema, or deployability facts must read the same backend authority surfaces used by panels. Chat cannot maintain separate path/deploy/readiness inference.

Create `ChatToolRegistry` as the single source of tool metadata:

- `name`
- `requiredScope`
- `requiresVersion`
- `schema`
- `description`
- `category`
- `navigationBehavior`

`ChatTools` delegates to the registry. `ChatService` exposes tools from registry based on `ChatTurnContext.scope`. `ChatToolExecutor` uses the same registry for preflight.

Chat execution signatures:

- `ChatController` builds `ChatTurnContext` before calling service methods.
- `ChatService.sendMessage(ChatTurnContext context, String content)` is the canonical send entry point after migration.
- `ChatService.buildSystemPrompt(ChatTurnContext context)` receives context, not loose tenant/pipeline arguments.
- `PhaseDetector.detect(ChatTurnContext context)` receives context and may read `context.versionId` only when scope is PIPELINE_VERSION.
- `ChatToolExecutor.execute(String toolName, Map<String,Object> args, ChatTurnContext context)` is the canonical executor signature.

Version authority threading:

- Tool bodies receive version authority from `ChatTurnContext.versionId`, not from tool args.
- During migration, schemas may still expose optional `version_id`; executor preflight compares it to context.
- If a PIPELINE_VERSION tool is invoked with no `context.versionId`, return `CHAT_VERSION_REQUIRED` before tool body execution.
- If args contain `version_id` and it differs from `context.versionId`, return `CHAT_VERSION_MISMATCH` before tool body execution.
- If args omit `version_id`, executor injects/uses `context.versionId` internally for versioned service calls.
- Tool args `pipeline_id` must match `context.pipelineId` when context has pipelineId, or return `CHAT_PIPELINE_MISMATCH`.
- No versioned tool may call `pipeline.getActiveVersionId()` or latest-version lookup for mutation/read authority.

Classic pipeline chat is PIPELINE aggregate:

- It can answer pipeline-level questions.
- Versioned tools are hidden.
- If the model emits a hidden versioned tool, executor blocks with canonical `CHAT_VERSION_REQUIRED` tool result and Open IDE CTA.

IDE assistant remains disabled until `PipelineVersionAuthorityGate` passes.

Initial registry scope matrix:

Tool names below are target canonical names after the prompt/tool rewrite. During migration, legacy direct-mutator names can map to the matching `plan_*` row only when they remain side-effect-free except for plan records.

| Tool | Required scope | Requires version | Visible in GLOBAL | Visible in PIPELINE | Visible in PIPELINE_VERSION |
| --- | --- | --- | --- | --- | --- |
| `navigate_ui` | GLOBAL | false | yes | yes | yes |
| `list_data_sources` | GLOBAL | false | yes | yes | yes |
| `list_connectors` | GLOBAL | false | yes | yes | yes |
| `list_datasets` | GLOBAL | false | yes | yes | yes |
| `plan_create_data_source` | GLOBAL | false | yes | yes | yes |
| `plan_create_connector` | GLOBAL | false | yes | yes | yes |
| `list_domains` | GLOBAL | false | yes | yes | yes |
| `plan_create_domain` | GLOBAL | false | yes | yes | yes |
| `plan_create_dataset` | GLOBAL | false | yes | yes | yes |
| `list_blueprints` | GLOBAL | false | yes | yes | yes |
| `get_blueprint_detail` | GLOBAL | false | yes | yes | yes |
| `list_sink_targets` | GLOBAL | false | yes | yes | yes |
| `plan_create_sink_target` | GLOBAL | false | yes | yes | yes |
| `view_code_examples` | GLOBAL | false | yes | yes | yes |
| `get_connector_type_schema` | GLOBAL | false | yes | yes | yes |
| `request_credential_attach` | GLOBAL | false | yes | yes | yes |
| `get_storage_paths` | GLOBAL | false | yes | yes | yes |
| `get_runtime_projection_preview` | PIPELINE_VERSION | true | no | no | yes |
| `get_table_contracts` | PIPELINE_VERSION | true | no | no | yes |
| `plan_create_pipeline` | GLOBAL | false | yes | yes | yes |
| `list_dbt_assets` | PIPELINE_VERSION | true | no | no | yes |
| `find_dbt_reuse_candidate` | PIPELINE_VERSION | true | no | no | yes |
| `get_composition` | PIPELINE_VERSION | true | no | no | yes |
| `plan_add_instance` | PIPELINE_VERSION | true | no | no | yes |
| `plan_wiring` | PIPELINE_VERSION | true | no | no | yes |
| `plan_set_canonical_fields` | PIPELINE_VERSION | true | no | no | yes |
| `get_upstream_schema` | PIPELINE_VERSION | true | no | no | yes |
| `evaluate_dq_readiness` | PIPELINE_VERSION | true | no | no | yes |
| `suggest_dq_expectations` | PIPELINE_VERSION | true | no | no | yes |
| `plan_configure_step` | PIPELINE_VERSION | true | no | no | yes |
| `plan_update_pipeline_orchestration` | PIPELINE_VERSION | true | no | no | yes |
| `plan_wire_ports` | PIPELINE_VERSION | true | no | no | yes |
| `plan_remove_step` | PIPELINE_VERSION | true | no | no | yes |
| `plan_apply_dq_expectations` | PIPELINE_VERSION | true | no | no | yes |
| `apply_plan` | GLOBAL | false | yes | yes | yes |

Scope semantics:

- GLOBAL tools may run from any chat scope because they do not read or mutate pipeline-version state.
- PIPELINE tools require a pipeline context but not a version. They may read or mutate aggregate pipeline-level resources.
- `plan_create_pipeline` is GLOBAL because it creates a plan for pipeline context and must be available before a pipeline exists. Actual creation happens only through `apply_plan(plan_id)` after approval.
- PIPELINE_VERSION tools require `ChatTurnContext.versionId` and are hidden from GLOBAL and PIPELINE tool definitions.
- If a model emits a hidden or out-of-scope tool, executor preflight blocks it before tool body execution.

Blocked result:

```json
{
  "toolCallId": "call_...",
  "toolName": "plan_wiring",
  "status": "error",
  "result": null,
  "error": {
    "code": "CHAT_VERSION_REQUIRED",
    "message": "Open this pipeline in the IDE to work on a specific version.",
    "details": {
      "recovery": {
        "action": "OPEN_IDE",
        "pipelineId": "...",
        "latestVersionId": "..."
      }
    }
  },
  "timestamp": "ISO-8601"
}
```

If a tool requires PIPELINE scope and no pipeline exists, return `CHAT_PIPELINE_REQUIRED` with the same canonical ToolResultDTO error shape and no mutation.

## Workspace Events

Primary owners:

- Migration: create the next available Flyway file after scoped chat. If the repo head remains `V112__tenant_git_identity_scaffold.sql`, this is `backend/src/main/resources/db/migration/V115__workspace_events.sql`.
- Entity: new `backend/src/main/java/com/pulse/workspace/model/WorkspaceEvent.java`.
- Repository: new `backend/src/main/java/com/pulse/workspace/repository/WorkspaceEventRepository.java`.
- Service: new `backend/src/main/java/com/pulse/workspace/service/WorkspaceEventService.java`.
- Read controller: new `backend/src/main/java/com/pulse/workspace/controller/WorkspaceEventController.java`.
- `backend/src/main/java/com/pulse/command/controller/CommandLogController.java` is not extended for the canonical workspace-events API. It remains the legacy command log surface until replaced by callers.
- Backend emitters: chat services, codegen services, deploy/package services, composition services, `StoryGenerationService`, and the COBOL/EBCDIC service path behind `backend/src/main/java/com/pulse/cobol/controller/CobolDiscoveryController.java`.
- Frontend event/audit panel: IDE workspace components mounted by `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx`.
- Existing command log page after route-group migration: `frontend/src/app/(app)/commands/page.tsx`.

Command log coexistence:

- Workspace events are the canonical product audit surface for domain/product state changes introduced or migrated by this plan.
- Existing `CommandLogController` and `frontend/src/app/(app)/commands/page.tsx` remain the legacy command execution/history surface during migration. They are not used by IDE events/audit panels.
- During migration, command-driven mutations that still pass through `CommandService` must emit workspace events through the canonical service methods in addition to any existing command-log records. The command log records command invocation; workspace events record product state evidence.
- Command-log records for migrated product mutations store `correlation_id`, `canonical_idempotency_key`, and `canonical_event_id` so operators can trace from legacy command history to canonical workspace audit without duplicating events.
- `CommandLogController` may display those bridge fields, but it remains read-only history. It must not become a workspace-events reader or write surface.
- If a command path performs no product mutation, it may remain command-log-only.
- Retirement rule: a product mutation is considered migrated only when its canonical endpoint/service writes workspace events, compatibility command paths delegate to that same service, and IDE events/audit panels can prove the mutation from workspace events without reading `CommandLogController`.

Sequencing:

- The workspace-events migration, entity, repository, service, and write API land before any chat/story/composition/codegen/deploy/Git/EBCDIC emitter is enabled.
- Phase 1 may record `CHAT_*` and `STORY_GENERATED` only after `WorkspaceEventService.record` exists.
- Phase 3+ emitters depend on the same service.
- The Phase 5 events/audit panel is the UI/read-surface rollout; it does not delay the backend event foundation.

Table:

- `id varchar(26) primary key`
- `tenant_id varchar(26) not null`
- `scope varchar(32) not null check scope in ('GLOBAL','DOMAIN','PIPELINE','PIPELINE_VERSION')`
- `domain_id varchar(26) null references domains(id) on delete restrict`
- `pipeline_id varchar(26) null references pipelines(id) on delete restrict`
- `version_id varchar(26) null references pipeline_versions(id) on delete restrict`
- `correlation_id varchar(96) null`
- `idempotency_key varchar(160) null`
- `event_type varchar(64) not null`
- `actor_type varchar(32) not null check actor_type in ('USER','ASSISTANT','SYSTEM')`
- `actor_id varchar(64) null`
- `target_type varchar(64) not null`
- `target_id varchar(64) null`
- `summary text not null`
- `payload jsonb not null default '{}'`
- `payload_hash varchar(64) not null`
- `created_at timestamptz not null default now()`
- Unique partial index `ux_workspace_events_idempotency` on `(tenant_id, idempotency_key)` where `idempotency_key is not null`.

Scope checks:

- GLOBAL: pipeline and version null.
- DOMAIN: domain non-null, pipeline and version null.
- PIPELINE: pipeline non-null, version null.
- PIPELINE_VERSION: pipeline and version non-null.

Version integrity:

- Use a `BEFORE INSERT OR UPDATE` trigger.
- For DOMAIN, validate `domain_id` belongs to the row tenant.
- For PIPELINE, validate `pipeline_id` belongs to the row tenant and clear/forbid `domain_id` unless this plan later needs denormalized domain evidence.
- For PIPELINE_VERSION, select `pipeline_versions.pipeline_id` for `NEW.version_id`; raise check violation if missing or not equal to `NEW.pipeline_id`.
- Tests cover valid domain, wrong-tenant domain, valid pipeline, valid version, wrong-pipeline version, missing version, and update that breaks relationship.

Read API:

```http
GET /api/v1/tenants/{tenantId}/workspace/events?scope=&domainId=&pipelineId=&versionId=&correlationId=&eventType=&limit=&cursor=
GET /api/v1/tenants/{tenantId}/workspace/events/{eventId}
```

Rules:

- Default limit 50, max 200.
- Cursor is base64url JSON `{createdAt,id}`.
- Order `createdAt DESC, id DESC`.
- If scope omitted and correlationId present, query tenant plus correlation across scopes.
- If scope omitted and no correlationId, return 400 `WORKSPACE_EVENT_SCOPE_REQUIRED`.
- `GET .../workspace/events/{eventId}` returns one `WorkspaceEventDTO` after tenant validation. It returns `404 WORKSPACE_EVENT_NOT_FOUND` when the event does not exist or is outside the tenant.
- `eventType` may appear multiple times or as a comma-separated value. Repeated and comma-separated forms are normalized into a de-duplicated ordered set before validation.

Response:

```json
{
  "items": [
    {
      "id": "...",
      "timestamp": "ISO-8601",
      "scope": "PIPELINE_VERSION",
      "pipelineId": "...",
      "versionId": "...",
      "correlationId": "...",
      "eventType": "CODEGEN_COMPLETED",
      "actor": {
        "type": "USER",
        "id": "..."
      },
      "target": {
        "type": "GENERATION_RUN",
        "id": "..."
      },
      "summary": "Generated 14 artifacts",
      "payload": {}
    }
  ],
  "nextCursor": null
}
```

Formal DTO:

```ts
type WorkspaceEventDTO = {
  id: string;
  timestamp: string;
  scope: "GLOBAL" | "DOMAIN" | "PIPELINE" | "PIPELINE_VERSION";
  tenantId: string;
  domainId: string | null;
  pipelineId: string | null;
  versionId: string | null;
  correlationId: string | null;
  eventType: string;
  actor: {
    type: "USER" | "ASSISTANT" | "SYSTEM";
    id: string | null;
  };
  target: {
    type: string;
    id: string | null;
  };
  summary: string;
  payload: Record<string, unknown>;
};
```

Empty response:

```json
{
  "items": [],
  "nextCursor": null
}
```

Filter validation:

- `scope=GLOBAL` forbids `pipelineId` and `versionId`.
- `scope=DOMAIN` requires `domainId` and forbids `pipelineId` and `versionId`.
- `scope=PIPELINE` requires `pipelineId` and forbids `versionId`.
- `scope=PIPELINE_VERSION` requires both `pipelineId` and `versionId`.
- `correlationId` may be combined with scope filters.
- Each requested `eventType` must be one of the declared event types.
- Multiple event types are OR filters. The default audit panel sends the audit event-type set as repeated `eventType` query params and does not client-filter a broader feed.
- Invalid scope/filter combinations return stable `{code,message,details}` errors.

Errors:

```json
{
  "code": "WORKSPACE_EVENT_SCOPE_REQUIRED",
  "message": "scope or correlationId is required",
  "details": {}
}
```

```json
{
  "code": "WORKSPACE_EVENT_NOT_FOUND",
  "message": "Workspace event not found",
  "details": {
    "eventId": "..."
  }
}
```

```json
{
  "code": "WORKSPACE_EVENT_SCOPE_INVALID",
  "message": "Event query scope is invalid",
  "details": {
    "scope": "PIPELINE",
    "pipelineId": null,
    "versionId": null
  }
}
```

Event catalog:

| Event type | Scope | Emitting service/phase | Target | Minimum payload |
| --- | --- | --- | --- | --- |
| `CHAT_TURN_STARTED` | GLOBAL, PIPELINE, PIPELINE_VERSION | ChatService, Phase 1 | `CHAT_SESSION` | `{ "sessionId": "...", "turnId": "...", "route": "..." }` |
| `CHAT_TOOL_STARTED` | By registry scope | ChatToolExecutor, Phase 1 | `CHAT_TOOL_CALL` | `{ "sessionId": "...", "turnId": "...", "toolCallId": "...", "toolName": "..." }` |
| `CHAT_TOOL_COMPLETED` | Same as matching start | ChatToolExecutor, Phase 1 | `CHAT_TOOL_CALL` | `{ "sessionId": "...", "turnId": "...", "toolCallId": "...", "toolName": "...", "status": "success" }` |
| `CHAT_TOOL_FAILED` | Same as matching start; attempted turn scope for preflight | ChatToolExecutor, Phase 1 | `CHAT_TOOL_CALL` | `{ "sessionId": "...", "turnId": "...", "toolCallId": "...", "toolName": "...", "status": "error", "errorCode": "..." }` |
| `STORY_GENERATED` | PIPELINE for aggregate, PIPELINE_VERSION for version story | StoryGenerationService, Phase 1/3 | `STORY` | `{ "sourceScope": "PIPELINE_AGGREGATE|PIPELINE_VERSION", "storyScope": "...", "versionId": "..." }` |
| `VERSION_CREATED` | PIPELINE_VERSION | PipelineService, Phase 1/3 | `PIPELINE_VERSION` | `{ "versionId": "...", "baseVersionId": null | "...", "revision": 1, "seeded": true | false }` |
| `VERSION_LIFECYCLE_CHANGED` | PIPELINE_VERSION | PipelineService, Phase 1/3 | `PIPELINE_VERSION` | `{ "versionId": "...", "fromStage": "...", "toStage": "..." }` |
| `COMPOSITION_CHANGED` | PIPELINE_VERSION | CompositionService, Phase 3 | `PIPELINE_VERSION` or `COMPOSITION_NODE` | `{ "changeType": "add_node|update_node|delete_node|wire|unwire|orchestration_update", "operationId": "...", "nodeId": "...", "edgeId": "..." }` |
| `DQ_RECOMMENDATION_APPLIED` | PIPELINE_VERSION | DQ service/tool executor, Phase 4/5 | `DQ_RECOMMENDATION` | `{ "recommendationId": "...", "affectedNodeId": "...", "resultingScore": 0 }` |
| `CODEGEN_STARTED` | PIPELINE_VERSION | CodeGenerationService, Phase 4 | `GENERATION_RUN` | `{ "runId": "...", "triggerType": "...", "versionId": "..." }` |
| `CODEGEN_COMPLETED` | PIPELINE_VERSION | CodeGenerationService, Phase 4 | `GENERATION_RUN` | `{ "runId": "...", "artifactCount": 0, "versionId": "..." }` |
| `CODEGEN_FAILED` | PIPELINE_VERSION | CodeGenerationService, Phase 4 | `GENERATION_RUN` | `{ "runId": "...", "errorCode": "...", "message": "..." }` |
| `ARTIFACT_EDITED` | PIPELINE_VERSION | Artifact save, Phase 4 | `GENERATED_ARTIFACT` | `{ "artifactId": "...", "runId": "...", "filePath": "...", "contentHash": "..." }` |
| `DEPLOYABILITY_ASSESSED` | PIPELINE_VERSION | Deploy/packaging service, Phase 4/5 | `DEPLOYABILITY_ASSESSMENT` | `{ "assessmentId": "...", "status": "PASS|WARN|FAIL" }` |
| `PACKAGE_CREATED` | PIPELINE_VERSION | DeployController/packaging service, Phase 4/5 | `PACKAGE` | `{ "packageId": "...", "versionId": "...", "packageType": "...", "status": "..." }` |
| `PACKAGE_FAILED` | PIPELINE_VERSION | DeployController/packaging service, Phase 4/5 | `PACKAGE` | `{ "packageId": "...", "versionId": "...", "errorCode": "...", "message": "..." }` |
| `DEPLOYMENT_REQUESTED` | PIPELINE_VERSION | DeployService, Phase 4/5 | `DEPLOYMENT` | `{ "deploymentId": "...", "environment": "...", "packageId": "..." }` |
| `APPROVAL_REQUESTED` | PIPELINE_VERSION | DeployService, Phase 4/5 | `APPROVAL_REQUEST` | `{ "approvalId": "...", "deploymentId": "...", "packageId": "...", "versionId": "..." }` |
| `APPROVAL_DECIDED` | PIPELINE_VERSION | DeployService, Phase 4/5 | `APPROVAL` | `{ "approvalId": "...", "deploymentId": "...", "decision": "APPROVE|REJECT", "versionId": "..." }` |
| `GIT_REPO_ATTACHED` | PIPELINE for pipeline attach, DOMAIN for domain attach, GLOBAL for tenant attach | GitController/Git service, Phase 5 | `GIT_REPO` | `{ "repoId": "...", "attachmentScope": "PIPELINE|DOMAIN|TENANT", "pipelineId": "...", "domainId": "..." }` |
| `GIT_REPO_UPDATED` | Same as attachment scope | GitController/Git service, Phase 5 | `GIT_REPO` | `{ "repoId": "...", "attachmentScope": "PIPELINE|DOMAIN|TENANT", "changedFields": [] }` |
| `GIT_REPO_DETACHED` | Same as attachment scope | GitController/Git service, Phase 5 | `GIT_REPO` | `{ "repoId": "...", "attachmentScope": "PIPELINE|DOMAIN", "newEffectiveRepoId": "..." }` |
| `GIT_BRANCH_CHANGED` | PIPELINE_VERSION when initiated from versioned IDE/classic, PIPELINE for aggregate pipeline Git panel, GLOBAL for tenant settings | GitController/Git service, Phase 5 | `GIT_REPO` | `{ "repoId": "...", "fromBranch": "...", "toBranch": "...", "created": false }` |
| `GIT_PULL_REQUEST_CREATED` | PIPELINE_VERSION | GitController/Git service, Phase 5 | `PULL_REQUEST` | `{ "pullRequestId": "...", "repoId": "...", "versionId": "...", "status": "OPEN" }` |
| `GIT_PULL_REQUEST_STATUS_CHANGED` | PIPELINE_VERSION | GitController/Git service, Phase 5 | `PULL_REQUEST` | `{ "pullRequestId": "...", "repoId": "...", "versionId": "...", "fromStatus": "...", "toStatus": "...", "mergeCommitHash": "..." }` |
| `EBCDIC_SESSION_CREATED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_SESSION` | `{ "sessionId": "..." }` |
| `EBCDIC_COPYBOOK_UPLOADED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_ARTIFACT` | `{ "sessionId": "...", "artifactId": "...", "filename": "..." }` |
| `EBCDIC_DATA_UPLOADED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_ARTIFACT` | `{ "sessionId": "...", "artifactId": "...", "filename": "..." }` |
| `EBCDIC_PREVIEW_STARTED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "preview" }` |
| `EBCDIC_PREVIEW_COMPLETED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "preview", "status": "COMPLETED" }` |
| `EBCDIC_PREVIEW_FAILED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "preview", "status": "FAILED", "errorCode": "..." }` |
| `EBCDIC_PROFILE_STARTED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "profile" }` |
| `EBCDIC_PROFILE_COMPLETED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "profile", "status": "COMPLETED" }` |
| `EBCDIC_PROFILE_FAILED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "profile", "status": "FAILED", "errorCode": "..." }` |
| `EBCDIC_RUN_CANCELLED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_RUN` | `{ "sessionId": "...", "runId": "...", "runType": "...", "status": "CANCELLED" }` |
| `EBCDIC_MESSAGE_SENT` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `EBCDIC_SESSION` | `{ "sessionId": "...", "messageId": "..." }` |
| `EBCDIC_PROFILE_SAVED` | GLOBAL or PIPELINE | CobolDiscoveryService, Phase 5 | `COBOL_PROFILE` | `{ "sessionId": "...", "runId": "...", "profileId": "..." }` |

Payload rules:

- Minimum payload keys are required; additional payload keys are allowed.
- EBCDIC events use PIPELINE scope only when launched from a pipeline context. Otherwise they use GLOBAL scope with null pipeline/version.
- A future version-tied EBCDIC flow must explicitly move its events to PIPELINE_VERSION.

Event durability:

| Emitter/event family | Durability mode | Failure behavior |
| --- | --- | --- |
| `CHAT_TURN_STARTED`, `CHAT_TOOL_STARTED`, `CHAT_TOOL_COMPLETED`, `CHAT_TOOL_FAILED` | best-effort | Log structured warning with `correlationId`/`turnId`, continue chat stream, and include `eventWriteFailed:true` in server logs only. Do not fail chat or tool mutation after the tool service has succeeded. |
| `STORY_GENERATED` | best-effort | Return story response; log warning. Story generation is read/generate UI output and must not fail solely because audit write failed. |
| `VERSION_CREATED`, `VERSION_LIFECYCLE_CHANGED` | transactional required | If event write fails, roll back the version mutation and return `500 WORKSPACE_EVENT_WRITE_FAILED`; version lineage/lifecycle changes require durable audit rows. |
| `COMPOSITION_CHANGED`, `DQ_RECOMMENDATION_APPLIED` | transactional required | If event write fails, roll back the composition/DQ mutation and return `500 WORKSPACE_EVENT_WRITE_FAILED` because workbench edits require auditable mutation evidence. |
| `CODEGEN_STARTED`, `CODEGEN_COMPLETED`, `CODEGEN_FAILED` | transactional for run state transition | If event write fails during run creation or terminal state persistence, fail/roll back that state transition where possible; otherwise mark run metadata `eventWriteFailed:true` and enqueue one async retry. |
| `ARTIFACT_EDITED` | transactional required | If event write fails, reject artifact save with `500 WORKSPACE_EVENT_WRITE_FAILED`; generated artifact edits must be auditable. |
| `DEPLOYABILITY_ASSESSED` | best-effort with retry | Persist assessment, log warning, enqueue one retry because deployability can be recomputed and is not itself an approval action. |
| `PACKAGE_CREATED`, `PACKAGE_FAILED`, `DEPLOYMENT_REQUESTED`, `APPROVAL_REQUESTED`, `APPROVAL_DECIDED` | transactional required | If event write fails, fail/roll back the package/deploy/approval mutation and return `500 WORKSPACE_EVENT_WRITE_FAILED`. Release-governance actions require durable audit rows. |
| `GIT_REPO_ATTACHED`, `GIT_REPO_UPDATED`, `GIT_REPO_DETACHED`, `GIT_BRANCH_CHANGED` | transactional required, except hosted provider branch creation caveat | Repo attach/update/detach/current-branch local mutation and event write happen in one transaction. If event write fails, roll back local DB work. Hosted provider branch creation may have happened before the local transaction; return `providerBranchCreated=true` when compensation is needed. |
| `GIT_PULL_REQUEST_CREATED`, `GIT_PULL_REQUEST_STATUS_CHANGED` | transactional before provider side effect | Local PR row/status intent and event write happen before provider calls. If event write fails, roll back local DB work and return `500 WORKSPACE_EVENT_WRITE_FAILED` with no provider call. Provider failures after event commit are recorded as provider-sync failures and are retryable by idempotency key. |
| `EBCDIC_SESSION_CREATED`, `EBCDIC_MESSAGE_SENT` | best-effort | Persist session/message response, log warning, no retry. |
| `EBCDIC_COPYBOOK_UPLOADED`, `EBCDIC_DATA_UPLOADED` | transactional required | If event write fails, reject upload and roll back persisted artifact/file metadata where possible. |
| `EBCDIC_PREVIEW_*`, `EBCDIC_PROFILE_*`, `EBCDIC_RUN_CANCELLED` | best-effort with retry | Persist run state, log warning, enqueue one async retry keyed by run id and event type. |
| `EBCDIC_PROFILE_SAVED` | transactional required | If event write fails, fail profile save with `500 WORKSPACE_EVENT_WRITE_FAILED`. |

`WORKSPACE_EVENT_WRITE_FAILED` body is `{ "code": "WORKSPACE_EVENT_WRITE_FAILED", "message": "Workspace event could not be recorded", "details": { "eventType": "...", "correlationId": "...", "idempotencyKey": "..." } }`.

Workspace event idempotency:

- `WorkspaceEventService.record` accepts explicit `idempotencyKey`; callers must not rely on service-derived keys for mutating workflows.
- The key is unique per tenant and stored on `workspace_events.idempotency_key`.
- Mutation services derive keys from the stable mutation operation id, not only from target and correlation.
- Required formulas:
  - Chat turn start: `chat:turn-start:{turnId}`.
  - Chat tool event: `chat:tool:{toolCallId}:{eventType}`.
  - Story generation: `story:{storyId}:{eventType}`.
  - Version create: `version:create:{newVersionId}`.
  - Version lifecycle change: `version:lifecycle:{versionId}:{fromStage}:{toStage}:{correlationId}`.
  - Composition/orchestration mutation: `composition:{versionId}:{operationId}:{eventType}` where `operationId` comes from the request body and is unique for each add/update/delete/wire/unwire/orchestration edit attempt.
  - DQ recommendation apply: `dq:apply:{versionId}:{recommendationId}:{operationId}`.
  - Codegen run event: `codegen:{runId}:{eventType}`.
  - Artifact edit: `artifact:edit:{artifactId}:{contentHash}:{correlationId}`.
  - Package event: `{eventType}:{packageId}:{correlationId}`.
  - Deploy mutation event: use the request idempotency key prefixed as `deploy:{idempotencyKey}`.
  - Approval mutation event: use the request idempotency key prefixed as `approval:{idempotencyKey}`.
  - Git repo attach/update/detach/branch: use the request idempotency key prefixed as `git:{idempotencyKey}`.
  - Git PR create/status: use the request PR idempotency key prefixed as `git:{idempotencyKey}`.
  - EBCDIC run event: `ebcdic:run:{runId}:{eventType}`; upload event: `ebcdic:upload:{artifactId}:{eventType}`.
- If a retry uses the same idempotency key with identical payload hash, return the existing event id.
- If a retry uses the same idempotency key with a materially different payload hash, return `409 WORKSPACE_EVENT_IDEMPOTENCY_CONFLICT` and do not write a second event.
- Async retry is best-effort in-process in this plan and must use the same explicit key that the original write attempted.

Chat event scope matrix:

- `CHAT_TURN_STARTED`: GLOBAL, PIPELINE, PIPELINE_VERSION.
- `CHAT_TOOL_STARTED`: GLOBAL only for global-safe tools, PIPELINE only for pipeline-aggregate tools, PIPELINE_VERSION only for versioned tools.
- `CHAT_TOOL_COMPLETED`: same scope as matching `CHAT_TOOL_STARTED`.
- `CHAT_TOOL_FAILED`: same scope as matching `CHAT_TOOL_STARTED`; preflight failures use attempted chat turn scope.

Registry drives compatibility:

- GLOBAL tools emit GLOBAL events.
- PIPELINE tools emit PIPELINE events.
- PIPELINE_VERSION tools emit PIPELINE_VERSION events only when versionId exists.
- Writer rejects mismatches with `WORKSPACE_EVENT_SCOPE_INVALID`.

No public create endpoint. Services call `WorkspaceEventService.record`.

Events vs audit panels:

- Bottom `events` panel shows the raw workspace event feed from `GET /api/v1/tenants/{tenantId}/workspace/events`.
- Bottom `events` filters default to current IDE `scope=PIPELINE_VERSION`, `pipelineId`, and `versionId`; users may additionally filter by `eventType` and `correlationId`.
- Bottom `audit` panel also reads from the same canonical workspace-events endpoint, but applies audit semantics: actor, timestamp, target, summary, and payload evidence grouped by `correlationId`.
- Default `audit` query in a versioned IDE route uses `scope=PIPELINE_VERSION`, `pipelineId`, and `versionId`, with the audit default event types below. It does not omit scope.
- `bottom=audit&correlationId=<id>` switches audit into correlation mode. In correlation mode the request omits `scope`, sends only `correlationId` plus optional event-type filters, and may return events across PIPELINE and PIPELINE_VERSION scopes for that correlation id.
- If `bottom=audit` opens with no selected version and no `correlationId`, the panel does not call the API; it renders the no-version/select-version state.
- `audit` default event types are `VERSION_CREATED`, `VERSION_LIFECYCLE_CHANGED`, `STORY_GENERATED`, `COMPOSITION_CHANGED`, `CODEGEN_STARTED`, `CODEGEN_COMPLETED`, `CODEGEN_FAILED`, `ARTIFACT_EDITED`, `DEPLOYABILITY_ASSESSED`, `PACKAGE_CREATED`, `PACKAGE_FAILED`, `DEPLOYMENT_REQUESTED`, `APPROVAL_REQUESTED`, and `APPROVAL_DECIDED`.
- `events` is chronological operations detail; `audit` is compliance/review evidence.
- Neither bottom panel reads `CommandLogController`. `frontend/src/app/(app)/commands/page.tsx` may continue to use the legacy command log until separately migrated.
- Command-search `OPEN_PANEL target:"events"` and `target:"audit"` open the same bottom region with different view mode and filters.

Bottom panel contracts:

- `problems`: local derived panel owned by `WorkspaceShellProvider` plus active workspace tab components. Data sources are client-side validation results from route canonicalization, composition validation, codegen freshness, deployability blockers, and chat/tool preflight errors. It has no backend API in Phase 3. Empty state: `No problems for this version`. Loading state follows the contributing panes. Error state shows unavailable contributors but keeps other problems visible.
- `runs`: codegen/deploy run panel owned by the IDE workspace. Data sources are `GET /api/v1/tenants/{tenantId}/codegen/runs?versionId=<selectedVersionId>`, `GET /api/v1/tenants/{tenantId}/codegen/runs/{runId}` for `runKind=codegen` deep links, `GET /api/v1/tenants/{tenantId}/pipeline-versions/{selectedVersionId}/deployment-runs` for the deployment-run list, and `GET .../deployment-runs/{runId}` for `runKind=deployment` deep links. Deployment `runId` is always a `DeploymentRunDTO.id`, not a deployment id. Empty state: `No runs for this version`. Loading and error states are isolated from the editor; failure to load runs does not block code editing.
- `diffs`: local comparison panel owned by the code editor workspace. Data sources are stale-save/apply-to-latest buffers and artifact read endpoints. It opens only when a stale buffer or explicit artifact comparison exists. Empty state: `No active diff`. It has no standalone backend API in Phase 3.
- `events`: canonical workspace event feed panel described above.
- `audit`: canonical workspace event evidence panel described above.
- `none`: bottom panel hidden. Hidden panels preserve their loaded state for the current route key.

Query deep-link bootstrap:

- `bottom=runs&runKind=codegen&runId=<id>` fetches `GET .../codegen/runs/{runId}` first, validates `versionId` matches selected version, then selects the row/detail. Not found or version mismatch clears `runId`/`runKind` with `router.replace` and shows `Code generation run not found`.
- `bottom=runs&runKind=deployment&runId=<id>` fetches `GET .../deployment-runs/{runId}` first, validates version, then selects the row/detail. Not found or mismatch clears `runId`/`runKind` and shows `Deployment run not found`.
- `bottom=events&eventId=<id>` fetches `GET .../workspace/events/{eventId}` first, validates scope/pipeline/version compatibility, then selects and expands the event row. Not found or mismatch clears `eventId` and shows `Event not found`.
- `bottom=audit&correlationId=<id>` queries the workspace event feed by correlation id and keeps the filter even when no rows are found.
- `bottom=diffs&artifactId=<id>` resolves through artifact detail read; not found clears `artifactId` and shows `Artifact not found for diff`.
- `bottom=problems&problemsIssueId=<id>` focuses a problem row by deterministic id. Server `ReadinessIssueDTO.id` is already stable. Client-derived ids use `client:<source>:<versionId>:<stableKey>`, for example `client:route:<versionId>:invalid-rev`, `client:codegen:<versionId>:stale-buffer:<artifactId>`, `client:chat:<versionId>:tool-preflight:<toolName>`, or `client:composition:<versionId>:dangling-edge:<edgeId>`. On version switch, client-derived problem ids clear unless recomputed with the new version id; server ids clear when not present in the new deployability response.

## Codegen

Current unscoped endpoints remain only as compatibility shims during migration. Add canonical tenant-scoped reads:

```http
GET /api/v1/tenants/{tenantId}/codegen/runs?versionId=&pipelineId=&status=
GET /api/v1/tenants/{tenantId}/codegen/runs/{runId}
```

Response includes `serverTime`, `items`, and `latestCompletedRunId`.

Response:

```json
{
  "serverTime": "ISO-8601",
  "items": [
    {
      "id": "...",
      "pipelineId": "...",
      "versionId": "...",
      "status": "COMPLETED",
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601",
      "completedAt": "ISO-8601",
      "artifactCount": 14,
      "correlationId": "..."
    }
  ],
  "latestCompletedRunId": "..."
}
```

Empty response:

```json
{
  "serverTime": "ISO-8601",
  "items": [],
  "latestCompletedRunId": null
}
```

Filter rules:

- `versionId` only: return runs for that version.
- `pipelineId` only: return runs for all versions of that pipeline.
- Both `versionId` and `pipelineId`: `versionId` is authoritative, and server validates that the version belongs to the pipeline.
- Neither `versionId` nor `pipelineId`: return 400 `CODEGEN_SCOPE_REQUIRED`.
- Path `tenantId` must match the tenant of the supplied pipeline/version, or return `403 CODEGEN_TENANT_MISMATCH`.
- `status` filters before sorting and latest calculation.
- Sort order is `completedAt DESC NULLS LAST`, then `createdAt DESC`, then `id DESC`.
- `latestCompletedRunId` is null when no COMPLETED run exists after filters.
- `GET .../codegen/runs/{runId}` returns one `GenerationRunDTO` after tenant validation and returns `404 CODEGEN_RUN_NOT_FOUND` when missing or outside tenant.

DQ readiness generate gate:

- Keep the existing code-generation safety gate in both classic and IDE codegen flows.
- Gate owner is a shared hook/component extracted from `frontend/src/components/pipeline/code-editor-panel.tsx` and reused by the IDE code tab, for example `useCodegenReadinessGate(selectedVersionId)`.
- The hook reads deployability/DQ readiness for the explicit selected version through the tenant-scoped deployability endpoint. It never reads active/latest.
- Thresholds remain:
  - score below `50`: block generation; Generate is disabled and the dialog explains the blocking DQ/readiness issues.
  - score `50` through `79`: generation is allowed only after a confirmation dialog.
  - score `80` or above: generation proceeds without confirmation.
- If readiness evaluation fails and no score is available, allow generation to proceed, show non-blocking warning `Readiness unavailable; generation will continue`, and send `readinessConfirmation.dqReadinessUnavailable:true`.
- Confirmation dialog shows score, blocking/warning checks, and actions `Review readiness`, `Generate anyway`, and `Cancel`. `Generate anyway` is available only for the 50-79 warning band.
- Classic and IDE use identical thresholds and request the same canonical codegen route after the gate passes.
- Server-side `POST .../codegen/runs` also rechecks readiness from the explicit version unless request `triggerType="regenerate"` is an administrator-only override. Scores below 50 return `400 CODEGEN_READINESS_BLOCKED` with `details.score` and `details.threshold`; scores 50-79 require `readinessConfirmation.dqGateConfirmed:true`; unavailable scores are allowed only with `readinessConfirmation.dqReadinessUnavailable:true`.
- Workspace events emit `CODEGEN_STARTED` only after the gate passes and the run is created; blocked attempts may surface in chat/tool errors and local problems but do not create generation runs.

Canonical readiness issue DTO:

```ts
type ReadinessIssueDTO = {
  id: string;
  category: "DQ" | "COMPOSITION" | "CODEGEN" | "DEPLOY" | "GIT" | "SECURITY";
  severity: "BLOCKER" | "WARNING" | "INFO";
  code: string;
  title: string;
  description: string;
  recommendation: string | null;
  targetType: "PIPELINE_VERSION" | "NODE" | "EDGE" | "DATASET" | "ARTIFACT" | "PACKAGE" | "DEPLOY_TARGET";
  targetId: string | null;
  source: "DQ_READINESS" | "DEPLOYABILITY" | "STATIC_VALIDATION" | "CHAT_TOOL" | "SYSTEM";
  action:
    | null
    | WorkspaceAction;
};
```

Readiness surface contract:

- `DeployabilityDTO.blockers` and `DeployabilityDTO.warnings` are arrays of `ReadinessIssueDTO`; blockers contain only `severity="BLOCKER"`, warnings contain `WARNING` or `INFO`.
- The codegen gate dialog reads only `dqReadinessScore`, `dqGateThresholds`, `blockers`, and `warnings` from `DeployabilityDTO`.
- `Review readiness` navigates to `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy` and preserves the current correlation id for that navigation event.
- The deployability tab renders the full `DeployabilityDTO`; the problems panel projects the same `ReadinessIssueDTO` arrays plus local client issues into one list. No second readiness DTO is introduced in Phase 4.
- The legacy DQ recommendation/expectation flow is preserved as the producer of DQ-category `ReadinessIssueDTO` rows. Accept/apply recommendation actions remain owned by the DQ recommendation panel; the codegen gate only displays and routes to them.

DQ recommendation endpoints:

```http
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/dq/recommendations
POST /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/dq/recommendations/{recommendationId}/apply
POST /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/dq/evaluate
```

DQ physical owners and compatibility:

- Backend controller owner: existing `backend/src/main/java/com/pulse/pipeline/controller/DqController.java`.
- Backend service owner: existing `backend/src/main/java/com/pulse/pipeline/service/DqReadinessService.java`.
- Frontend hook owner: existing `frontend/src/hooks/use-dq-readiness.ts`, migrated to require explicit `{tenantId,pipelineId,versionId}` and to call the tenant-scoped endpoints above.
- Frontend UI owners: `frontend/src/components/pipeline/dq-recommendations.tsx`, deployability tab primitives, codegen readiness gate, and the problems panel projection.
- Existing live routes remain compatibility shims during migration:
  - `GET|POST /api/v1/versions/{versionId}/dq/*`
  - `POST /api/v1/versions/{versionId}/instances/{instanceId}/dq-expectations`
- Shims derive tenant and pipeline from `versionId`, reject missing/wrong tenant with the same structured errors, call `DqReadinessService` through the same tenant-scoped service methods, and return the same `ReadinessIssueDTO`/score shapes.
- Shim retirement requires all frontend callers, chat tools, and tests to use tenant-scoped routes; until then, shims must not use active/latest or pipeline aggregate fallback.
- No new DQ table migration is required in this plan unless implementation discovers missing persistence for recommendation application audit; DQ audit evidence is represented by workspace events using `DQ_RECOMMENDATION_APPLIED`.

`GET .../dq/recommendations` returns `{ "items": ReadinessIssueDTO[], "score": number | null, "reasoning": string | null }`.
Apply body is `{ "correlationId": "...", "accepted": true }` and returns `{ "applied": true, "issue": ReadinessIssueDTO, "score": number | null }`.
Evaluate returns the same shape as recommendation list and is the source for `DeployabilityDTO.dqReadinessScore` when deployability recomputes.

DQ errors:

| Condition | HTTP | Code | Details |
| --- | --- | --- | --- |
| Version missing/outside tenant | 404 | `VERSION_NOT_FOUND` | `versionId` |
| Pipeline/version mismatch | 400 | `DQ_VERSION_PIPELINE_MISMATCH` | `pipelineId`, `versionId` |
| Recommendation missing | 404 | `DQ_RECOMMENDATION_NOT_FOUND` | `recommendationId`, `versionId` |
| Version immutable for apply | 409 | `VERSION_IMMUTABLE` | `versionId`, `lifecycleStage` |
| Readiness unavailable | 503 | `DQ_READINESS_UNAVAILABLE` | `versionId`, `reasonCode` |

Readiness action executor:

- Shared owner: `frontend/src/components/readiness/readiness-action-executor.ts`.
- UI owners calling it: deployability tab, problems panel, codegen gate dialog, DQ recommendation panel.
- `dqRecommendations` dialog owner: `frontend/src/components/pipeline/dq-recommendations.tsx`.
- `configureStep` dialog owner: `frontend/src/components/pipeline/configure-transform-dialog.tsx`.
- The executor receives `{tenantId,pipelineId,versionId,action}` where `action` is a concrete `WorkspaceAction`, validates required params, and forwards it to `CommandActionHost`.

Readiness action construction:

| Use case | Concrete `WorkspaceAction` |
| --- | --- |
| Open deployability | `{ type:"NAVIGATE", target:"/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy", params:{} }` |
| Focus composition node | `{ type:"NAVIGATE", target:"/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=composition&side=inspector&bottom=problems&activity=composition#node:<nodeId>", params:{} }` |
| Open code artifact | `{ type:"NAVIGATE", target:"/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=code&side=assistant&bottom=problems&activity=code#artifact:<artifactId>", params:{} }` |
| Open Git tab | `{ type:"NAVIGATE", target:"/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=git&side=assistant&bottom=problems&activity=deploy", params:{} }` |
| Open story | `{ type:"NAVIGATE", target:"/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=story&side=assistant&bottom=problems&activity=assistant#story:<storyId>", params:{} }` |
| Focus problem | `{ type:"OPEN_PANEL", target:"problems", params:{ problemsIssueId:"<issueId>" } }` |
| Open event | `{ type:"OPEN_PANEL", target:"events", params:{ eventId:"<eventId>" } }` |
| Open audit filter | `{ type:"OPEN_PANEL", target:"audit", params:{ correlationId:"<correlationId>" } }` |
| Open run | `{ type:"OPEN_PANEL", target:"runs", params:{ runId:"<runId>", runKind:"codegen|deployment" } }` |
| Open diff | `{ type:"OPEN_PANEL", target:"diffs", params:{ artifactId:"<artifactId>" } }` |
| Open DQ recommendations | `{ type:"OPEN_DIALOG", target:"dqRecommendations", params:{ pipelineId:"<pipelineId>", versionId:"<versionId>", issueId:"<issueId>" } }` |
| Configure step | `{ type:"OPEN_DIALOG", target:"configureStep", params:{ pipelineId:"<pipelineId>", versionId:"<versionId>", nodeId:"<nodeId>" } }` |
| Connect Git | `{ type:"OPEN_DIALOG", target:"connectGit", params:{ pipelineId:"<pipelineId>", versionId:"<versionId-if-versioned>", domainId:"<domainId>", scope:"PIPELINE" } }`; `versionId` is optional for versionless pipeline setup. If `domainId` is unknown, `CommandActionHost` fetches pipeline aggregate before opening. |
| Deploy package | `{ type:"OPEN_DIALOG", target:"deployPackage", params:{ pipelineId:"<pipelineId>", versionId:"<versionId>", packageId:"<packageId>" } }` |

Missing required params make the action disabled at render time; if encountered at execution, the executor logs `READINESS_ACTION_INVALID` and shows a toast without mutation.

Canonical client action schema:

```ts
type WorkspaceAction =
  | { type: "NAVIGATE"; target: string; params: Record<string, never> }
  | { type: "OPEN_GLOBAL_ASSISTANT"; target: "globalAssistant"; params: { focus?: "composer" | "messages" } }
  | { type: "OPEN_PANEL"; target: "assistant" | "inspector" | "problems" | "runs" | "events" | "audit" | "diffs"; params: WorkspaceBottomParams & { placement?: "side" | "bottom" } }
  | { type: "OPEN_DIALOG"; target: "newPipeline" | "newProducer" | "defineDataset" | "connectGit" | "deployPackage" | "uploadCopybook" | "uploadDataFile" | "switchTenant" | "dqRecommendations" | "configureStep"; params: Record<string, string> }
  | { type: "CLIENT_COMMAND"; target: "focusComposer" | "copyCurrentLink" | "toggleSidebar" | "toggleWorkspaceZen"; params: Record<string, string> };
```

`CommandAction` is an alias of `WorkspaceAction`. Readiness actions, command-search results, and chat/tool navigation affordances use this one schema. `CommandActionHost` is the single executor; `ReadinessActionExecutor` validates required params and forwards a `WorkspaceAction` to the host.

Artifact reads:

```http
GET /api/v1/tenants/{tenantId}/codegen/runs/{runId}/artifacts
GET /api/v1/tenants/{tenantId}/codegen/artifacts/{artifactId}
```

Rules:

- These tenant-scoped routes are canonical for the IDE and migrated classic editor.
- The current `GET /api/v1/generations/{runId}/artifacts` and `GET /api/v1/artifacts/{artifactId}` remain compatibility shims during migration.
- Path `tenantId` must match the run/version tenant, or return `403 CODEGEN_TENANT_MISMATCH`.
- `GET .../runs/{runId}/artifacts` returns artifacts sorted by `filePath ASC`.
- `GET .../artifacts/{artifactId}` returns content and provenance for one artifact.
- Artifact list responses include enough data for tree/list display and stale-save lookup; artifact detail includes full `content` for editor/diff.

Artifact list response:

```json
{
  "runId": "...",
  "pipelineId": "...",
  "versionId": "...",
  "items": [
    {
      "id": "...",
      "generationRunId": "...",
      "pipelineId": "...",
      "versionId": "...",
      "filePath": "...",
      "fileType": "...",
      "contentHash": "...",
      "manuallyModified": false,
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601"
    }
  ]
}
```

Artifact detail response:

```json
{
  "id": "...",
  "generationRunId": "...",
  "pipelineId": "...",
  "versionId": "...",
  "filePath": "...",
  "fileType": "...",
  "content": "...",
  "contentHash": "...",
  "manuallyModified": false,
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

Latest artifact lookup for stale-save:

- When `/codegen/runs?versionId=<selectedVersionId>` returns `latestCompletedRunId`, the editor fetches `GET /api/v1/tenants/{tenantId}/codegen/runs/{latestCompletedRunId}/artifacts`.
- It finds same-path latest artifact client-side by exact `filePath`.
- The save 409 body may include `latestArtifactId` and `latestArtifactPath` as a convenience, but the list endpoint is the canonical source for merge/diff setup.

Errors:

```json
{
  "code": "CODEGEN_SCOPE_REQUIRED",
  "message": "versionId or pipelineId is required",
  "details": {}
}
```

```json
{
  "code": "CODEGEN_VERSION_PIPELINE_MISMATCH",
  "message": "Version does not belong to pipeline",
  "details": {
    "versionId": "...",
    "pipelineId": "..."
  }
}
```

Readiness errors use the same shape:

- `CODEGEN_RUN_NOT_FOUND`: run missing or outside tenant; details include `runId`.
- `CODEGEN_READINESS_BLOCKED`: score below 50; details include `score`, `threshold`, and `versionId`.
- `CODEGEN_READINESS_CONFIRMATION_REQUIRED`: score 50-79 without confirmation; details include `score`, `confirmBelow`, and `versionId`.
- `CODEGEN_READINESS_UNAVAILABLE`: server cannot compute readiness and client did not acknowledge unavailable readiness; details include `versionId`.

Freshness:

- Code editor polls `/api/v1/tenants/{tenantId}/codegen/runs?versionId={selectedVersionId}` every 15 seconds while Code tab visible and dirty or generation status non-terminal.
- Code editor uses tenant path `/api/v1/tenants/{tenantId}/codegen/runs?versionId={selectedVersionId}`.
- Refetch after generate resolves and window focus.
- Workspace events are audit only, not freshness authority.

Generate mutation:

```http
POST /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/codegen/runs
```

Request:

```json
{
  "pipelineId": "...",
  "triggerType": "manual|chat|regenerate",
  "userId": "...",
  "correlationId": "...",
  "readinessConfirmation": {
    "dqGateConfirmed": false,
    "dqReadinessUnavailable": false,
    "observedScore": 86,
    "observedAt": "ISO-8601"
  }
}
```

Rules:

- This tenant-scoped route is canonical for the IDE and migrated classic editor.
- The current `POST /api/v1/versions/{versionId}/generate` remains as a compatibility shim only during migration.
- The legacy shim must validate `request.tenantId`, `request.pipelineId`, and `versionId` exactly as the canonical route does, then delegate to the canonical service method.
- Path `tenantId` must match the tenant of `versionId`, or return `403 CODEGEN_TENANT_MISMATCH`.
- `pipelineId` must match the version's pipeline, or return `400 CODEGEN_VERSION_PIPELINE_MISMATCH`.
- `triggerType` defaults to `manual`.
- `readinessConfirmation` is optional only for legacy shims. Canonical IDE/classic migrated clients always send it.
- If backend recomputed readiness score is below 50, generation is blocked regardless of `dqGateConfirmed`.
- If backend recomputed readiness score is 50-79, `readinessConfirmation.dqGateConfirmed` must be true or return `400 CODEGEN_READINESS_CONFIRMATION_REQUIRED`.
- If backend cannot compute readiness and `readinessConfirmation.dqReadinessUnavailable` is false, return `400 CODEGEN_READINESS_UNAVAILABLE`.
- If backend cannot compute readiness and `readinessConfirmation.dqReadinessUnavailable` is true, allow generation and store that flag in run metadata.
- `observedScore` and `observedAt` are client evidence only; backend validation uses server recomputation and may include both server and observed scores in error details.
- `userId` is accepted during the current dev-auth period, but once actor resolution is available the server derives the actor from auth and ignores request-body `userId`.
- `correlationId` is optional; when omitted, the service generates a fallback id and stores it on the generation run metadata.
- Response is `GenerationRunDTO` with `id`, `pipelineId`, `versionId`, `status`, timestamps, `triggeredBy`, `triggerType`, `artifactCount`, and `correlationId`.

Save:

```http
PUT /api/v1/tenants/{tenantId}/codegen/artifacts/{artifactId}/content
```

Request:

```json
{
  "content": "...",
  "expectedRunId": "...",
  "expectedVersionId": "...",
  "correlationId": "..."
}
```

Success response:

```ts
type ArtifactSaveResponse = {
  artifact: GeneratedArtifactDTO;
  previousContentHash: string;
  contentHash: string;
  manuallyModified: true;
  eventId: string;
  correlationId: string;
};
```

- `GeneratedArtifactDTO.updatedAt`, `contentHash`, and `manuallyModified` are updated from the committed row.
- `previousContentHash` is the hash the server observed before save; `contentHash` is the hash after save.
- `eventId` is the `ARTIFACT_EDITED` workspace event id written transactionally with the content update.
- The editor replaces its open artifact metadata from `response.artifact`, resets `originalContentHash` to `response.contentHash`, clears dirty state, and merges the returned event into events/audit panels when visible.

Only latest completed run is editable.

Compatibility rollout:

- This tenant-scoped route is canonical for the IDE and migrated classic editor.
- The current `PUT /api/v1/artifacts/{artifactId}/content` remains as a compatibility shim only during migration.
- The legacy save shim derives tenant/version through artifact -> generation run -> version, applies the same authorization and freshness checks, and returns the same structured errors.
- Phase 4 initially accepts legacy body `{ "content": "..." }` from the current editor.
- Legacy saves are allowed only while the current editor has not been migrated, and each legacy save logs `legacy_artifact_save_without_guards`.
- Legacy saves are not exempt from latest-run safety. Server derives `expectedRunId` from the artifact's current `generationRunId` and `expectedVersionId` from that run's `versionId`, then applies the same latest completed run validation.
- If the artifact's run is stale, legacy save returns the same `409 GENERATION_RUN_NOT_EDITABLE` body.
- The IDE/new editor always sends `expectedRunId` and `expectedVersionId`.
- After the editor cutover and a 14-day zero-use legacy log window, guarded fields become required.
- Once required, missing `expectedRunId` or `expectedVersionId` returns 400 `ARTIFACT_SAVE_GUARD_REQUIRED`.

Save validation:

- Load artifact and generation run.
- Path `tenantId` must match the artifact's generation-run version tenant, or return `403 CODEGEN_TENANT_MISMATCH`.
- `artifact.generationRunId` must equal `expectedRunId`.
- `run.versionId` must equal `expectedVersionId`.
- Recompute latest completed run for `expectedVersionId`.
- If `expectedRunId` is not the latest completed run, return 409 `GENERATION_RUN_NOT_EDITABLE`.

409 body:

```json
{
  "code": "GENERATION_RUN_NOT_EDITABLE",
  "message": "A newer completed generation exists. Apply your edits to the latest artifact before saving.",
  "details": {
    "artifactId": "...",
    "expectedRunId": "...",
    "latestCompletedRunId": "...",
    "expectedVersionId": "...",
    "latestRunCompletedAt": "ISO-8601",
    "latestArtifactId": "...",
    "latestArtifactPath": "..."
  }
}
```

If no same-path artifact exists in the latest run, `latestArtifactId` and `latestArtifactPath` are null.

Frontend stale detection:

- Editor opens an artifact with tuple `{editingVersionId, editingRunId, artifactId, filePath, originalContentHash, originalContent}`.
- Polling `/api/v1/tenants/{tenantId}/codegen/runs?versionId={selectedVersionId}` returns `latestCompletedRunId`.
- If `latestCompletedRunId !== editingRunId`, mark the buffer stale and disable Save.
- Polling failure marks freshness unknown and disables Save for dirty buffers.

On 409:

- Preserve local unsaved buffer under key `versionId:runId:artifactId`.
- Fetch latest run artifacts.
- Show actions: Review diff with latest, Apply my edits to latest, Discard local edits, Keep read-only snapshot.
- Do not silently retry.

Apply-to-latest:

- Available only when a latest artifact with the same `filePath` exists.
- Uses three sources:
  - merge base: `originalContent` loaded for the old artifact.
  - user content: current dirty editor buffer.
  - latest content: content of `latestArtifactId`.
- If non-overlapping hunks merge automatically, populate a latest-artifact buffer and show diff. User must click Save.
- If overlapping hunks exist, open manual diff/merge UI. User resolves into the latest-artifact buffer and then saves.
- Save after apply-to-latest uses `artifactId=latestArtifactId`, `expectedRunId=latestCompletedRunId`, and `expectedVersionId=selectedVersionId`.
- Apply-to-latest never mutates historical artifacts.
- If no latest same-path artifact exists, Apply-to-latest is disabled and user may copy manually from the read-only diff.

Tests:

- Stale save returns exact 409 body.
- Latest same-path artifact is included when present.
- Missing latest same-path artifact disables apply-to-latest.
- Poll-detected stale state disables Save.
- Poll failure disables dirty Save.
- Auto-merge path saves with latest artifact id.
- Manual conflict path requires resolution before save.

## dbt Assets

Decision:

- dbt assets remain a domain-scoped registry in storage and backend ownership.
- The IDE `tab=dbt` is a version-scoped workspace lens over the selected pipeline's domain registry. It requires `selectedVersionId` so the route can deterministically resolve `{pipelineId -> domainId}`, show version-specific reuse evidence, and avoid active/latest fallback.
- The source of truth for registry CRUD remains domain-scoped; version-scoped endpoints are read/selection bridges for workspace use.

Primary owners:

- Backend controller: `backend/src/main/java/com/pulse/codegen/controller/DbtAssetController.java`.
- Backend service: `backend/src/main/java/com/pulse/codegen/service/DbtAssetRegistryService.java`.
- Frontend panel: `frontend/src/components/pipeline/dbt-asset-panel.tsx`.
- IDE route host: `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx`.

Canonical endpoints:

```http
GET /api/v1/domains/{domainId}/dbt-assets
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/dbt-assets
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/versions/{versionId}/dbt-assets/reuse-candidates?nodeId=&datasetId=&query=
```

Rules:

- Domain endpoint remains the registry owner and is used by domain/settings style management surfaces.
- Version endpoint validates tenant, pipeline, version, and pipeline domain, then delegates to `DbtAssetRegistryService` using the resolved `domainId`.
- Version endpoint response includes `{ "domainId": "...", "versionId": "...", "items": DbtAssetDTO[], "usage": DbtAssetUsageDTO[] }`. `usage` is version-derived evidence such as nodes/datasets that can reuse or already reference a dbt asset.
- `frontend/src/components/pipeline/dbt-asset-panel.tsx` receives `tenantId`, `pipelineId`, `selectedVersionId`, and `domainId` from `PipelineRouteStateController`/pipeline aggregate state. If any is missing, it renders disabled/no-domain or no-version state and does not call the domain endpoint directly.
- Chat tools `list_dbt_assets` and `find_dbt_reuse_candidate` are `PIPELINE_VERSION` tools. They use `ChatTurnContext.versionId` to resolve domain and may not run from PIPELINE aggregate scope.
- Command search opens `tab=dbt` only in `PIPELINE_VERSION` scope.
- Existing domain-scoped API is not removed; the version-scoped bridge can be added without migrating registry tables.

Errors:

```ts
type DbtAssetUsageDTO = {
  assetId: string;
  usageType: "REFERENCED" | "REUSE_CANDIDATE" | "CONFLICT";
  nodeId: string | null;
  datasetId: string | null;
  confidence: number | null;
  reason: string | null;
};
```

| Condition | HTTP | Code | Details |
| --- | --- | --- | --- |
| Version missing/outside pipeline | 404 | `VERSION_NOT_FOUND` | `pipelineId`, `versionId` |
| Pipeline has no domain | 400 | `DBT_DOMAIN_REQUIRED` | `pipelineId` |
| Domain forbidden | 403 | `DBT_DOMAIN_FORBIDDEN` | `domainId` |

## Deploy And Deployability

Primary owners:

- Backend API owner: `backend/src/main/java/com/pulse/deploy/controller/DeployController.java`.
- Classic panel owner: `frontend/src/components/pipeline/deploy-panel.tsx`.
- IDE deployability owner: workspace tab mounted by `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx`.

Canonical endpoints:

```http
POST /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/packages
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/packages
GET /api/v1/tenants/{tenantId}/packages/{packageId}
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/deployability
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/deployment-targets
GET /api/v1/tenants/{tenantId}/packages/{packageId}/deployment-targets
GET /api/v1/tenants/{tenantId}/deployment-targets
GET /api/v1/tenants/{tenantId}/deployment-targets/{targetId}
POST /api/v1/tenants/{tenantId}/deployment-targets
PUT /api/v1/tenants/{tenantId}/deployment-targets/{targetId}
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/deployments
GET /api/v1/tenants/{tenantId}/deployments/{deploymentId}
GET /api/v1/tenants/{tenantId}/deployments/{deploymentId}/runs
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/deployment-runs
GET /api/v1/tenants/{tenantId}/deployment-runs/{runId}
POST /api/v1/tenants/{tenantId}/packages/{packageId}/deploy
POST /api/v1/tenants/{tenantId}/deployments/{deploymentId}/approval
GET /api/v1/tenants/{tenantId}/deployments/{deploymentId}/approval-requests
PUT /api/v1/tenants/{tenantId}/approvals/{approvalId}
```

Compatibility:

- Current unscoped routes in `DeployController` remain compatibility shims during migration.
- Shims derive tenant/version/package/deployment identity from persisted records, apply the same checks, and return the same structured errors.

Deployment targets:

- Target list/create/read/update stay tenant-scoped catalog operations, but deploy picker authority comes from the runtime-binding authority facade and runtime projection. Catalog rows are candidates/evidence; they are not deployable merely because they exist.
- `GET .../pipeline-versions/{versionId}/deployment-targets` is an eligibility projection over persona-legal runtime bindings plus candidate target metadata; it validates version tenant and returns `DeploymentTargetDTO` with eligibility fields populated.
- `GET .../packages/{packageId}/deployment-targets` returns package-aware eligibility metadata resolved through the runtime binding and runtime projection. It validates package tenant/version and evaluates `NO_PACKAGE_ARTIFACT`, `PACKAGE_TYPE_UNSUPPORTED`, package deployability blockers, projection drift, approval requirements, and Airflow target compatibility against that exact package.
- `DeploymentTargetDTO` includes `id`, `tenantId`, `name`, `environment`, `targetType`, `endpointUrl`, `config`, `runtimePersona`, `runtimeBindingId`, `runtimeBindingRole: "PRIMARY" | "DIAGNOSTIC" | null`, `runtimeBindingValidationStatus`, `runtimeProjectionId`, `runtimeProjectionHash`, `stableDagId`, `eligible: boolean | null`, `disabledReasonCode: DeploymentTargetDisabledReason | null`, `disabledReasonLabel: string | null`, `eligibilityDetails: Record<string, unknown>`, `createdAt`, and `updatedAt`.
- `DeploymentTargetDisabledReason` allowed values: `NO_PACKAGE_ARTIFACT`, `PACKAGE_TYPE_UNSUPPORTED`, `TARGET_TYPE_UNSUPPORTED`, `MISSING_TARGET_CONFIG`, `MISSING_RUNTIME_BINDING`, `DIAGNOSTIC_BINDING_ONLY`, `PERSONA_MISMATCH`, `PROJECTION_DRIFT`, `DEPLOYABILITY_BLOCKED`, `APPROVAL_REQUIRED`, `TARGET_DISABLED`, `AUTH_REQUIRED`, `UNKNOWN`.
- UI meaning: `APPROVAL_REQUIRED` may allow package/deployment preparation but blocks final submit until approval; every other non-null disabled reason makes the target unselectable. `disabledReasonLabel` is display text derived from the code.
- Tenant-level target list/read may return `eligible=null`, `disabledReasonCode=null`, `disabledReasonLabel=null`, and `eligibilityDetails={}` because no version context is being evaluated. Version target list always returns non-null `eligible`.
- Target list returns `{ "items": DeploymentTargetDTO[] }` ordered by `environment ASC, name ASC`.
- Create/update body includes `{ "name": "...", "environment": "dev|integration|prod|...", "targetType": "...", "endpointUrl": "...", "config": {} }`.
- Environment normalization: trim, lowercase, replace spaces/underscores with hyphen, then map aliases `development -> dev`, `prod -> prod`, `production -> prod`, `staging -> integration`, `int -> integration`, `qa -> integration`. Unknown non-empty values persist as normalized lowercase hyphen strings.
- Target type normalization for deployable targets: trim, uppercase, replace spaces/hyphens with underscore, then map aliases `airflow -> AIRFLOW`, `composer -> AIRFLOW`, `cloud_composer -> AIRFLOW`, and `on_prem_airflow -> AIRFLOW`. Non-Airflow values such as `spark`, `dataproc`, `databricks`, `dbt`, `kubernetes`, `k8s`, or `local` may be preserved only as diagnostic catalog values with `eligible=false` and a disabled reason such as `TARGET_TYPE_UNSUPPORTED` or `DIAGNOSTIC_BINDING_ONLY`; they are never deploy-submit targets.
- Deploy request target selection uses these eligibility-projected target DTOs and must refuse any row whose resolved target type is not Airflow.
- Deploy dialog target source: `packageAndDeploy` mode uses version-level targets before package creation and then revalidates selected target with package-aware targets after package creation; `deployExisting` mode uses `GET .../packages/{packageId}/deployment-targets` before enabling submit.

Airflow deploy boundary:

- Package/deploy UI labels use "Airflow deploy target" rather than generic compute target where the action would submit deployment.
- Composer is the GCP Airflow target; on-prem/DPC Airflow is the DPC target. Spark, Dataproc, dbt, and GX are runtime task engines inside the generated DAG, not deploy targets.
- Deploy dialogs show runtime projection evidence: stable DAG ID, DAG delivery path, package URI, Airflow connection/variable/pool/queue names when available, projection hash, and stale/drift blockers.
- Deploy confirmation copy must say that deployment publishes/syncs/activates Airflow artifacts. It must not say that PULSE is launching a Spark/Dataproc job at deploy time.
- Smoke-run actions, if exposed, call the Airflow runtime client to trigger/poll the DAG run and show the Airflow run id. They do not call Spark/Dataproc APIs directly.

Package request:

```json
{
  "pipelineId": "...",
  "packageType": "AIRFLOW|SPARK|DBT|FULL",
  "userId": "...",
  "correlationId": "...",
  "idempotencyKey": "..."
}
```

Rules:

- Path `tenantId` must match the version tenant, or return `403 DEPLOY_TENANT_MISMATCH`.
- `pipelineId` must match the version pipeline, or return `400 DEPLOY_VERSION_PIPELINE_MISMATCH`.
- Package creation uses the explicit path `versionId`; it never uses active/latest.
- `X-Correlation-Id` and `Idempotency-Key` headers are required on canonical route. Body `userId`, `correlationId`, and `idempotencyKey` are compatibility hints only and follow the global actor/header rules. UI formula: `package:create:{versionId}:{packageType}:{correlationId}`.
- Replay with same idempotency key and identical payload returns the existing `PackageDTO`; same key with different payload returns `409 PACKAGE_IDEMPOTENCY_CONFLICT`.
- Package creation records `PACKAGE_CREATED` or `PACKAGE_FAILED` transactionally with event idempotency key `package:{idempotencyKey}`.
- `userId` is accepted only as a dev-auth compatibility hint; server actor resolution is authoritative.
- Response `PackageDTO` includes `id`, `tenantId`, `pipelineId`, `versionId`, `packageType`, `status`, `artifactHash`, `artifactUrl`, `sourcePackageManifestVersion`, `sourcePackageManifestHash`, `runtimeAuthorityStamp`, `entrypointCatalogVersion`, `builtBy`, `createdAt`, `updatedAt`, `deployabilityStatus`, `blockers`, `warnings`, and `metadata`.
- Package list returns `{ "items": PackageDTO[] }` ordered by `createdAt DESC, id DESC`.
- `GET .../packages/{packageId}` returns one `PackageDTO` after tenant validation. Missing package returns `404 DEPLOY_PACKAGE_NOT_FOUND`. Optional query `expectedVersionId` validates package version and returns `400 DEPLOY_VERSION_MISMATCH` on mismatch.

Deployability read:

- `GET .../deployability` recomputes or returns the latest static deployability assessment for the explicit version.
- Response `DeployabilityDTO` includes `versionId`, `status: "PASS|WARN|FAIL"`, `runtimePersona`, `runtimeBindingId`, `sourcePackageManifestId`, `sourcePackageManifestHash`, `runtimeProjectionId`, `runtimeProjectionHash`, `runtimeProjectionStatus: "CURRENT|STALE|MISSING|FAILED"`, `stableDagId`, `orchestrationNamespace`, `tableContractSummary`, `ddlPlanSummary`, `airflowDeployBoundaryStatus: "PASS|FAIL"`, `runtimeIndependenceStatus: "PASS|FAIL"`, `dqReadinessScore: number | null`, `dqReadinessStatus: "PASS|WARN|FAIL|UNAVAILABLE"`, `dqGateThresholds: { "blockBelow": 50, "confirmBelow": 80 }`, `blockers`, `warnings`, `artifactCount`, `latestPackageId`, `latestGenerationRunId`, `assessedAt`, and `metadata`.
- `dqReadinessScore` is the canonical score used by the frontend generate gate. `null` means readiness is unavailable, not zero.
- `dqReadinessStatus` is derived from score: below 50 is `FAIL`, 50-79 is `WARN`, 80+ is `PASS`; unavailable score is `UNAVAILABLE`.
- Missing generated artifacts returns `200` with `status="FAIL"` and blockers; it is not a 404.
- `airflowDeployBoundaryStatus="FAIL"` when deploy or package evidence suggests direct deploy-time Spark/Dataproc/dbt/GX submission or a non-Airflow deploy target.
- `runtimeIndependenceStatus="FAIL"` when promoted artifacts require Pulse APIs, Pulse Postgres, peer Pulse endpoints, or forbidden callback/runtime helper strategies.

Deploy request:

```json
{
  "targetId": "...",
  "expectedVersionId": "...",
  "userId": "...",
  "correlationId": "...",
  "idempotencyKey": "..."
}
```

Rules:

- `packageId` must belong to path tenant.
- `expectedVersionId` must equal the package version, or return `400 DEPLOY_VERSION_MISMATCH`.
- Canonical deploy mutation is `POST /api/v1/tenants/{tenantId}/packages/{packageId}/deploy`.
- `POST .../packages/{packageId}/deployments` is not introduced by this plan. If that route exists later, it is a compatibility shim that delegates to `/deploy` and returns the same `DeploymentDTO`.
- Deploy uses package.versionId only; it never uses pipeline active/latest.
- `X-Correlation-Id` and `Idempotency-Key` headers are required on canonical route. Body `userId`, `correlationId`, and `idempotencyKey` are compatibility hints only and follow the global actor/header rules. UI formula: `deploy:{packageId}:{targetId}:{correlationId}`.
- Replay with same key and identical request returns the existing `DeploymentDTO` and latest `DeploymentRunDTO` when present; same key with different request returns `409 DEPLOY_IDEMPOTENCY_CONFLICT`.
- Deployment mutation records `DEPLOYMENT_REQUESTED` transactionally with idempotency key `deploy:{idempotencyKey}`.
- Response `DeploymentDTO` includes `id`, `tenantId`, `pipelineId`, `versionId`, `packageId`, `targetId`, `status`, `requestedBy`, `createdAt`, `updatedAt`, and `metadata`.

Deployment reads:

- `GET .../pipeline-versions/{versionId}/deployments` lists deployments for the exact version, ordered by `createdAt DESC, id DESC`.
- Optional filters: `status`, `targetId`, `limit`, `cursor`.
- `limit` default 50, max 100.
- Cursor is base64url JSON `{ "createdAt": "ISO-8601", "id": "..." }`.
- Response is `{ "items": DeploymentDTO[], "nextCursor": null }`.
- `GET .../deployments/{deploymentId}` returns one `DeploymentDTO` after validating path tenant.
- The current `GET /api/v1/pipelines/{pipelineId}/deployments` remains a compatibility shim during migration and must not be used by IDE.

Deployment run reads:

- `GET .../pipeline-versions/{versionId}/deployment-runs` lists deployment runs for the exact version across deployments, ordered by `startedAt DESC, id DESC`. Optional filters: `status`, `deploymentId`, `targetId`, `limit`, `cursor`.
- Response is `{ "items": DeploymentRunDTO[], "nextCursor": null }`.
- This version-scoped feed is the canonical data source for the deployment half of the IDE bottom `runs` panel.
- `GET .../deployments/{deploymentId}/runs` lists `DeploymentRunDTO` for one deployment ordered by `startedAt DESC, id DESC`.
- `GET .../deployment-runs/{runId}` returns one `DeploymentRunDTO`.
- `DeploymentRunDTO` includes `id`, `tenantId`, `deploymentId`, `pipelineId`, `versionId`, `packageId`, `targetId`, `status`, `initiatedBy`, `correlationId`, `idempotencyKey`, `startedAt`, `finishedAt`, `failureReason`, `metadata`.
- The bottom `runs` panel displays codegen runs and deployment runs in separate groups. Route selection uses query keys only: `bottom=runs&runKind=codegen|deployment&runId=<id>`.

Approval request/action:

- Approval creation for a deployment uses `deployment.versionId` and emits PIPELINE_VERSION workspace events.
- Approval creation body includes `{ "requestedApproverId": "...", "reason": "...", "correlationId": "...", "idempotencyKey": "..." }`.
- Approval creation response is `ApprovalRequestDTO` with `id`, `tenantId`, `deploymentId`, `pipelineId`, `versionId`, `packageId`, `requestedApproverId`, `status`, `reason`, `createdAt`, `updatedAt`, and `metadata`.
- Approval action body includes `{ "decision": "APPROVE|REJECT", "comment": "...", "correlationId": "...", "idempotencyKey": "..." }`.
- Approval action response is `ApprovalDTO` with `id`, `tenantId`, `deploymentId`, `approvalRequestId`, `decision`, `comment`, `decidedBy`, `decidedAt`, and `metadata`.
- Approval action validates tenant, deployment, package, and version consistency before mutation.
- Approval creation idempotency key formula: `approval:request:{deploymentId}:{requestedApproverId}:{correlationId}`.
- Approval decision idempotency key formula: `approval:decide:{approvalId}:{decision}:{correlationId}`.
- Replay with same key and identical request returns the existing `ApprovalRequestDTO` or `ApprovalDTO`; same key with different request returns `409 APPROVAL_IDEMPOTENCY_CONFLICT`.
- Approval creation/action record `APPROVAL_REQUESTED` or `APPROVAL_DECIDED` transactionally with event idempotency key `approval:{idempotencyKey}`.

Errors:

- `DEPLOY_TENANT_MISMATCH`
- `DEPLOY_VERSION_PIPELINE_MISMATCH`
- `DEPLOY_VERSION_MISMATCH`
- `DEPLOY_PACKAGE_NOT_FOUND`
- `DEPLOYMENT_NOT_FOUND`
- `DEPLOY_APPROVAL_NOT_FOUND`
- `DEPLOY_IDEMPOTENCY_CONFLICT`
- `APPROVAL_IDEMPOTENCY_CONFLICT`

All use `{code,message,details}`.

## Git

Primary owners:

- Backend API owner: `backend/src/main/java/com/pulse/git/controller/GitController.java`.
- Classic/IDE panel owner: `frontend/src/components/pipeline/git-panel.tsx`.
- IDE Git tab is mounted by `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx`.

Canonical endpoints:

```http
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo
GET /api/v1/tenants/{tenantId}/onboarding-status
POST /api/v1/tenants/{tenantId}/onboard
GET /api/v1/tenants/{tenantId}/git-repo
PUT /api/v1/tenants/{tenantId}/git-repo
GET /api/v1/tenants/{tenantId}/git-repo/branches
PUT /api/v1/tenants/{tenantId}/git-repo/branch
GET /api/v1/tenants/{tenantId}/domains/{domainId}/git-repo
GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo/attachment
GET /api/v1/tenants/{tenantId}/git-repos/{repoId}/branches
PUT /api/v1/tenants/{tenantId}/git-repos/{repoId}/branch
POST /api/v1/tenants/{tenantId}/domains/{domainId}/git-repo
PUT /api/v1/tenants/{tenantId}/domains/{domainId}/git-repo
DELETE /api/v1/tenants/{tenantId}/domains/{domainId}/git-repo
POST /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo/attachment
PUT /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo/attachment
DELETE /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo/attachment
GET /api/v1/tenants/{tenantId}/pipeline-versions/{versionId}/pull-requests
POST /api/v1/tenants/{tenantId}/git-repos/{repoId}/pull-requests
PUT /api/v1/tenants/{tenantId}/pull-requests/{prId}/status
```

Compatibility:

- Current `/api/v1/pipelines/{pipelineId}/git-repo`, `/api/v1/versions/{versionId}/pull-requests`, `/api/v1/git-repos/{repoId}/pull-requests`, and `/api/v1/pull-requests/{prId}/status` remain compatibility shims during migration.
- Shims derive tenant from persisted pipeline/version/repo/PR records, apply the same authorization checks, and return the same structured errors.

Physical backend owners:

- Migration: create the next available Flyway file after workspace events. If the repo head remains `V112__tenant_git_identity_scaffold.sql`, this is `backend/src/main/resources/db/migration/V116__git_repo_scope_and_pr_sync.sql`.
- Model owners: `backend/src/main/java/com/pulse/git/model/GitRepo.java` and `backend/src/main/java/com/pulse/git/model/PullRequest.java`.
- Repository owners: `backend/src/main/java/com/pulse/git/repository/GitRepoRepository.java` and `backend/src/main/java/com/pulse/git/repository/PullRequestRepository.java`.
- Service owner: existing or new Git service behind `backend/src/main/java/com/pulse/git/controller/GitController.java`.
- `V116` normalizes repo scope before PR sync work: change the model/database default from legacy `LEGACY` to `TENANT`, keep only API-visible scopes `PIPELINE|DOMAIN|TENANT`, and add/confirm nullable `pipeline_id` and `domain_id` columns with check constraints matching scope.
- Scope backfill rules for existing `git_repos` rows: rows with `pipeline_id` become `PIPELINE`; rows with null `pipeline_id` and non-null `domain_id` become `DOMAIN`; rows with both null become `TENANT`; rows with legacy metadata linking to a pipeline/domain are resolved by that metadata before falling back to `TENANT`.
- If multiple legacy rows collapse to the same exact scope, keep the most recently updated row as the active attachment, mark older rows `metadata.migratedDuplicate=true`, and exclude those duplicates from exact/effective lookup. Do not delete rows in `V116`.
- After backfill, API serialization must never return `scope="LEGACY"`; compatibility shims may read legacy rows only through the normalized repository methods.
- `V116` adds PR provider sync/idempotency fields: `provider_pr_id`, `provider_sync_status`, `provider_sync_error_code`, `provider_sync_error_message`, `last_sync_attempt_at`, and `retry_idempotency_key`, plus a unique index for create/status idempotency lookups scoped by tenant.
- `GitRepoRepository` adds exact lookup methods for `(tenantId, scope, pipelineId/domainId/null)`, effective lookup by pipeline resolution order, active tenant repo lookup, duplicate-collapse filters, and normalized attach/detach methods that ignore rows marked `metadata.migratedDuplicate=true`.
- `PullRequestRepository` adds lookups by `(tenantId, idempotencyKey)` and by duplicate open PR key `(tenantId, gitRepoId, versionId, sourceBranch, targetBranch, status='OPEN')`.

Onboarding and branch management:

- Tenant onboarding/status endpoints remain canonical tenant-scoped Git setup APIs.
- `GET .../onboarding-status` returns `{ "onboarded": boolean, "gitRepo": GitRepoDTO | null, "missing": string[] }`.
- `POST .../onboard` creates or links the tenant-scoped repo and returns onboarding status.
- `GET .../git-repo` returns the exact tenant-scoped repo or `204 No Content` when absent.
- `PUT .../git-repo` updates tenant repo metadata using `GitRepoAttachRequest` and returns `200 GitRepoDTO`. Tenant repo creation remains `POST .../onboard`; `PUT .../git-repo` without an existing tenant repo returns `404 GIT_REPO_NOT_FOUND`.
- `GET .../git-repo/branches` returns branches for the tenant repo only and is used by Settings.
- `GET .../pipelines/{pipelineId}/git-repo` response includes the effective repo id after pipeline -> domain -> tenant resolution.
- IDE/classic pipeline Git panels must use `GET .../git-repos/{repoId}/branches` with the effective repo id from the pipeline repo response. They must not call tenant branch endpoints unless the effective repo scope is `TENANT`.
- `GET .../git-repo/branches` and `GET .../git-repos/{repoId}/branches` responses are `{ "items": string[], "currentBranch": "..." }`.
- `PUT .../git-repo/branch` body is `{ "branch": "...", "createIfMissing": false, "correlationId": "...", "idempotencyKey": "..." }` and returns `GitRepoDTO` for the tenant repo.
- `PUT .../git-repos/{repoId}/branch` body is the same and returns `GitRepoDTO` for the addressed effective repo after tenant and scope access checks.
- Domain attach/edit screens call `GET|POST|PUT .../domains/{domainId}/git-repo`. Pipeline attach/edit screens call `GET|POST|PUT .../pipelines/{pipelineId}/git-repo/attachment` for exact-scope reads and pipeline attach/update. `GET .../pipelines/{pipelineId}/git-repo` remains effective lookup only and is never the attach/update path. Settings may only attach/edit tenant repos through tenant onboarding/settings flows.
- Exact-scope read returns `200 GitRepoDTO` when that exact DOMAIN or PIPELINE attachment exists and `204 No Content` when it does not. It does not fall through to effective repo.
- Unified repo attach/edit request body is `GitRepoAttachRequest`.
- Settings uses onboarding and branch endpoints. Classic and IDE Git panels read status/repo but do not own tenant onboarding setup beyond linking to Settings.

Domain/pipeline attach:

- Domain and pipeline attach endpoints are canonical tenant-scoped replacements for the current unscoped domain/pipeline attach routes.
- `GitRepoAttachRequest` is `{ "provider": "GITHUB|GITLAB|BITBUCKET|LOCAL|OTHER", "repoUrl": "...", "defaultBranch": "...", "currentBranch": "...", "deployKeyId": null | "...", "metadata": {}, "correlationId": "..." }`.
- Attach response is `GitRepoDTO`.
- Required fields: `provider`, `repoUrl`, `defaultBranch`, and `correlationId`. `currentBranch` defaults to `defaultBranch` when omitted. `metadata` defaults to `{}`. `deployKeyId` may be null.
- Provider validation: `LOCAL` allows `repoUrl` starting with `file://` or `/`; hosted providers require `https://` URL. `deployKeyId` is forbidden for `LOCAL` and optional for hosted providers.
- Scope validation: DOMAIN attach requires an existing domain in the path tenant. PIPELINE attach requires pipeline in path tenant. Opening DOMAIN scope from a pipeline without `domainId` returns `400 GIT_DOMAIN_REQUIRED`.
- `POST` creates an attachment and returns `201 Created` with `GitRepoDTO`.
- `POST` when exact-scope attachment already exists returns `409 GIT_REPO_ATTACHMENT_EXISTS` with `details.repoId`; clients should switch to `PUT`.
- `PUT` updates an existing exact-scope attachment and returns `200 OK` with `GitRepoDTO`.
- `PUT` when exact-scope attachment does not exist returns `404 GIT_REPO_ATTACHMENT_NOT_FOUND`; clients may switch to `POST`.
- `DELETE .../domains/{domainId}/git-repo` and `DELETE .../pipelines/{pipelineId}/git-repo/attachment` detach the exact DOMAIN or PIPELINE attachment only. They never detach a tenant fallback repo and never delete provider-side repositories.
- Detach request body is `{ "correlationId": "...", "idempotencyKey": "..." }`. `idempotencyKey` is required on canonical routes and derived only by compatibility shims.
- Detach response is `{ "detachedRepoId": "...", "effectiveRepo": GitRepoDTO | null, "eventId": "..." }`; `effectiveRepo` is the new fallback after detaching the exact scope, or null if no repo remains in the resolution chain.
- Detach when exact-scope attachment is absent returns `404 GIT_REPO_ATTACHMENT_NOT_FOUND`.
- Exact-scope `GET` returns `200 GitRepoDTO` or `204 No Content`.
- Current unscoped `/api/v1/domains/{domainId}/git-repo` and `/api/v1/pipelines/{pipelineId}/git-repo` remain compatibility shims during migration. The tenant-scoped `/pipelines/{pipelineId}/git-repo` path is effective read-only lookup; tenant-scoped pipeline writes use `/pipelines/{pipelineId}/git-repo/attachment`.

Rules:

- Git repo lookup is pipeline-scoped because repo attachment may be domain/pipeline/tenant backed; response includes the effective attachment source.
- Effective repo resolution order is deterministic: pipeline-scoped repo first, then pipeline domain-scoped repo, then tenant-scoped onboarded repo.
- The first accessible repo in that order wins. A missing higher-priority repo falls through; a forbidden higher-priority repo returns `403 GIT_TENANT_MISMATCH` and does not fall through because that would hide an authorization issue.
- If no repo is found after the fallthrough chain, return `404 GIT_REPO_NOT_FOUND`.
- `GitRepoDTO.scope` is the effective attachment source: `PIPELINE`, `DOMAIN`, or `TENANT`.
- `GitRepoDTO.pipelineId` is set only for `PIPELINE` scope; `domainId` is set only for `DOMAIN` scope; both are null for `TENANT` scope.
- Effective lookup `GET .../pipelines/{pipelineId}/git-repo` additionally includes `attachments: { "pipeline": GitRepoDTO | null, "domain": GitRepoDTO | null, "tenant": GitRepoDTO | null }` so setup/edit UI can show the exact-scope state without issuing extra reads. Exact-scope reads remain canonical when opening an edit dialog directly.
- Pull-request list is version-scoped and requires explicit path `versionId`.
- Pull-request create requires request `versionId`, `pipelineId`, `title`, `description`, `sourceBranch`, `targetBranch`, `correlationId`.
- Request `versionId` must belong to `pipelineId`, and repo must be valid for that pipeline/domain/tenant.
- Pull-request status update validates tenant and PR ownership before mutation.
- Pull-request status update body is `{ "status": "OPEN|MERGED|CLOSED", "mergeCommitHash": "...", "closedReason": "...", "correlationId": "...", "idempotencyKey": "..." }`. `idempotencyKey` is required on canonical route and derived only by compatibility shims.
- Allowed PR transitions: `OPEN -> MERGED`, `OPEN -> CLOSED`, `CLOSED -> OPEN`. `MERGED` is terminal.
- `MERGED` requires `mergeCommitHash`.
- Status update response is `PullRequestDTO`.
- Attach emits `GIT_REPO_ATTACHED`; update emits `GIT_REPO_UPDATED`; detach emits `GIT_REPO_DETACHED`; branch switch/create emits `GIT_BRANCH_CHANGED`; pull-request create emits `GIT_PULL_REQUEST_CREATED`; status update emits `GIT_PULL_REQUEST_STATUS_CHANGED`.
- IDE Git tab never uses active/latest. It receives `selectedVersionId` from `PipelineRouteStateController`.
- Classic Git panel may remain visible on classic route only when `selectedVersionId` exists; otherwise it renders disabled with Open IDE/select-version CTA.

DTOs:

- `GitRepoDTO`: `id`, `tenantId`, `scope`, `pipelineId`, `domainId`, `provider`, `repoUrl`, `repoType`, `defaultBranch`, `currentBranch`, `onboarded`, `capabilities`, `metadata`, and optional `attachments` only on effective pipeline lookup.
- `GitRepoCapabilitiesDTO`: `canAttach`, `canEdit`, `canDetach`, `canSwitchBranch`, `canCreateBranch`, `canCreatePullRequest`, `canUpdatePullRequestStatus`, `allowedAttachScopes: Array<"PIPELINE"|"DOMAIN"|"TENANT">`, `readOnlyReasonCode: GitCapabilityDisabledReason | null`, `readOnlyReasonLabel: string | null`, `disabledReasons: Partial<Record<"attach"|"edit"|"detach"|"switchBranch"|"createBranch"|"createPullRequest"|"updatePullRequestStatus", GitCapabilityDisabledReason>>`.
- `GitCapabilityDisabledReason` allowed values: `NO_REPO`, `READ_ONLY_PROVIDER`, `MISSING_PERMISSION`, `MISSING_DOMAIN`, `TENANT_REPO_REQUIRED`, `UNSUPPORTED_PROVIDER`, `BRANCH_NOT_FOUND`, `SYNC_PENDING`, `PROVIDER_AUTH_REQUIRED`, `UNKNOWN`.
- Effective lookup returns capability fields for the effective repo plus attachment capability by scope. Exact-scope lookup returns capabilities for that exact attachment/scope.
- Versionless pipeline attach/update/detach uses PIPELINE scope capability and requires pipeline write permission, not version write permission. PR creation/status remains PIPELINE_VERSION only.
- Pipeline attach/update/detach always emits PIPELINE-scoped `GIT_REPO_*` workspace events, even when initiated from the IDE Git tab while a version is selected. The event payload may include `initiatingVersionId` for UI context, but `workspace_events.scope` remains PIPELINE and `version_id` remains null. Versioned events/audit panels include those events only when filtering by `correlationId`; their default PIPELINE_VERSION feed does not mix in pipeline-scope Git setup events.
- Git workspace-event payloads use `attachmentScope` for Git attachment scope. They must not use a payload field named `scope`, because top-level `WorkspaceEventDTO.scope` already means event scope (`GLOBAL|DOMAIN|PIPELINE|PIPELINE_VERSION`). `attachmentScope="TENANT"` maps to top-level event `scope="GLOBAL"` for tenant-level Git setup events.
- Disable rules: action-specific `disabledReasons.<key>` wins over `readOnlyReasonCode`; if the key is absent and the capability is false, use `readOnlyReasonCode`; if both are absent, use `UNKNOWN`. Key mapping is `attach -> canAttach`, `edit -> canEdit`, `detach -> canDetach`, `switchBranch -> canSwitchBranch`, `createBranch -> canCreateBranch`, `createPullRequest -> canCreatePullRequest`, and `updatePullRequestStatus -> canUpdatePullRequestStatus`. UI labels derive from the enum and may use `readOnlyReasonLabel` for supplemental detail.
- `PullRequestDTO`: `id`, `tenantId`, `gitRepoId`, `pipelineId`, `versionId`, `title`, `description`, `sourceBranch`, `targetBranch`, `status`, `prUrl`, `providerPrId`, `providerSyncStatus`, `providerSyncErrorCode`, `providerSyncErrorMessage`, `lastSyncAttemptAt`, `retryIdempotencyKey`, `createdAt`, `updatedAt`, `metadata`.
- `providerSyncStatus` is `PENDING|SYNCED|FAILED|NOT_APPLICABLE`. Local/stub providers use `NOT_APPLICABLE` when no provider sync is required.
- Provider sync fields are authoritative top-level `PullRequestDTO` fields. Persistence may store them as columns or metadata internally, but API serialization must always populate the top-level fields and UI must not read `metadata.providerSyncStatus`.
- `retryIdempotencyKey` is returned only when a failed provider sync can be retried.

Git attach/update/detach/branch mutation ordering:

- Attach/update request body is `GitRepoAttachRequest` plus required `idempotencyKey` on canonical routes: `{ "provider": "...", "repoUrl": "...", "defaultBranch": "...", "currentBranch": "...", "deployKeyId": null, "metadata": {}, "correlationId": "...", "idempotencyKey": "..." }`.
- UI idempotency key formulas:
  - Domain attach/update: `git:attach-domain:{domainId}:{correlationId}` / `git:update-domain:{repoId}:{correlationId}`.
  - Pipeline attach/update: `git:attach-pipeline:{pipelineId}:{correlationId}` / `git:update-pipeline:{repoId}:{correlationId}`.
  - Detach: `git:detach:{repoId}:{scope}:{correlationId}`.
  - Branch switch/create: `git:branch:{repoId}:{branch}:{createIfMissing}:{correlationId}`.
- Attach/update sequence: validate scope, permissions, URL/provider, duplicate exact attachment, and capability; persist repo row changes and record `GIT_REPO_ATTACHED` or `GIT_REPO_UPDATED` in the same transaction; return `GitRepoDTO` plus event id in response metadata.
- Detach sequence: validate exact-scope attachment and capability; mark attachment detached or inactive according to existing model constraints; record `GIT_REPO_DETACHED` in the same transaction; recompute fallback effective repo for response.
- IDE Git setup event visibility: after attach/update/detach from the IDE, the Git tab refetches repo/branches/PRs and shows a success toast with `View audit`. It does not inject the PIPELINE-scoped event into the default version-scoped events panel. If the user clicks `View audit`, `WorkspaceShellProvider` opens `bottom=audit&correlationId=<correlationId>`, which queries across scopes by correlation id and displays the PIPELINE-scoped Git event as audit evidence.
- Branch sequence: validate repo access, branch name, provider capability, and branch existence/create policy; update `currentBranch` and record `GIT_BRANCH_CHANGED` in the same transaction. If provider branch creation is required and provider call fails before local branch update, return provider error with no local mutation; if event write fails, roll back local mutation.
- Same idempotency key with identical payload returns the original response. Same key with different payload returns `409 GIT_IDEMPOTENCY_CONFLICT`.
- Event write failure returns `500 WORKSPACE_EVENT_WRITE_FAILED`; no provider side effect is attempted for attach/update/detach. For branch create on a hosted provider, provider branch creation is attempted only after local validation but before final local current-branch update/event transaction; if the event transaction fails after provider branch creation, return `500 WORKSPACE_EVENT_WRITE_FAILED` with `details.providerBranchCreated=true`.

Pull-request list:

- `GET .../pipeline-versions/{versionId}/pull-requests` returns a paginated envelope:

```json
{
  "items": [
    {
      "id": "...",
      "tenantId": "...",
      "gitRepoId": "...",
      "pipelineId": "...",
      "versionId": "...",
      "title": "...",
      "description": "...",
      "sourceBranch": "...",
      "targetBranch": "...",
      "status": "OPEN",
      "prUrl": "...",
      "providerPrId": "...",
      "providerSyncStatus": "SYNCED",
      "providerSyncErrorCode": null,
      "providerSyncErrorMessage": null,
      "lastSyncAttemptAt": "ISO-8601",
      "retryIdempotencyKey": null,
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601",
      "metadata": {}
    }
  ],
  "nextCursor": null
}
```

- Optional filters: `status`, `limit`, `cursor`.
- `limit` default 50, max 100.
- Cursor is base64url JSON `{ "updatedAt": "ISO-8601", "id": "..." }`.
- Sort order is `updatedAt DESC, id DESC`.
- Empty list returns `{ "items": [], "nextCursor": null }`.
- List rows are full `PullRequestDTO` objects and always include provider sync fields, even when values are null.

Pull-request create:

```ts
type PullRequestCreateRequest = {
  pipelineId: string;
  versionId: string;
  title: string;
  description: string;
  sourceBranch: string;
  targetBranch: string;
  correlationId?: string;
  idempotencyKey: string;
};
```

- `POST .../git-repos/{repoId}/pull-requests` returns `201 Created`, `200 OK`, or `202 Accepted` with `PullRequestDTO` for successful/accepted create or replay states. Provider failure after local/event commit returns structured `ApiErrorBody` with `code="GIT_PROVIDER_CREATE_FAILED"` and `details.pullRequest` containing the failed `PullRequestDTO`.
- Required fields must be non-empty strings after trim.
- `idempotencyKey` is required and must be stable for retries of the same create action. The client derives it as `pr:create:{repoId}:{versionId}:{sourceBranch}:{targetBranch}:{correlationId}` unless a server-provided `retryIdempotencyKey` is being retried.
- `repoId` must equal the effective repo id for the supplied `pipelineId`/`versionId`, or be a valid accessible repo in the pipeline -> domain -> tenant resolution chain; otherwise return `403 GIT_REPO_SCOPE_MISMATCH`.
- Duplicate open PR from the same `sourceBranch` to `targetBranch` for the same `versionId` returns `409 GIT_PULL_REQUEST_ALREADY_EXISTS` with `details.pullRequestId`.
- On success, status is `OPEN`, `createdAt`/`updatedAt` are server timestamps, and `prUrl` is provider URL when available or null when the backing provider is local/stubbed.

Pull-request mutation ordering and audit durability:

- PR create sequence:
  1. Validate tenant, version, pipeline, repo scope, branches, and duplicate open PR locally.
  2. Create a pending local PR row with status `OPEN`, provider status `PENDING`, and request `idempotencyKey` in the same DB transaction that records `GIT_PULL_REQUEST_CREATED`.
  3. Commit the local row and workspace event.
  4. Call provider to create the PR.
  5. Update local row with provider URL/id and provider status `SYNCED`.
- If workspace-event write fails before the provider call, roll back the local row and return `500 WORKSPACE_EVENT_WRITE_FAILED`; no provider PR exists.
- If provider create fails after local/event commit, update local row provider status fields to `FAILED`, keep the audit event, return structured `502 GIT_PROVIDER_CREATE_FAILED` with `details.pullRequest`, and allow retry with the same idempotency key.
- Retry with same idempotency key returns the existing successful PR when provider status is `SYNCED`; if provider status is `FAILED`, it retries provider creation without creating duplicate local/event rows.
- Same-key create replay returns `200 OK` with existing `PullRequestDTO` when the local row already exists. A first successful create returns `201 Created`. If provider sync is still `PENDING`, replay returns `202 Accepted` with the same `PullRequestDTO` and no second provider call.
- PR status update sequence:
  1. Validate transition and write local status-change intent plus `GIT_PULL_REQUEST_STATUS_CHANGED` event in one DB transaction using request `idempotencyKey` or, for compatibility shims only, derived key `pr:status:{prId}:{targetStatus}:{correlationId}`.
  2. Call provider status/merge/close API where supported.
  3. Mark provider status synced or failed in local metadata.
- If event write fails before provider status call, roll back local intent and return `500 WORKSPACE_EVENT_WRITE_FAILED`; no provider mutation was attempted.
- If provider status call fails after local/event commit, keep local desired status, set top-level provider sync fields to `FAILED`, return structured `502 GIT_PROVIDER_STATUS_FAILED` with `details.pullRequest`, and show row sync error in UI. A retry with the same idempotency key resumes provider sync without duplicating workspace events.
- Same-key status replay returns `200 OK` with current `PullRequestDTO` when sync is `SYNCED` or `NOT_APPLICABLE`; returns `202 Accepted` when sync is `PENDING`; retries provider sync and returns the resulting `PullRequestDTO` when sync is `FAILED`.
- Local/stub providers complete steps 4/2 synchronously without external compensation.

Provider sync state machine:

| State | Meaning | Create flow transition | Status flow transition | UI behavior |
| --- | --- | --- | --- | --- |
| `PENDING` | Local row/event committed; provider call not complete. | Set before provider create call; replay returns `202`. | Set before provider status call; replay returns `202`. | Show syncing badge; disable duplicate action. |
| `SYNCED` | Local row and provider state agree. | Final create state for hosted providers. | Final state after provider status success. | Normal row actions based on PR status. |
| `FAILED` | Provider call failed after local/event commit. | Provider create failure sets `FAILED`, stores error fields and `retryIdempotencyKey`. | Provider status failure sets `FAILED`, stores error fields and `retryIdempotencyKey`. | Show error badge, retry action, and parsed provider error. |
| `NOT_APPLICABLE` | No provider sync required. | Local/stub providers set immediately. | Local/stub providers set immediately. | No provider badge; actions complete locally. |

First response examples:

- First hosted create with provider success: `201 Created`, `providerSyncStatus="SYNCED"`, provider URL/id populated.
- First hosted create with slow provider worker: `202 Accepted`, `providerSyncStatus="PENDING"`, `retryIdempotencyKey` populated.
- First hosted create with provider failure after local/event commit: `502 GIT_PROVIDER_CREATE_FAILED`, body is `{code,message,details}` with `details.pullRequest` set to the failed `PullRequestDTO` and `providerSyncStatus="FAILED"`.
- Same-key replay for synced create/status: `200 OK`, full `PullRequestDTO`.
- Same-key replay while pending: `202 Accepted`, full `PullRequestDTO`.
- Same-key replay after failure retries provider sync; success returns `200 OK` with `SYNCED`, repeated failure returns `502` with updated provider error fields.

Git panel workflow:

- Classic and IDE use the same Git panel controller logic but different chrome. Classic renders inside `frontend/src/components/pipeline/git-panel.tsx`; IDE Git tab reuses its presentational sections under `tab=git`.
- Panel bootstrap requires `tenantId`, `pipelineId`, and `selectedVersionId`. If `selectedVersionId` is null, render disabled state with actions `Select version` and `Open IDE`; do not fetch PRs.
- Bootstrap sequence:
  1. Fetch `GET .../pipelines/{pipelineId}/git-repo`.
  2. Always fetch `GET .../pipeline-versions/{selectedVersionId}/pull-requests?limit=50`; PR history is version-scoped and remains visible even if the effective repo is missing later.
  3. If repo exists, fetch `GET .../git-repos/{repoId}/branches`.
- If repo lookup returns `GIT_REPO_NOT_FOUND`, show repo setup empty state above the PR history. Classic and IDE show `Connect repository`, which opens `GitRepoAttachDialog`; PR creation and branch switching are disabled until attach succeeds, but existing PR rows for the selected version remain visible in read-only history mode.
- Concrete setup surface is `frontend/src/components/git/git-repo-attach-dialog.tsx`, opened from classic and IDE panels as `GitRepoAttachDialog`.
- Empty-state `Connect repository` opens that dialog with default `scope="PIPELINE"` for pipeline pages, `pipelineId=current pipelineId`, `domainId=pipeline.domainId`, and `tenantId=currentTenant.id`.
- The dialog offers scope choices in precedence order `PIPELINE`, `DOMAIN`, `TENANT`. `PIPELINE` is the default because it creates the highest-priority effective repo for the current pipeline; `DOMAIN` is available when `pipeline.domainId` exists; `TENANT` links to Settings onboarding instead of submitting from the panel.
- Dialog request payload for `PIPELINE` calls `POST .../pipelines/{pipelineId}/git-repo/attachment`; `DOMAIN` calls `POST .../domains/{domainId}/git-repo`; updates call the matching `PUT` endpoint when an existing attachment for that exact scope is being edited.
- Dialog prefill uses effective lookup `attachments.<scope>` when available; otherwise it calls the exact-scope GET. Existing exact-scope attachment means submit `PUT`; no exact-scope attachment means submit `POST`.
- Dialog fields are exactly `GitRepoAttachRequest` plus canonical idempotency: `provider`, `repoUrl`, `defaultBranch`, `currentBranch`, optional `deployKeyId`, `metadata`, hidden `correlationId`, and hidden `idempotencyKey`. Provider defaults to `GITHUB`; branch defaults to `main`.
- On attach/update success, close dialog, toast success with `View audit`, refetch pipeline effective repo, branch list, and PR list. `View audit` opens `bottom=audit&correlationId=<correlationId>` so the PIPELINE-scoped setup event is visible by correlation without polluting the default version event feed. On error, keep dialog open and render parsed `ApiError`.
- Branch selector defaults to `GitRepoDTO.currentBranch || GitRepoDTO.defaultBranch`.
- Branch switch action calls `PUT .../git-repos/{repoId}/branch` with `{branch, createIfMissing:false, correlationId, idempotencyKey}`. On success, update repo/branch state, refetch PR list, and toast `Branch switched`. On error, keep previous branch and show parsed `ApiError`.
- Optional `Create branch` affordance may call the same endpoint with `createIfMissing:true`; it is disabled for read-only/local provider states until provider support exists.
- PR create dialog defaults:
  - `versionId`: selected version from `PipelineRouteStateController`.
  - `pipelineId`: current pipeline id.
  - `sourceBranch`: current branch.
  - `targetBranch`: repo default branch.
  - `title`: `<pipeline name>: version <version label>`.
  - `description`: generated summary with selected version id, latest generation/package references when available, and editable markdown.
- PR create is disabled when no repo, no selected version, no branch list, branch fetch failed, or an open duplicate is visible for the same source/target/version.
- PR create success prepends/updates the PR in the list, emits success toast, and leaves the dialog closed. It does not navigate away.
- PR list shows status, source -> target, created/updated time, provider URL, and actions. Provider URL opens in a new tab only for trusted http(s) URLs.
- Status actions:
  - `OPEN` PRs show `Mark merged` and `Close`.
  - `CLOSED` PRs show `Reopen`.
  - `MERGED` PRs are terminal and show no status action.
  - `Mark merged` requires `mergeCommitHash`; `Close` may include `closedReason`; all status actions include `correlationId` and `idempotencyKey`.
  - Status idempotency key formula: `pr:status:{prId}:{targetStatus}:{correlationId}` for `Close`/`Reopen`, and `pr:status:{prId}:MERGED:{mergeCommitHash}:{correlationId}` for `Mark merged`. If retrying a provider-sync failure, use `retryIdempotencyKey` returned by `PullRequestDTO`.
- Status action success refetches the affected PR list page or updates the row from response, then toasts. Failure keeps row state and shows parsed error.
- Refresh behavior: refetch repo/branches/PRs on version change, branch switch success, PR create/status success, window focus after 60 seconds stale, and explicit refresh button. Do not poll continuously in Phase 5.
- Classic Git panel may expose only repo status, branch selector, PR list, and PR status actions. IDE Git tab additionally includes command-search integration and bottom events/audit links.

Errors:

- `GIT_REPO_NOT_FOUND`
- `GIT_REPO_ATTACHMENT_EXISTS`
- `GIT_REPO_ATTACHMENT_NOT_FOUND`
- `GIT_DOMAIN_REQUIRED`
- `GIT_PROVIDER_URL_INVALID`
- `GIT_DEPLOY_KEY_FORBIDDEN`
- `GIT_VERSION_REQUIRED`
- `GIT_VERSION_PIPELINE_MISMATCH`
- `GIT_TENANT_MISMATCH`
- `GIT_REPO_SCOPE_MISMATCH`
- `GIT_PULL_REQUEST_NOT_FOUND`
- `GIT_PULL_REQUEST_ALREADY_EXISTS`
- `GIT_PROVIDER_CREATE_FAILED`
- `GIT_PROVIDER_STATUS_FAILED`
- `GIT_ATTACH_FORBIDDEN`
- `GIT_EDIT_FORBIDDEN`
- `GIT_BRANCH_SWITCH_FORBIDDEN`
- `GIT_BRANCH_CREATE_FORBIDDEN`
- `GIT_BRANCH_INVALID`
- `GIT_BRANCH_NOT_FOUND`
- `GIT_BRANCH_CREATE_UNSUPPORTED`

All use `{code,message,details}`.

Blocked Git operation errors:

| Condition | HTTP | Code | Details |
| --- | --- | --- | --- |
| Exact-scope read forbidden by tenant/auth | 403 | `GIT_TENANT_MISMATCH` | `tenantId`, `scope`, optional `pipelineId`, optional `domainId` |
| `canAttach=false` attach attempt | 403 | `GIT_ATTACH_FORBIDDEN` | `scope`, `readOnlyReasonCode`, `allowedAttachScopes` |
| `canEdit=false` update attempt | 403 | `GIT_EDIT_FORBIDDEN` | `repoId`, `scope`, `readOnlyReasonCode` |
| `canSwitchBranch=false` branch switch | 403 | `GIT_BRANCH_SWITCH_FORBIDDEN` | `repoId`, `readOnlyReasonCode` |
| `canCreateBranch=false` with `createIfMissing=true` | 403 | `GIT_BRANCH_CREATE_FORBIDDEN` | `repoId`, `provider`, `readOnlyReasonCode` |
| Branch name empty, contains whitespace-only, starts with `/`, ends with `/`, contains `..`, `~`, `^`, `:`, `?`, `*`, `[`, `\\`, or ASCII control | 400 | `GIT_BRANCH_INVALID` | `branch` |
| Branch does not exist and `createIfMissing=false` | 404 | `GIT_BRANCH_NOT_FOUND` | `repoId`, `branch` |
| `createIfMissing=true` on provider without branch creation support | 400 | `GIT_BRANCH_CREATE_UNSUPPORTED` | `repoId`, `provider` |
| Hosted provider URL is not `https://` | 400 | `GIT_PROVIDER_URL_INVALID` | `provider`, `repoUrl` |
| `LOCAL` provider has `deployKeyId` | 400 | `GIT_DEPLOY_KEY_FORBIDDEN` | `provider` |

These errors are returned even if the frontend should have disabled the action from capabilities; server remains authoritative.

## EBCDIC

Route remains `/ebcdic-discovery`.

Primary owners after route-group migration:

- Page route: `frontend/src/app/(app)/ebcdic-discovery/page.tsx`.
- State owner: `frontend/src/contexts/ebcdic-discovery-context.tsx`.
- Workspace UI: `frontend/src/components/ebcdic-discovery/discovery-workspace.tsx`.
- Backend API owner: `backend/src/main/java/com/pulse/cobol/controller/CobolDiscoveryController.java`.
- Migration: create the next available Flyway file after Git scope/PR sync. If the repo head remains `V112__tenant_git_identity_scaffold.sql`, this is `backend/src/main/resources/db/migration/V117__ebcdic_scoped_sessions.sql`.
- Model owners: `backend/src/main/java/com/pulse/cobol/model/CobolDiscoverySession.java` and `backend/src/main/java/com/pulse/cobol/model/CobolDiscoveryRun.java`.
- Repository owners: `backend/src/main/java/com/pulse/cobol/repository/CobolDiscoverySessionRepository.java` and `backend/src/main/java/com/pulse/cobol/repository/CobolDiscoveryRunRepository.java`.
- Service owner: existing COBOL/EBCDIC discovery service behind `CobolDiscoveryController`.

Migration/backfill:

- `V117` adds `scope varchar(32) not null default 'GLOBAL'`, nullable `pipeline_id varchar(26)`, and nullable `correlation_id varchar(96)` to discovery sessions.
- `V117` adds `correlation_id varchar(96)` to discovery runs if absent and indexes runs by `(tenant_id, session_id, updated_at desc, id desc)` and `(tenant_id, session_id, run_type, status, updated_at desc, id desc)`.
- Backfill existing sessions to `scope='GLOBAL'`, `pipeline_id=null`, and preserve existing `tenant_id`, `user_id`, and `title`.
- Add check constraint: `scope in ('GLOBAL','PIPELINE')`, `GLOBAL` requires `pipeline_id is null`, `PIPELINE` requires `pipeline_id is not null`.
- Add foreign key from `pipeline_id` to `pipelines(id)` with `on delete restrict`.
- Session repository methods: latest by `(tenantId,userId,scope,pipelineId)`, paged list by same key, exact session by tenant/id, and scope/pipeline access validation.
- Run repository methods: latest run by session, latest completed profile run by session, run by tenant/id through session join, and run events by run id.

Add:

Scoped routing:

- Public route remains `/ebcdic-discovery`.
- GLOBAL EBCDIC entry uses `/ebcdic-discovery`.
- PIPELINE EBCDIC entry uses `/ebcdic-discovery?scope=PIPELINE&pipelineId=<pipelineId>`.
- Unknown query keys are ignored.
- `scope=PIPELINE` without `pipelineId` shows a local invalid-context state and does not create a session.
- `frontend/src/contexts/ebcdic-discovery-context.tsx` reads `scope` and `pipelineId` from the route during bootstrap and uses them for restore/create.
- Command search in PIPELINE scope returns the pipeline URL above. GLOBAL command search returns bare `/ebcdic-discovery`.

```http
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/latest?userId=&scope=&pipelineId=
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions?userId=&scope=&pipelineId=&limit=&cursor=
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/messages
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/messages
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/copybook
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/data-file
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/artifacts
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/stream
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/runs/preview
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/runs/profile
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}/events
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}/stream
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}/preview-table
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}/profiling
POST /api/v1/tenants/{tenantId}/ebcdic-discovery/runs/{runId}/cancel
```

Create body:

```json
{
  "scope": "GLOBAL|PIPELINE",
  "pipelineId": "...",
  "title": "...",
  "userId": "...",
  "correlationId": "..."
}
```

Create rules:

- Omitted `scope` defaults to `GLOBAL`.
- `GLOBAL` requires `pipelineId=null`.
- `PIPELINE` requires `pipelineId` and validates tenant access to the pipeline.
- `PIPELINE_VERSION` is not accepted in this plan and returns `400 EBCDIC_SCOPE_UNSUPPORTED`.
- `title` remains the persisted display field on `CobolDiscoverySession`; do not introduce a separate persisted `name` field. If a client still sends `name` during migration, map it to `title` and log `legacy_ebcdic_session_name`.
- `userId` remains accepted during the current dev-auth period. When omitted, server uses authenticated principal id when available; otherwise it uses literal fallback `current-user`. Once actor resolution is mandatory, the server derives user identity from auth and ignores request-body `userId`.
- Created sessions persist existing `tenantId`, `userId`, and `title` fields plus new `scope`, nullable `pipelineId`, and `correlationId`.
- Session GET/create responses include `id`, `tenantId`, `userId`, `title`, `scope`, `pipelineId`, `correlationId`, `createdAt`, and `updatedAt`.
- Copybook upload, data upload, preview run, profile run, latest-run, latest-profile-run, and profile save derive event scope from the persisted discovery session.
- If a run or profile save references a session whose pipeline has become inaccessible, the API returns `403 EBCDIC_PIPELINE_FORBIDDEN` and emits no pipeline-scoped event.

Existing endpoint scope rules:

- The endpoints listed above are canonical tenant-scoped EBCDIC endpoints; they are not compatibility shims.
- Every session endpoint validates path tenant, session existence, persisted `scope`, and persisted `pipelineId` access before reading or mutating.
- Every run endpoint validates path tenant through run -> session, then applies the persisted session scope/pipeline access rules.
- Message send accepts `content`, `currentOptionOverrides`, and optional `correlationId`; it records GLOBAL or PIPELINE workspace events from the persisted session scope.
- Copybook upload and data-file upload emit `EBCDIC_COPYBOOK_UPLOADED` and `EBCDIC_DATA_UPLOADED` using persisted session scope.
- Preview/profile run creation accepts existing `optionOverrides`, `sampleRows`, `autoRefine`, plus optional `correlationId`; it emits started/completed/failed events using persisted session scope.
- Cancel run emits an EBCDIC run event with status `CANCELLED` using persisted session scope.
- Session and run SSE streams use the same tenant/scope validation before opening the stream and close with structured error events when validation fails.
- `/preview-table` and `/profiling` are read-only run result endpoints and return structured errors when run/session scope validation fails.
- All non-2xx responses from these endpoints use `{code,message,details}` through `ApiException`.

EBCDIC idempotency and retry classification:

- `POST /ebcdic-discovery/sessions` is retryable and requires `X-Correlation-Id` and `Idempotency-Key`. Formula: `ebcdic:session:create:{tenantId}:{scope}:{pipelineIdOrGlobal}:{titleHash}:{correlationId}`. Replay with the same key returns the original `EbcdicSessionDTO`. Same key with different normalized scope/title/body returns `409 IDEMPOTENCY_CONFLICT`.
- `POST .../sessions/{sessionId}/messages` is retryable and requires both headers. Formula: `ebcdic:message:{sessionId}:{contentHash}:{optionOverridesHash}:{correlationId}` where `optionOverridesHash` is the stable hash of normalized `currentOptionOverrides` or `none`. Replay returns the originally persisted message response and must not create duplicate user/assistant messages or duplicate workspace events.
- `POST .../copybook` and `POST .../data-file` are retryable uploads and require both headers. Formula: `ebcdic:artifact:{sessionId}:{artifactType}:{filename}:{sha256(fileBytes)}:{correlationId}`. Replay returns the existing `EbcdicArtifactDTO`. Same key with different file hash or artifact type returns `409 IDEMPOTENCY_CONFLICT`.
- `POST .../runs/preview` and `POST .../runs/profile` are retryable run creation endpoints and require both headers. Formula: `ebcdic:run:{sessionId}:{runType}:{optionHash}:{sampleRows}:{autoRefine}:{latestArtifactSetHash}:{correlationId}`. Replay returns the original `EbcdicRunDTO`; it does not enqueue a second run.
- `POST .../runs/{runId}/cancel` is retryable and requires both headers. Formula: `ebcdic:run:cancel:{runId}:{correlationId}`. Replay returns the current run DTO; if the run already reached `CANCELLED`, the response is 200 with the cancelled run; if it reached `COMPLETED` or `FAILED`, return `409 EBCDIC_RUN_ALREADY_TERMINAL`.
- `POST /api/v1/tenants/{tenantId}/cobol-profiles` is retryable and requires both headers. Formula: `ebcdic:profile:save:{runId}:{nameHash}:{correlationId}`. Replay returns the original saved profile DTO. Same key with different `runId` or normalized `name` returns `409 IDEMPOTENCY_CONFLICT`.
- Read-only EBCDIC GET endpoints do not require `Idempotency-Key`.
- Session/run SSE stream GET endpoints do not require `Idempotency-Key`; they propagate `X-Correlation-Id` when provided for telemetry only.
- Idempotency records store tenant, actor, endpoint family, normalized request hash, response status/body reference, first correlation id, and timestamps. Records are tenant-scoped and expire after at least 24 hours.
- EBCDIC workspace events are emitted only on first successful mutation for a given idempotency key. Replays return prior DTOs without duplicate events.

Latest/list restore rules:

- Latest filters by tenant, user, exact scope, and exact nullable pipeline.
- Latest order is `updatedAt DESC, id DESC`.
- Latest returns 204 when no matching session exists.
- List order is `updatedAt DESC, id DESC`.
- List `limit` default 50, max 100.
- List cursor is base64url JSON `{ "updatedAt": "ISO-8601", "id": "..." }`.
- List cursor predicate is `(updatedAt < cursor.updatedAt) OR (updatedAt = cursor.updatedAt AND id < cursor.id)`.
- `nextCursor` is derived from the last returned session when more rows exist; otherwise null.
- Empty list returns `{ "items": [], "nextCursor": null }`.
- Invalid scope/filter combinations return `400 EBCDIC_SCOPE_INVALID` with `{code,message,details}`.
- `frontend/src/contexts/ebcdic-discovery-context.tsx` restores latest for the route scope before creating a session.
- Switching from GLOBAL route to PIPELINE route switches restore key and does not reuse the previous session.

Session response:

```json
{
  "id": "...",
  "tenantId": "...",
  "userId": "...",
  "title": "...",
  "scope": "GLOBAL|PIPELINE",
  "pipelineId": "...",
  "correlationId": "...",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601"
}
```

Session list response:

```json
{
  "items": [
    {
      "id": "...",
      "tenantId": "...",
      "userId": "...",
      "title": "...",
      "scope": "GLOBAL|PIPELINE",
      "pipelineId": "...",
      "correlationId": "...",
      "createdAt": "ISO-8601",
      "updatedAt": "ISO-8601"
    }
  ],
  "nextCursor": null
}
```

Invalid scope error:

```json
{
  "code": "EBCDIC_SCOPE_INVALID",
  "message": "EBCDIC session scope is invalid",
  "details": {
    "scope": "PIPELINE",
    "pipelineId": null
  }
}
```

Message DTOs:

```ts
type EbcdicMessageDTO = {
  id: string;
  sessionId: string;
  tenantId: string;
  role: "user" | "assistant" | "system";
  content: string;
  metadata: Record<string, unknown>;
  createdAt: string;
};

type EbcdicMessageSendRequest = {
  content: string;
  currentOptionOverrides?: Record<string, unknown>;
  correlationId?: string;
};
```

Message send returns the persisted user `EbcdicMessageDTO` plus any synchronous assistant message in `{ "message": EbcdicMessageDTO, "assistantMessage": EbcdicMessageDTO | null }`. If the assistant reply is streamed later, `assistantMessage` is null and the session stream emits a `message` event.

Artifact DTOs:

```ts
type EbcdicArtifactDTO = {
  id: string;
  sessionId: string;
  tenantId: string;
  artifactType: "COPYBOOK" | "DATA_FILE";
  filename: string;
  contentType: string;
  sizeBytes: number;
  metadata: Record<string, unknown>;
  createdAt: string;
};
```

Copybook/data-file upload returns `EbcdicArtifactDTO`. Artifact listing returns `{ "items": EbcdicArtifactDTO[] }`, ordered by `createdAt DESC, id DESC`.

Run DTOs:

```ts
type EbcdicRunDTO = {
  id: string;
  sessionId: string;
  tenantId: string;
  runType: "preview" | "profile";
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  sampleRows: number | null;
  autoRefine: boolean;
  confidenceScore: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  profilingSummary: Record<string, unknown>;
  samplePolicy: Record<string, unknown>;
  resultSchemaSnapshot: Record<string, unknown>;
  mappingSpec: unknown[];
  correlationId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
};

type EbcdicRunEventDTO = {
  id: string;
  runId: string;
  tenantId: string;
  eventType: "RUN_QUEUED" | "RUN_STARTED" | "RUN_PROGRESS" | "RUN_COMPLETED" | "RUN_FAILED" | "RUN_CANCELLED";
  message: string;
  progress: number | null;
  payload: Record<string, unknown>;
  createdAt: string;
};
```

Preview/profile run creation returns `EbcdicRunDTO`. Run detail returns `EbcdicRunDTO`. Run events return `{ "items": EbcdicRunEventDTO[], "nextCursor": string | null }`, ordered by `createdAt ASC, id ASC`; cursor is base64url JSON `{ "createdAt": "ISO-8601", "id": "..." }`.

Result DTOs:

- `/preview-table` returns `{ "columns": Array<{ "name": string, "type": string | null }>, "rows": unknown[][], "rowCount": number, "sampleRows": number, "truncated": boolean, "runId": "..." }`.
- `/profiling` returns `{ "runId": "...", "confidenceScore": number | null, "profilingSummary": {}, "samplePolicy": {}, "resultSchemaSnapshot": {}, "mappingSpec": [] }`.

SSE schema:

- Session stream event names: `session_updated`, `message`, `artifact`, `run_started`, `run_progress`, `run_completed`, `run_failed`, `run_cancelled`, `error`, `heartbeat`.
- Run stream event names: `run_started`, `run_progress`, `run_completed`, `run_failed`, `run_cancelled`, `error`, `heartbeat`.
- Every SSE payload is `{ "eventId": "...", "tenantId": "...", "sessionId": "...", "runId": null | "...", "correlationId": null | "...", "data": {}, "createdAt": "ISO-8601" }`.
- Poll/read APIs remain authoritative in Phase 5. SSE is a live hint channel only; frontend handlers use SSE payloads for optimistic progress but refetch the affected session, messages, artifacts, run, or run events before rendering terminal authoritative state.

```http
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/latest-profile-run
GET /api/v1/tenants/{tenantId}/ebcdic-discovery/sessions/{sessionId}/latest-run
```

Response:

```json
{
  "id": "...",
  "sessionId": "...",
  "tenantId": "...",
  "runType": "profile",
  "status": "COMPLETED",
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601",
  "completedAt": "ISO-8601",
  "confidenceScore": 0.92,
  "profilingSummary": {},
  "samplePolicy": {},
  "resultSchemaSnapshot": {},
  "mappingSpec": []
}
```

Empty state:

- Return 204 No Content when no completed profile run exists for the session.

Latest run:

- `GET .../latest-run` is canonical and remains distinct from `/latest-profile-run`.
- It returns the most recent run for the session across preview and profile run types, ordered by `updatedAt DESC, id DESC`.
- It validates tenant and session scope exactly like `/latest-profile-run`.
- It returns 204 when the session has no runs.
- It returns 404 `EBCDIC_SESSION_NOT_FOUND` when the session is missing in the tenant.
- It returns 403 `EBCDIC_PIPELINE_FORBIDDEN` when the persisted session is PIPELINE scoped and the user/tenant no longer has access to the pipeline.
- Response shape is the same run DTO as `/latest-profile-run`, except `runType` may be `preview` or `profile`, and `status` may be any run status.

Errors:

```json
{
  "code": "EBCDIC_SESSION_NOT_FOUND",
  "message": "Discovery session not found",
  "details": {
    "sessionId": "..."
  }
}
```

Profile save remains runId-driven:

```http
POST /api/v1/tenants/{tenantId}/cobol-profiles
```

Request body remains:

```json
{
  "runId": "...",
  "name": "...",
  "description": "...",
  "userId": "..."
}
```

Backend rules:

- Load the run by `runId` and tenant.
- If no completed profile run exists for the run's session, return `400 EBCDIC_PROFILE_RUN_REQUIRED`.
- If `run.status` is not `COMPLETED`, return `400 EBCDIC_RUN_NOT_COMPLETED`.
- If `run.runType` is not `profile`, return `400 EBCDIC_PROFILE_RUN_REQUIRED`.
- The run must be the latest completed profile run for its session, as returned by `/latest-profile-run`; otherwise return `409 EBCDIC_PROFILE_RUN_STALE`.
- Preview runs cannot be saved as parsing profiles.
- No historical run selector is added in this plan.

Errors:

```json
{
  "code": "EBCDIC_RUN_NOT_COMPLETED",
  "message": "Only completed profile runs can be saved as parsing profiles.",
  "details": {
    "runId": "..."
  }
}
```

```json
{
  "code": "EBCDIC_PROFILE_RUN_REQUIRED",
  "message": "Run must be a profile run to be saved as a parsing profile.",
  "details": {
    "runId": "...",
    "runType": "preview"
  }
}
```

```json
{
  "code": "EBCDIC_PROFILE_RUN_STALE",
  "message": "A newer completed profile run exists. Save the latest profile run instead.",
  "details": {
    "runId": "...",
    "latestProfileRunId": "..."
  }
}
```

Frontend:

- Discovery context stores both `activeRun` from `/latest-run` and `latestProfileRun` from `/latest-profile-run`.
- Save Profile uses only `latestProfileRun.id`.
- If active run is preview but a latest completed profile exists, Save remains enabled and labels the target as latest completed profile run with timestamp/run id.
- If no latest completed profile run exists, Save is disabled with prompt to run profiling. If a stale/manual API call is attempted anyway, backend returns `EBCDIC_PROFILE_RUN_REQUIRED`.

Discovery context state contract:

```ts
type EbcdicDiscoveryState = {
  session: EbcdicSessionDTO | null;
  activeRun: EbcdicRunDTO | null;
  latestProfileRun: EbcdicRunDTO | null;
  isLoadingSession: boolean;
  isLoadingActiveRun: boolean;
  isLoadingLatestProfileRun: boolean;
  isSavingProfile: boolean;
  profileSaveError: ApiErrorBody | null;
  refreshRuns(reason: "bootstrap" | "run-created" | "run-terminal" | "profile-saved" | "focus" | "manual"): Promise<void>;
};
```

Refresh cadence:

- On route bootstrap and after session restore/create, fetch `/latest-run` and `/latest-profile-run` in parallel.
- When preview/profile run creation returns, set `activeRun` from the response, clear terminal save errors, and start polling `/latest-run` every 2 seconds while `activeRun.status` is `QUEUED` or `RUNNING`.
- While a profile run is `QUEUED` or `RUNNING`, also refetch `/latest-profile-run` every polling tick only after `/latest-run` reports a terminal profile run, so Save Profile does not point at an older completed profile while a newer profile is finishing.
- On any run terminal state from polling or SSE hint, call `refreshRuns("run-terminal")`, which fetches both endpoints in parallel.
- On window focus after 30 seconds stale and on explicit Refresh, fetch both endpoints in parallel.
- On successful profile save, call `refreshRuns("profile-saved")` so the UI keeps the saved profile target and latest run labels consistent.
- SSE session/run stream events are hints only; they trigger the same refresh functions, but `/latest-run` and `/latest-profile-run` remain authoritative.

Discovery workspace Save Profile UI:

- Save Profile button is enabled only when `latestProfileRun?.status === "COMPLETED"` and `isSavingProfile` is false.
- Button label is `Save profile from <runId>` or equivalent compact timestamp/run label from `latestProfileRun`.
- If `activeRun` is a completed preview and `latestProfileRun` exists, show helper text that Save uses the latest completed profile run, not the preview.
- If no latest completed profile run exists, disable Save and show `Run profiling before saving a profile`.
- If a newer profile run is currently running, disable Save with `Profiling in progress` until `/latest-profile-run` catches up after terminal state.
- Stale save errors with `EBCDIC_PROFILE_RUN_STALE` update `latestProfileRun` from `details.latestProfileRunId` by refetching `/latest-profile-run`, then show a non-destructive retry affordance.

Tests:

- No profile run disables Save and backend returns `EBCDIC_PROFILE_RUN_REQUIRED`.
- Completed preview run cannot be saved.
- Older completed profile run returns stale 409 with latest id.
- Latest completed profile run saves successfully.

## Phase 2: Refined App Skin

Polish current skin without semantic changes:

- Sidebar/topbar rhythm.
- Dashboard hierarchy.
- Producers/SOR pages.
- Pipeline classic page.
- Chat drawer and `/chat`.
- Git/deploy panels.
- EBCDIC in app chrome.

Style: quiet dense enterprise app, no nested cards, no decorative gradient/orb hero, stable dimensions, explicit empty/error/loading states.

Per-surface UI contracts:

| Surface | Layout contract | Required states | Acceptance |
| --- | --- | --- | --- |
| Dashboard/home | Full app shell with left nav, compact topbar, two-column responsive content: primary work queue/list left, operational summary right. No hero. | loading tenant, empty tenant, API error, partial cards failed. | At 1280px and 390px, primary next actions are visible without horizontal scroll; cards are not nested. |
| Pipelines list | Dense table/list hybrid with filters, status chips, domain label, latest/active display labels only. | empty list, filtered empty, loading, row action disabled. | Active/latest labels never look like mutation authority; Open IDE CTA exists per row when a version exists. |
| Producers/SOR | Master list plus detail sections for connectors/datasets; dataset and connector dialogs use shared form rhythm. | no connectors, no datasets, connector error, dataset creation validation. | Dataset rows fit mobile by stacking metadata; primary actions remain icon+label buttons. |
| Classic pipeline | AppChrome route with header, version selector, aggregate summary, composition/story/chat/code/git/deploy panels using selected version where needed. | no versions, invalid rev, selected off-page version, disabled version panels. | Classic keeps current mental model but uses consistent panels/toolbars; no IDE-only controls appear except Open IDE CTA. |
| Global chat drawer and `/chat` | Drawer is 420-520px responsive side panel in AppChrome; `/chat` is full-page AssistantShell with same message/composer primitives. | empty session, streaming, tool error, reconnect, reset. | Only one global chat surface mounted; message width and composer fit mobile. |
| Git/deploy panels | Compact operational panels with status header, action toolbar, history list, and detail drawer/inline rows. | repo missing, branch fetch failed, no PRs, deploy target missing, approval pending. | Destructive/release actions require explicit confirmation; provider sync failures are visible and retryable. |
| EBCDIC discovery | AppChrome page with session header, upload/run controls, preview/profile tabs, message/activity rail. | invalid pipeline scope, no session, upload error, running preview/profile, no latest profile. | Save Profile state always reflects `latestProfileRun`; active preview never masquerades as savable profile. |
| Runtime settings | Settings section with read-only active persona badge, primary runtime bindings by environment, diagnostic legacy rows, validation evidence, and repair actions. | no primary binding, diagnostic-only rows, persona mismatch, validation failed, local proof unavailable. | Only primary persona-legal bindings can feed deploy eligibility; diagnostic rows are never styled as deployable targets. |
| Storage/table previews | Compact evidence panels for landing contracts, table contracts, roots, catalog/database/dataset names, partition/layout spec, and ops-root artifacts. | missing contract, stale contract, persona unsupported, legacy path-only preview. | Previews cite contract/projection ids and never rebuild paths from raw params in UI code. |

Phase 2 implementation owners and state contracts:

- Dashboard/home owner: `frontend/src/app/(app)/page.tsx` after route-group migration, extracted presentational pieces under `frontend/src/components/dashboard/`. Pipeline work queue/list data uses canonical `GET /api/v1/tenants/{tenantId}/pipelines/summary`; non-pipeline summary cards may keep existing APIs. Phase 2 may reshape layout, loading, empty, and partial-error states but must not add another pipeline-summary backend dependency.
- Dashboard state: `{isLoadingTenant,isLoadingPipelines,isLoadingSorSummary,error,workQueueItems,summaryCards}`. Partial failures render the affected section error inline while the rest of the page remains usable.
- Pipelines list owner: `frontend/src/app/(app)/pipelines/page.tsx` plus reusable `PipelineList`/`PipelineFilters` components if extracted. It consumes canonical `GET /api/v1/tenants/{tenantId}/pipelines/summary` for row data, filters, `hasVersions`, `latestVersionId`, and `versionCount`; it never uses latest/active as mutation authority.
- Producers list owner: `frontend/src/app/(app)/producers/page.tsx`. State is `{query,domainFilter,statusFilter,isLoading,error,items}` and route search params may preserve filters. Empty and filtered-empty states are distinct.
- Producer/SOR detail owner: `frontend/src/app/(app)/producers/[sorId]/page.tsx`. It keeps connector, dataset, and SOR metadata loading/error state isolated so one failed section does not blank the whole detail page. Existing dialogs `create-sor-dialog.tsx` and `define-dataset-dialog.tsx` keep their API contracts; Phase 2 only standardizes layout, form rhythm, validation display, and mobile stacking.
- Settings owner: `frontend/src/app/(app)/settings/page.tsx` plus the canonical pipeline Git setup route `frontend/src/app/(app)/settings/pipelines/[pipelineId]/git/page.tsx`. Settings state is grouped by section: profile/session, tenant, runtime bindings, diagnostic target rows, Git, and deployment targets. Section-level save buttons are disabled only by their own dirty/valid/loading state.
- Runtime settings owner: existing runtime frontend surfaces under `frontend/src/components/runtime/`, including `runtime-persona-badge.tsx` and `runtime-bindings-panel.tsx`, remain the canonical UI primitives for persona and binding evidence. Settings must not recreate a separate runtime-target editor with divergent eligibility logic.
- Route/data cutover: each moved route must leave exactly one public route implementation after route groups are introduced. The old ungrouped file is deleted or reduced to a redirect only in the same change. No page may fetch the same resource both in the route file and in a nested panel after extraction.
- Phase 2 test scope: screenshot or Playwright coverage for dashboard, producers list, producer detail, settings, classic pipeline, `/chat`, and EBCDIC at desktop/mobile; component tests for section-level error/empty states where screenshots cannot force the API condition cheaply.

Visual acceptance gates:

- Desktop screenshots at 1440x900 and constrained 390x844 for dashboard, producers, classic pipeline, `/chat`, Git/deploy, and EBCDIC.
- No overlapping text, no nested cards, no viewport-scaled typography, no decorative gradient/orb backgrounds.
- Keyboard focus visible on all toolbar/dialog controls.

## Phase 3: Developer/IDE Workspace

IDE URL owns:

- `rev`
- `tab`
- `side`
- `bottom`
- `activity`
- hash anchors

Defaults:

- `tab=composition`
- `activity=composition`
- `side=assistant`
- `bottom=problems`

URL is source of truth. Local storage may remember only layout dimensions/collapsed rail state.

IDE assistant and IDE navigation emission enable only after:

- Route/state tests pass.
- `PipelineVersionAuthorityGate` passes.
- Active/latest fallbacks are removed/quarantined from pipeline-affecting chat/story/tool paths.

Developer/IDE skin layout contract:

- `WorkspaceShell` is full-viewport and does not mount `AppChrome`.
- Left activity rail width: 48px collapsed icon rail plus optional 240-320px explorer/history rail when `activity=explorer|history`.
- Center workbench hosts the active `tab` with a stable toolbar row, content region, and optional tab-local inspector hooks.
- Right side panel width default 420px, min 320px, max 560px, persisted per pipeline; `side=none` gives space back to workbench.
- Bottom panel height default 280px, min 180px, max 50vh, persisted per pipeline; `bottom=none` hides it.
- Top workspace bar shows breadcrumb, pipeline name, selected version label, version selector, command button, and route health/status chips.

IDE surface acceptance:

| IDE surface | Required behavior |
| --- | --- |
| Composition | Graph fills workbench, stable minimap/zoom controls, selected node opens inspector, invalid wiring appears in problems. |
| Code | Artifact tree and editor share one workbench; stale/dirty/read-only/conflict states are visible in editor header and problems. |
| dbt | Asset registry view uses same artifact/list density and explicit version scope. |
| Orchestration | Sensing/schedule/policy controls use explicit selected version and show generated orchestration evidence. |
| Story | Story pane is readable in workbench, copy/export controls stay in toolbar, version story uses selected version only. |
| Deployability | Readiness issues, packages, Airflow target eligibility, runtime projection evidence, stable DAG identity, approvals, and deploy history are scannable without leaving IDE. |
| Git | Effective repo, branch, PR history, provider sync state, and attach dialog are reachable from one tab. |
| Assistant side | Version-scoped assistant always shows selected version badge and refuses mutation when version authority is absent. |
| Bottom panels | Problems/runs/diffs/events/audit preserve state per `{tenantId}:{pipelineId}:{versionId}` and never block the active tab from rendering. |

### PipelineRouteStateController

File:

- `frontend/src/components/pipeline/pipeline-route-state-controller.tsx`

Inputs:

- `tenantId`
- `pipelineId`
- URL search params and hash
- authenticated user context where needed for access handling

Responsibilities:

- Fetch and validate pipeline aggregate detail through `GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}`.
- Fetch version list.
- Validate `rev` through the direct version endpoint.
- Perform one-shot canonical route replacement.
- Produce pipeline aggregate state for headers, breadcrumbs, classic aggregate panels, IDE title bars, and route metadata.
- Produce selected version state for both classic and IDE routes.
- Own loading, access-denied, not-found, stale-link, invalid-rev, and no-version states.

Exposed state:

```ts
type PipelineRouteState = {
  tenantId: string;
  pipelineId: string;
  pipeline: PipelineDTO | null;
  view: "classic" | "ide";
  selectedVersionId: string | null;
  selectedVersion: PipelineVersionDTO | null;
  versions: PipelineVersionDTO[];
  pinnedSelectedVersion?: PipelineVersionDTO;
  versionsNextCursor: string | null;
  hasMoreVersions: boolean;
  latestVersionId: string | null;
  activeVersionId: string | null;
  isLoadingPipeline: boolean;
  isLoadingVersions: boolean;
  isLoadingMoreVersions: boolean;
  routeReady: boolean;
  canonicalizationInProgress: boolean;
  error:
    | null
    | { code: "PIPELINE_NOT_FOUND" }
    | { code: "PIPELINE_FORBIDDEN" }
    | { code: "VERSION_FORBIDDEN"; versionId: string }
    | { code: "VERSION_NOT_FOUND"; versionId: string };
  noVersion: boolean;
  loadMoreVersions: () => Promise<void>;
};
```

Consumption:

- Classic page consumes `pipeline` for header, breadcrumb, aggregate status, domain display, and aggregate story entry points.
- Classic page consumes selected-version state for version selector and versioned panels.
- IDE workspace consumes `pipeline` for workspace title, breadcrumbs, route metadata, command scope, and non-version aggregate labels.
- IDE workspace consumes selected-version state for all version-scoped panes.
- Mutating components receive `selectedVersionId` only from this controller.
- Components must not fetch active/latest on their own for mutation authority.
- Components must not independently fetch `PipelineDTO` for the same route unless they are a child detail view with a separately documented refresh contract.
- `loadMoreVersions` fetches the next version page using `versionsNextCursor`, appends to `versions`, de-duplicates `pinnedSelectedVersion`, and sets `hasMoreVersions=false` when the next cursor is null.
- Version selector disables load-more while `isLoadingMoreVersions` or `canonicalizationInProgress`.

Outputs:

- Loading skeleton when pipeline or versions are loading.
- Access denied page on 403.
- Pipeline not found page on pipeline 404.
- No-version empty state when no version exists.
- Disabled versioned controls while `canonicalizationInProgress` is true.

### PipelineVersionAuthorityGate

Gate location:

- Implement as a testable verification utility/checklist in the Phase 3 enablement path, with tests or CI scripts that can fail the build before IDE assistant/navigation are enabled.

The gate passes only when:

- `ChatTurnContext.versionId` is populated for every PIPELINE_VERSION send.
- `ChatToolExecutor` uses context `versionId` or returns `CHAT_VERSION_REQUIRED`; it does not use `pipeline.getActiveVersionId()` or latest fallback in pipeline-affecting tools.
- `ChatService` prompt builders, facts, targeted generation summary, pipeline summary, and tool execution context do not read versioned state from active/latest.
- `frontend/src/contexts/chat-context.tsx` no longer owns implicit pipeline steering for versioned work; any remaining `setPipelineId` usage is legacy PIPELINE aggregate only and cannot affect PIPELINE_VERSION sends.
- `frontend/src/contexts/ebcdic-discovery-context.tsx` restores by explicit route `scope` and `pipelineId`; it never infers pipeline/version from global app state or active/latest.
- `PhaseDetector` receives explicit context and does not infer version from active/latest.
- `StoryGenerationService.generateVersionStory` requires explicit version and has no active/latest fallback.
- `UserStoryDialog` version mode calls only the version endpoint.
- Codegen generate, run listing, artifact read, artifact save, and apply-to-latest paths require explicit `versionId`/`expectedVersionId` and do not use active/latest for mutation authority.
- Deploy/package build, deploy, approval, and deployability mutation paths require explicit `versionId` and do not use active/latest for mutation authority.
- Workspace events use explicit scope/pipeline/version from the initiating context and reject mismatches.
- EBCDIC remains GLOBAL or PIPELINE only in this plan and never infers a pipeline version from active/latest.
- IDE navigation events target `/workspace/pipelines/{pipelineId}?rev=<versionId>`.
- Deploy/package/codegen surfaces read runtime persona, runtime binding, table contract, orchestration namespace, and runtime projection authority through canonical services; no IDE panel reconstitutes physical paths, deploy targets, DAG IDs, or connection IDs from raw params.
- Deploy actions submit only Airflow artifact publication/activation or Airflow DAG-run smoke checks. Any direct Spark, Dataproc, dbt, or GX deploy-time submission path fails the gate.
- Promoted generated artifacts for `AdvanceTimeDimension` and callback helpers are statically free of `PULSE_API_URL`, `PULSE_PG_*`, Pulse API callbacks, and Pulse service dependencies except for explicitly blocked local/dev strategies.

Phase 5 extension to this gate:

- Before command search is enabled, `CommandSearchService` must satisfy the same explicit-version authority rule: it never infers versioned command authority from active/latest, and PIPELINE scope may only return disabled version suggestions or IDE canonicalizable route inputs.

Search gate:

```bash
rg -n "getActiveVersionId\\(|findFirstByPipelineIdOrderByCreatedAtDesc|latestVersion|activeVersion" \
  backend/src/main/java/com/pulse/chat \
  backend/src/main/java/com/pulse/pipeline/service/StoryGenerationService.java \
  backend/src/main/java/com/pulse/pipeline/controller/PipelineController.java \
  backend/src/main/java/com/pulse/codegen \
  backend/src/main/java/com/pulse/deploy \
  backend/src/main/java/com/pulse/command \
  backend/src/main/java/com/pulse/workspace \
  backend/src/main/java/com/pulse/cobol \
  frontend/src/components/pipeline \
  frontend/src/contexts/chat-context.tsx \
  frontend/src/contexts/ebcdic-discovery-context.tsx \
  frontend/src/app
```

Allowed hits must be display-only labels, route latest resolution/canonicalization, freshness comparison, EBCDIC latest-session/latest-run restore, or legacy aggregate story summaries explicitly marked non-mutating and non-versioned. Any mutation, tool execution, artifact save, deploy/package action, story generation, or versioned command using active/latest fails the gate.

Until the gate passes:

- `WorkspaceAssistantPanel` is disabled.
- IDE route emission from tools is disabled.
- Versioned chat tools return `CHAT_VERSION_REQUIRED`.

## Phase 4: Codegen Workspace

Land run APIs, polling freshness, guarded saves, stale conflict UX, and apply-to-latest.

## Phase 5: Commands, Workspace Events, EBCDIC Polish

Land command search, events/audit panel, and EBCDIC workspace treatment.

### Command Search

Primary owners:

- Existing page after route-group migration: `frontend/src/app/(app)/commands/page.tsx`.
- Global shortcut owner: `frontend/src/components/shell/shortcut-provider.tsx`.
- Command palette surface: new `frontend/src/components/commands/command-palette.tsx`.
- Backend search controller: new `backend/src/main/java/com/pulse/command/controller/CommandSearchController.java`.
- Backend search service: new `backend/src/main/java/com/pulse/command/service/CommandSearchService.java`.
- Existing audit/log page remains backed by `backend/src/main/java/com/pulse/command/controller/CommandLogController.java`.
- Workspace integration: `frontend/src/app/(workspace)/workspace/pipelines/[pipelineId]/page.tsx` supplies current `pipelineId`, `selectedVersionId`, and IDE tab context.

Trigger surface:

- Global shortcut: Cmd/Ctrl+K opens command search.
- IDE activity rail and top-level shell may expose a command button using the same command search surface.
- Shortcut ownership lives in `ShortcutProvider`.
- When a modal or text editor owns Cmd/Ctrl+K, that local control may stop propagation; otherwise global command search opens.

Endpoint:

```http
GET /api/v1/tenants/{tenantId}/commands/search?q=&scope=&pipelineId=&versionId=&tab=&side=&bottom=&activity=&selectedNodeId=&selectedArtifactId=&selectedRunId=&selectedRunKind=&limit=
```

Rules:

- `limit` default 20, max 50.
- `q` empty returns top contextual commands for the supplied scope.
- Search is read-only. It never performs mutations.
- Command execution is client-side routing/opening or calls existing explicit APIs after user confirmation.
- `tab`, `side`, `bottom`, and `activity` are optional context values from the current IDE route and must be validated against the canonical allowed values.
- `selectedNodeId`, `selectedArtifactId`, `selectedRunId`, and `selectedRunKind=codegen|deployment` are optional opaque client-selected values used only for contextual command inclusion. Server validates tenant/pipeline/version relationship before returning any command that acts on them.

Scope validation:

- GLOBAL forbids `pipelineId` and `versionId`.
- PIPELINE requires `pipelineId` and forbids `versionId`.
- PIPELINE_VERSION requires `pipelineId` and `versionId`.
- PIPELINE_VERSION command results are unavailable when no `selectedVersionId` exists.
- Path `tenantId` must match pipeline/version tenant when pipelineId or versionId is supplied, or return `403 COMMAND_TENANT_MISMATCH`.

Classic scope rule:

- Classic pipeline route without valid `selectedVersionId` requests command search as `scope=PIPELINE`.
- Classic pipeline route with valid `selectedVersionId` may request command search as `scope=PIPELINE_VERSION`, passing `pipelineId` and `versionId`, so versioned story/Git/deploy/DQ commands match IDE authority and event scope.
- Classic PIPELINE_VERSION command execution preserves classic route shape when a classic-specific target exists; otherwise it may navigate to the IDE canonical route for versioned workspace commands.
- Invalid, forbidden, canonicalizing, or no-version classic states must use `scope=PIPELINE` and return versioned commands disabled.
- Classic-specific executable command search targets in Phase 5 are limited to opening the classic aggregate route and opening the classic route pinned to a selected version. Story, Git, deployability, runs, events, audit, code, dbt, orchestration, and DQ/readiness commands all navigate to the IDE canonical route because classic panels do not have stable panel URL state.
- Classic page-local buttons may still open classic Git/deploy/DQ/story surfaces when a selected version exists, but those page-local interactions are not command-search command targets until classic panel URL parameters are introduced.

Sources:

- Static shell commands: open global chat, open `/chat`, open settings, switch tenant when available.
- Route commands: open pipelines, producers, commands, EBCDIC discovery.
- Pipeline commands: open classic pipeline, open IDE, and discover Git/deploy entry points with disabled reasons when a version-specific route is required.
- Version commands: open composition, code, dbt assets, orchestration, story, deployability, runs, events, audit.
- Contextual commands from current workspace state, including selected node/artifact/run when available.

Canonical command targets:

| Command class | Required scope | Action | Canonical target |
| --- | --- | --- | --- |
| Open pipelines | GLOBAL | `NAVIGATE` | `/pipelines` |
| Open producers | GLOBAL | `NAVIGATE` | `/producers` |
| Open commands | GLOBAL | `NAVIGATE` | `/commands` |
| Open global chat drawer | GLOBAL | `OPEN_GLOBAL_ASSISTANT` | `target: "globalAssistant", params: { "focus": "composer" }` |
| Open chat page | GLOBAL | `NAVIGATE` | `/chat` |
| Open settings | GLOBAL | `NAVIGATE` | `/settings` |
| Switch tenant | GLOBAL | `OPEN_DIALOG` | `target: "switchTenant", params: {}`; disabled with `Only one tenant available` when tenant list has fewer than two items |
| Open EBCDIC discovery | GLOBAL | `NAVIGATE` | `/ebcdic-discovery` |
| Open EBCDIC discovery for pipeline | PIPELINE | `NAVIGATE` | `/ebcdic-discovery?scope=PIPELINE&pipelineId=<pipelineId>` |
| Open classic pipeline | PIPELINE | `NAVIGATE` | `/pipelines/<pipelineId>?view=classic` |
| Open Pipeline IDE | PIPELINE | `NAVIGATE` | `/workspace/pipelines/<pipelineId>` canonicalizable input |
| Open classic Git panel | PIPELINE | disabled result | `action: null`, `disabledReason: "Select a version first or open the IDE"` |
| Open classic deploy panel | PIPELINE | disabled result | `action: null`, `disabledReason: "Select a version first or open the IDE"` |
| Open classic pipeline version | PIPELINE_VERSION | `NAVIGATE` | `/pipelines/<pipelineId>?view=classic&rev=<versionId>` |
| Open Pipeline IDE version | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>` canonicalizable input |
| Open IDE composition | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=composition&side=assistant&bottom=problems&activity=composition` |
| Open IDE code | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=code&side=assistant&bottom=problems&activity=code` |
| Open IDE dbt assets | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=dbt&side=assistant&bottom=problems&activity=code` |
| Open IDE orchestration | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=orchestration&side=assistant&bottom=problems&activity=composition` |
| Open IDE story | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=story&side=assistant&bottom=problems&activity=assistant` |
| Open IDE deployability | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy` |
| Open IDE DQ/readiness | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy` |
| Open IDE Git | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=git&side=assistant&bottom=problems&activity=deploy` |
| Connect pipeline Git repository | PIPELINE | `OPEN_DIALOG` | `target: "connectGit", params: { "scope": "PIPELINE", "pipelineId": "<pipelineId>" }`; opens versionless pipeline setup at `/settings/pipelines/<pipelineId>/git` |
| Connect Git repository | PIPELINE_VERSION | `OPEN_DIALOG` | `target: "connectGit", params: { "scope": "PIPELINE", "pipelineId": "<pipelineId>", "versionId": "<versionId>" }`; disabled when repo already exists unless user has repo edit permission |
| Open runs panel | PIPELINE_VERSION | `OPEN_PANEL` | `target: "runs", params: { "placement": "bottom" }` |
| Open events panel | PIPELINE_VERSION | `OPEN_PANEL` | `target: "events", params: { "placement": "bottom" }` |
| Open audit panel | PIPELINE_VERSION | `OPEN_PANEL` | `target: "audit", params: { "placement": "bottom" }` |
| Open history rail | PIPELINE_VERSION | `NAVIGATE` | `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=composition&side=assistant&bottom=problems&activity=history` |

Classic deploy remains available from the classic pipeline page UI when that route has a valid selected version. Command search does not imperatively open classic Git/deploy panels in PIPELINE scope; it returns disabled discovery rows until a version-scoped IDE route is available. IDE deployability uses `tab=deployability`; there is no separate IDE `tab=deploy` in this plan.

Ranking:

Server ranking:

1. Exact label prefix match.
2. Acronym/token match.
3. Current-scope commands.
4. Global navigation commands.

Client re-ranking:

- The server does not receive local recent-command history.
- The client may re-rank the returned list using local recent-command history stored in localStorage under `pulse.commandHistory:{tenantId}:{userId}`.
- Client re-ranking may only reorder commands already returned by the server. It must not invent commands.
- Disabled commands remain below enabled commands even if recently used.

Response:

```json
{
  "items": [
    {
      "id": "pipeline.openIde",
      "label": "Open Pipeline IDE",
      "description": "Open this pipeline in the developer workspace",
      "icon": "panel-left",
      "category": "Pipeline",
      "shortcut": null,
      "disabledReason": null,
      "action": {
        "type": "NAVIGATE",
        "target": "/workspace/pipelines/01JPIPELINE9EZ2TR6B6R8QZ",
        "params": {}
      }
    }
  ]
}
```

Disabled response example:

```json
{
  "items": [
    {
      "id": "version.openCode",
      "label": "Open Code",
      "description": "Select a version to open generated code",
      "icon": "code",
      "category": "Version",
      "shortcut": null,
      "disabledReason": "Select a version first",
      "action": null
    }
  ]
}
```

Response rules:

- Enabled commands include executable `action`.
- Disabled commands set `disabledReason` and `action: null`.
- The client must not execute a command with `disabledReason` or `action: null`.

Navigation action contract:

- For `NAVIGATE`, the server returns a fully resolved internal URL in `action.target`.
- `NAVIGATE` responses must not contain template braces such as `{pipelineId}` or `{versionId}`.
- For `NAVIGATE`, `action.params` is always `{}` in the first command-search release.
- The client does not interpolate navigation targets. If `action.target` contains `{` or `}`, the client rejects the command result, logs an invalid command action, and does not navigate.
- Command search may return canonical or canonicalizable internal app URLs. The route controller owns final canonical replacement for missing workspace defaults and missing `rev`.
- Non-navigation action types may use `params` for client-local identifiers.
- `OPEN_PANEL` is not a hidden imperative state change. In IDE context, the client converts it to a URL update:
  - `assistant` or `inspector` sets `side`.
  - `problems`, `runs`, `events`, `audit`, or `diffs` sets `bottom`.
  - The client preserves current `rev`, `tab`, and `activity` unless the command target table specifies a full `NAVIGATE` URL.
  - AppChrome-only `assistant` opens `GlobalChatDrawer` outside IDE routes.

Action types:

- `NAVIGATE`: client route navigation.
- `OPEN_GLOBAL_ASSISTANT`: opens the AppChrome global chat drawer, navigates/focuses `/chat` in AssistantShell, or is offered as a `NAVIGATE /chat` fallback from WorkspaceShell. It never opens the workspace assistant side panel.
- `OPEN_PANEL`: opens an existing workspace panel such as assistant, inspector, problems, runs, events, audit, or diffs. It never opens the global chat drawer.
- `OPEN_DIALOG`: opens an existing dialog.
- `CLIENT_COMMAND`: executes a client-only command such as focus composer or copy link.

Action schema:

```ts
type CommandAction = WorkspaceAction;

type CommandResult = {
  id: string;
  label: string;
  description: string;
  icon: string;
  category: string;
  shortcut: string | null;
  disabledReason: string | null;
  action: CommandAction | null;
};
```

Per-shell execution:

| Current shell | `OPEN_GLOBAL_ASSISTANT` | `OPEN_PANEL` |
| --- | --- | --- |
| AppChrome | Open `GlobalChatDrawer` through `AssistantUiProvider`. | Disabled; server should prefer `NAVIGATE` into IDE for runs/events/audit/diffs/problems or return disabled `Open a workspace first`. |
| AssistantShell (`/chat`) | Focus full-page composer/messages according to params. | Disabled with `Open a workspace first`. |
| WorkspaceShell | Return `NAVIGATE /chat` for global assistant commands unless the command is explicitly workspace assistant, which must be `OPEN_PANEL target:"assistant"`. | Execute as URL state update for side/bottom panels. |

- Command search must choose action type by target surface, not by label. “Open global chat drawer” is always `OPEN_GLOBAL_ASSISTANT`; “Open workspace assistant” is `OPEN_PANEL target:"assistant"` and requires PIPELINE_VERSION workspace context.
- If a non-workspace shell receives an `OPEN_PANEL` result for runs/events/audit/diffs/problems, `CommandActionHost` rejects it as invalid action and logs telemetry; the backend command result should instead be disabled or `NAVIGATE` to the canonical IDE URL.

Rules:

- Unknown action type or target is ignored with an invalid-command telemetry event.
- `OPEN_PANEL` requires a workspace context; otherwise return the command disabled with `Open a workspace first`. Global assistant commands must use `OPEN_GLOBAL_ASSISTANT`.
- `OPEN_DIALOG` targets that require pipeline or version context are returned disabled when that context is absent.
- `CLIENT_COMMAND` never calls backend mutation APIs directly.

Command execution host:

- Host owner: `frontend/src/components/commands/command-action-host.tsx`.
- Mounted by `AuthenticatedProviders` so it exists in AppChrome, AssistantShell, and WorkspaceShell routes.
- Dialog registry:

| Target | Component file | Work required | Props | Success payload | Success refresh |
| --- | --- | --- | --- | --- | --- |
| `newPipeline` | `frontend/src/components/pipeline/create-pipeline-dialog.tsx` | Extract hostable wrapper if current component is page-callback-only. | `{open,onOpenChange,domainId?,onSuccess}` | `{pipelineId}` | Refresh tenant pipeline/domain summaries; navigate if caller requested. |
| `newProducer` | `frontend/src/components/sor/create-sor-dialog.tsx` | Extract hostable wrapper if current component is page-callback-only. | `{open,onOpenChange,onSuccess}` | `{sorId}` | Refresh SOR list; optional navigate `/producers/<sorId>`. |
| `defineDataset` | `frontend/src/components/sor/define-dataset-dialog.tsx` | Extract hostable wrapper if current component is page-callback-only. | `{open,onOpenChange,sorId,onSuccess}` | `{datasetId,sorId}` | Refetch SOR detail and dataset list. |
| `connectGit` | `frontend/src/components/git/git-repo-attach-dialog.tsx` | New hostable dialog. | `{open,onOpenChange,tenantId,pipelineId,domainId?,versionId?,scope?,onSuccess}` | `{repoId,scope}` | Refetch effective repo, exact attachment, branches, PR list. |
| `deployPackage` | `frontend/src/components/pipeline/deploy-panel.tsx` | Extract deploy/package dialog primitive. | `{open,onOpenChange,pipelineId,versionId,packageId?,mode:"packageAndDeploy"|"deployExisting",onSuccess}` | `{packageId,deploymentId?}` | Refetch packages/deployments/runs/deployability. |
| `uploadCopybook` | `frontend/src/components/ebcdic-discovery/discovery-workspace.tsx` | Use EBCDIC pending upload consumer, not a standalone dialog. | `EbcdicUploadActionParams` | `{artifactId,sessionId}` | Refetch artifacts/messages/session activity. |
| `uploadDataFile` | `frontend/src/components/ebcdic-discovery/discovery-workspace.tsx` | Use EBCDIC pending upload consumer, not a standalone dialog. | `EbcdicUploadActionParams` | `{artifactId,sessionId}` | Refetch artifacts/messages/session activity. |
| `switchTenant` | `frontend/src/components/shell/tenant-switch-dialog.tsx` | New/extracted hostable dialog if absent. | `{open,onOpenChange,onSuccess}` | `{tenantId}` | Switch tenant context and clear route-scoped pending actions. |
| `dqRecommendations` | `frontend/src/components/pipeline/dq-recommendations.tsx` | Use existing file; expose hostable panel/dialog wrapper if needed. | `{open,onOpenChange,pipelineId,versionId,issueId?,onSuccess}` | `{appliedIssueId?,score?}` | Refetch DQ recommendations, deployability, problems. |
| `configureStep` | `frontend/src/components/pipeline/configure-transform-dialog.tsx` | Existing dialog becomes hostable. | `{open,onOpenChange,pipelineId,versionId,nodeId,onSuccess}` | `{nodeId}` | Refetch composition and problems. |

- If a dialog requires route context that is not mounted, the host first navigates to the canonical route encoded by params, then opens the dialog after route hydration. If no canonical route can be derived, the server returns the command disabled.

EBCDIC pending upload consumer:

```ts
type EbcdicUploadActionParams = {
  scope: "GLOBAL" | "PIPELINE";
  pipelineId?: string;
  sessionId?: string;
};
```

- GLOBAL upload params must omit `pipelineId`.
- PIPELINE upload params must include `pipelineId`.
- `sessionId` is optional in both scopes; when present it must belong to the resolved tenant/scope/pipeline.
- `versionId` is forbidden for EBCDIC upload actions in this plan.
- Owner: `frontend/src/contexts/ebcdic-discovery-context.tsx` exposes `consumePendingUpload(kind: "copybook"|"dataFile", params)`.
- `discovery-workspace.tsx` registers hidden file inputs for copybook and data file uploads and exposes `openCopybookPicker()` / `openDataFilePicker()` through context.
- Bootstrap order after route handoff: restore latest session for route scope; if none exists, create session; validate optional `sessionId` belongs to route tenant/scope; then open the requested file picker exactly once.
- If `sessionId` is supplied but invalid or wrong scope, show `EBCDIC_SESSION_NOT_FOUND` or `EBCDIC_SCOPE_INVALID` toast and do not open picker.
- Pending upload action expires after the same 30 second handoff lifetime and is cleared after picker open, picker cancel, upload success, upload failure, or route scope change.

Cross-route dialog handoff:

- Transport: `sessionStorage` key `pulse.pendingCommandAction:{tenantId}:{userId}` containing:

```ts
type PendingCommandAction = {
  id: `cmd_${string}`;
  action: WorkspaceAction;
  targetRoute: string;
  createdAt: string;
  correlationId: string;
  idempotencySeed: string;
  source: "command-search" | "readiness-action" | "chat-navigation" | "keyboard";
};
```

- `correlationId` is created once when the command action is accepted and is reused after navigation, dialog open, mutation submit, workspace event write, and success/error refresh.
- `idempotencySeed` is a stable value derived from `{id, action.type, action.target, canonical params}`. Dialogs derive concrete API idempotency keys from it plus operation-specific values, for example `git:attach-pipeline:{pipelineId}:{idempotencySeed}`, `deploy:{packageId}:{targetId}:{idempotencySeed}`, `dq:apply:{versionId}:{recommendationId}:{idempotencySeed}`, or `version:lifecycle:{versionId}:{targetStage}:{idempotencySeed}`.
- Pending action params must not contain a different `correlationId` or idempotency key than the pending envelope. If both exist and differ, the host rejects the action with invalid-command telemetry and clears it.
- Lifetime: pending action expires after 30 seconds or after one successful open, whichever happens first.
- Dedupe: if a pending action with the same `action.type`, `action.target`, and JSON-stringified `params` exists, replace its `createdAt` and do not enqueue a duplicate.
- Clearing: clear on successful dialog open, expiry, user cancel before route navigation, logout, tenant switch, or route hydration where required params are still unavailable.
- Route derivation:
  - `newPipeline`: `/pipelines` unless `domainId` is supplied, then `/domains`.
  - `newProducer`: `/producers`.
  - `defineDataset`: `/producers/<sorId>`; disabled without `sorId`.
  - `connectGit`: with `versionId`, route to `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=git&side=assistant&bottom=problems&activity=deploy`; without `versionId`, route to `/settings/pipelines/<pipelineId>/git` and open the hostable attach dialog in pipeline setup mode. Disabled without `pipelineId`; `domainId` may be fetched in pre-open behavior.
  - `deployPackage`: `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy`; disabled without `pipelineId` and `versionId`.
  - `uploadCopybook` and `uploadDataFile`: `/ebcdic-discovery` or `/ebcdic-discovery?scope=PIPELINE&pipelineId=<pipelineId>`.
  - `switchTenant`: current route.
  - `dqRecommendations`: `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=deployability&side=assistant&bottom=problems&activity=deploy`.
  - `configureStep`: `/workspace/pipelines/<pipelineId>?rev=<versionId>&tab=composition&side=inspector&bottom=problems&activity=composition#node:<nodeId>`.

Dialog-specific pre-open behavior:

- `connectGit`: if `domainId` is absent, `CommandActionHost` fetches `GET /api/v1/tenants/{tenantId}/pipelines/{pipelineId}` before opening. If fetch fails, show parsed error and clear pending action. DOMAIN scope is disabled when fetched `pipeline.domainId` is null. Versionless pipeline setup opens the same attach dialog from `/settings/pipelines/<pipelineId>/git`; it calls `POST|PUT /api/v1/tenants/{tenantId}/pipelines/{pipelineId}/git-repo/attachment` and refreshes the effective repo, but does not require or invent `rev`.
- `deployPackage`: if `packageId` is absent, open in `mode="packageAndDeploy"`; if present, open in `mode="deployExisting"` after package pre-open validation.

Deploy package dialog sequence:

- `mode="packageAndDeploy"` shows deployability summary and target selector, then calls `POST .../pipeline-versions/{versionId}/packages` first. When package creation succeeds, the dialog advances to deployment confirmation using the returned `packageId`.
- `mode="packageAndDeploy"` initially reads targets from `GET .../pipeline-versions/{versionId}/deployment-targets`; after package creation it refetches `GET .../packages/{packageId}/deployment-targets` and preserves the selected target only if it remains eligible.
- `mode="deployExisting"` fetches `GET /api/v1/tenants/{tenantId}/packages/{packageId}?expectedVersionId=<versionId>` and `GET .../packages/{packageId}/deployment-targets`, shows package summary and package-aware target selector, and skips package creation.
- If deploy-existing package read returns `DEPLOY_PACKAGE_NOT_FOUND` or `DEPLOY_VERSION_MISMATCH`, the dialog does not open, pending action is cleared, and toast is `Package is unavailable for this version`.
- Deployment submit in both modes calls canonical `POST /api/v1/tenants/{tenantId}/packages/{packageId}/deploy` with `expectedVersionId=versionId`, selected `targetId`, required `X-Correlation-Id` and `Idempotency-Key` headers, and server-derived actor identity. Any body `userId`, `correlationId`, or `idempotencyKey` values are compatibility hints and must follow the global actor/header rules.
- Before deployment can be submitted, user must choose an eligible `DeploymentTargetDTO` and confirm blockers/warnings shown from deployability. Ineligible targets are visible but disabled with `disabledReasonCode` plus derived label text.
- If package creation fails, remain on package step. If deployment fails, keep selected package and target visible for retry.

Contextual commands:

| Context | Command | Required params | Action |
| --- | --- | --- | --- |
| selected node | Configure selected step | `pipelineId`, `versionId`, `selectedNodeId` | `OPEN_DIALOG configureStep` with `nodeId=selectedNodeId`. |
| selected node | Focus selected node | `pipelineId`, `versionId`, `selectedNodeId` | `NAVIGATE /workspace/pipelines/<pipelineId>?rev=<versionId>&tab=composition...#node:<id>` |
| selected artifact | Open artifact | `pipelineId`, `versionId`, `selectedArtifactId` | `NAVIGATE ...tab=code#artifact:<id>` |
| selected artifact | Open artifact diff | `pipelineId`, `versionId`, `selectedArtifactId` | `OPEN_PANEL diffs` with `artifactId` |
| selected codegen run | Open codegen run | `pipelineId`, `versionId`, `selectedRunId` | `OPEN_PANEL runs` with `runKind=codegen` |
| selected deployment run | Open deployment run | `pipelineId`, `versionId`, `selectedRunId` | `OPEN_PANEL runs` with `runKind=deployment` |
| current version | Copy version link | `pipelineId`, `versionId` | `CLIENT_COMMAND copyCurrentLink` |
| current workspace | Focus assistant composer | `pipelineId`, `versionId` | `CLIENT_COMMAND focusComposer` |

Disabled commands:

- Returned with `disabledReason` when useful for discoverability.
- PIPELINE_VERSION scope cannot be requested without `versionId`; that request returns `400 COMMAND_VERSION_REQUIRED`.
- PIPELINE scope may include discoverable version-scoped commands only as disabled suggestions with `Select a version first`, and those commands must have no executable action until a version is selected.

Open IDE command:

- In PIPELINE scope, `pipeline.openIde` navigates to `/workspace/pipelines/{pipelineId}` without `rev`.
- This bare IDE target is an allowed canonicalizable input URL, not the final canonical URL.
- The IDE route controller then resolves latest version explicitly through the version list endpoint, adds default IDE query keys, replaces to the canonical URL, or shows no-version empty state.
- In PIPELINE_VERSION scope, `pipeline.openIde` may include `rev={versionId}`.
- The server resolves both targets before returning them: PIPELINE scope returns `/workspace/pipelines/<pipelineId>` and PIPELINE_VERSION scope returns `/workspace/pipelines/<pipelineId>?rev=<versionId>`.
- Command search must not infer active/latest itself.

Tests:

- Cmd/Ctrl+K opens command search outside modal/editor capture.
- GLOBAL results do not include version commands.
- PIPELINE results include open IDE but not version-mutating commands without versionId.
- PIPELINE_VERSION results include composition/code/story/deployability commands.
- Empty query returns contextual commands.
- Search never performs mutation directly.

## Phase 6: Final Hardening

Accessibility, performance, responsive QA, visual QA, and release gates.

## Minimal Migration Order

1. S0 uncached story branch/tests.
2. Land `PulseGuard`, the `AuthorizationPolicyService`/`ActorResolverService` capability adapter, `frontend/src/lib/capabilities.ts`, structured 403 tests, and the permission/capability matrix for existing permissions before exposing new mutation entry points.
3. Land `V113__rollout_control_plane.sql`, `pulse.features`, tenant feature endpoint, `FeatureFlagProvider`, typed frontend flag helpers, and conservative defaults for all rollout flags.
4. Land telemetry DTO/endpoint/helper backed by `V113__rollout_control_plane.sql` with redaction tests and no-op-safe failure behavior.
5. Land shared accessibility helpers for route focus, focus trap/return, live regions, and skip links.
6. Introduce `AuthenticatedProviders`, `AppChrome`, and auth-only `AuthGate` for standard app routes without changing visual behavior. Keep `ui.shell.groups=false` until smoke and accessibility checks pass.
7. Extract chat stream/controllers/presentation with dual-shape parser/error parser.
8. Move `/chat` into `AssistantShell` full-page mode using `GlobalChatController` and remove the drawer-opening placeholder behavior.
9. Add paginated version envelope endpoint without breaking legacy array endpoint.
10. Land `PipelineRouteStateController`, route canonicalization, direct version validation, telemetry, and pinned off-page selected-version UI contract. Keep `ui.pipeline.routeStateController=false` until stale-link and focus tests pass.
11. Cut classic versioned panels to consume `selectedVersionId` from `PipelineRouteStateController` or disable them.
12. Atomic latest/create filtering plus pre-split restore/reset race-safe compatibility.
13. Land scoped chat migration: schema columns, constraints, backfill, and repository methods. Use the first Flyway number assigned by the migration resolver in the Scoped Chat Migration section. Keep `chat.scopedSessions=false` and scoped emitters disabled.
14. Land workspace-events migration: entity, repository, service, write API, and read controller with no emitters enabled yet. Use the next Flyway number after scoped chat. Keep `events.workspace.read=false`.
15. Tenant stream/context endpoints and `ChatTurnContext` service/executor signatures.
16. Add PipelineChatPanel local header trigger/state.
17. Remove `setPipelineId` steering; AppChrome/global chat explicit global.
18. ChatMessageDTO shield and adapter reader migration.
19. Land `WorkspaceAction`, `CommandActionHost`, dialog registry, and cross-route pending-action handoff without command-search UI. Readiness actions may depend on this step.
20. ChatToolRegistry bridge/preflight and canonical blocked-tool SSE JSON.
21. Enable chat/story workspace-event emitters only after steps 13 and 14 exist; event writes must be best-effort only where explicitly documented or transactionally coupled where the owning mutation requires audit durability. Gate with `events.workspace.emitters`.
22. Version story endpoint and UserStoryDialog/page selectedVersionId/storyScope cutover.
23. Scoped chat endpoints and ChatProvider `statesByScopeKey`.
24. Introduce `WorkspaceShell` public IDE route at `/workspace/pipelines/{pipelineId}` and compatibility redirect from `/pipelines/{pipelineId}?view=ide`. Keep `ui.workspace.ideRoute=false` until route, permission, telemetry, and accessibility checks pass.
25. Full version story transcript sections, canonical tool writer cutover, fallback removal, and Phase 3 `PipelineVersionAuthorityGate`.
26. Enable IDE assistant/navigation in Phase 3.
27. Phase 4/5 codegen, deploy, Git, command-search, and EBCDIC emitters may land only after the scoped chat foundation from step 13, shared workspace-event foundation from step 14, action-host foundation from step 19, permission matrix from step 2, flags from step 3, telemetry from step 4, and accessibility helpers from step 5 are already present. Command search must also satisfy the Phase 5 extension of `PipelineVersionAuthorityGate` before `commands.palette` is enabled.

## Final Gates

- Frontend lint.
- Backend tests.
- Backend representative static deployability proof: `./gradlew test --tests 'com.pulse.deploy.controller.RepresentativeStaticDeployabilityProofIT'`.
- Migration tests.
- Contract tests for chat, story, codegen, workspace events, version selection, and EBCDIC.
- Permission/403 contract tests for every operation in the Permission And Capability Matrix.
- Feature-flag default, enable, disable, and rollback-path tests for every flag in the rollout table.
- Telemetry DTO validation/redaction tests and frontend emission tests for required high-risk events.
- Accessibility tests for route focus, command palette keyboard behavior, dialogs/drawers, live regions, graph fallback, and keyboard-only IDE navigation.
- E2E smoke for app shell, global chat, pipeline classic, pipeline IDE, version switch, version-scoped assistant, story, codegen save guard, deployability, SOR, EBCDIC.
- Visual QA desktop and constrained widths.
- Runtime persona/settings proof: UI tests show active persona as read-only, primary runtime bindings separately from diagnostic rows, and deploy pickers refusing diagnostic/persona-mismatched rows.
- Chat plan/apply proof: plan tools create only plan records, `apply_plan` is the only generic mutator, structured SSE/tool-result fields drive refresh behavior, and legacy direct-mutator aliases cannot write product state.
- Runtime package independence proof: generated higher-environment package/static searches contain no `PULSE_API_URL`, `PULSE_PG_*`, peer Pulse endpoint requirement, internal Pulse broker requirement, or Pulse API callback dependency.
- Airflow deploy-boundary proof: deploy adapters and UI flows deploy/sync/activate Airflow artifacts or trigger/poll Airflow DAG runs only; static audit confirms no deploy-time Spark, Dataproc, dbt, or GX submission path.
- Runtime projection/storage proof: deployability and preview UI consume runtime projection, table contracts, landing contracts, storage authority, and orchestration namespace services; tests fail on ad hoc raw-param/path reconstruction in migrated surfaces.
