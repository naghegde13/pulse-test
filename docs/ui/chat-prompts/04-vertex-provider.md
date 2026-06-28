# #3 §7 — LLM provider contract: Vertex + OpenRouter (switchable)

> Status: NEW fragment (2026-06-16). This **re-grounds** the §7 LLM-provider material that was previously
> a placeholder — the "**OpenRouter today, Vertex later** = config swap" GUESS at
> `SPEC-ui-composition.md:198-203` and the **"DEFERRED — needs Vertex merge"** notes at
> `SPEC-ui-composition.md:829, 836-839` (and the prompt-cache-marker GUESS at `:661-664`). The Vertex
> path is no longer pending design: it is **implemented on branch `cg-env-transition`** (not yet merged
> to `guided/acme-lending-human-rehearsal-2026-05-25`). Every contract below is grounded against that
> actual code with `[read] file:line`. Inventions are flagged `> GUESS:`.
>
> Provenance: all `[read]` paths are read via `git show cg-env-transition:<path>` (the branch is
> `remotes/origin/cg-env-transition`; HEAD `f852686` "Move PULSE toward Capgemini-backed Vertex runtime",
> `6c794e6` "Record CG DEV2 source repository access").
>
> **This fragment SUPERSEDES the "Vertex later is a config swap, not a code change" framing.** That framing
> was wrong: Vertex is a real provider implementation (a new endpoint URL shape, GCP OAuth bearer auth, and
> a thought-signature replay obligation), wired behind a config-selected seam. The *consumer* code is
> unchanged (the seam is provider-agnostic), but the seam itself (`LlmEndpointService`) IS new code.

---

## 0. Scope of this fragment

Four normative pieces, all already exercised by tests on `cg-env-transition`:

1. **The provider seam** — `LlmEndpointService`, config-selected by `pulse.llm.provider`, consumed identically
   by every LLM call-site (Chat, schema inference, story, DQ, COBOL).
2. **The Vertex (Gemini on CG DEV2) provider** — endpoint URL shape, GCP OAuth bearer auth, per-surface
   Gemini model defaults.
3. **Structured tool-call replay** — completed tool calls stay structured `function` calls across turns;
   they are NOT flattened to text.
4. **Vertex thought-signature preservation** — the opaque `extra_content.google.thought_signature` on each
   Gemini function-call must be captured and replayed unchanged on the follow-up turn.

And one standing constraint: **OpenRouter stays switchable** (it remains the default; Vertex is the CG DEV2 path).

---

## 1. The provider seam — one abstraction, config-selected (replaces the "config swap" GUESS)

- [read] The provider abstraction is `com.pulse.llm.LlmEndpointService` (a `@Service`), keyed off a **call-site
  enum** `com.pulse.llm.LlmSurface` = `{CHAT, CHAT_REASONING, STORY_GENERATION, SCHEMA_INFERENCE, DQ_READINESS,
  COBOL_DISCOVERY}` (`LlmSurface.java:3-10`). `LlmSurface` is the *use-case* axis (which call-site), NOT the
  provider axis — the provider is one scalar config value (§1.1).
- [read] The service exposes exactly four operations consumed by call-sites
  (`LlmEndpointService.java:80-127`):
  - `boolean isConfigured(LlmSurface)` — provider-aware readiness (`:80-85`).
  - `String model(LlmSurface)` — resolves the per-surface model for the active provider (`:87-106`).
  - `HttpURLConnection openChatCompletionsConnection(LlmSurface, String title)` — opens the POST with the
    right URL + auth headers for the active provider (`:108-123`).
  - `String provider()` / `boolean isVertexProvider()` — the active provider id (`:125-131`).
- [read] **The seam is OPTIONAL and back-compatible.** Every consumer injects it `@Autowired(required = false)`
  and, when the bean is configured for the surface, delegates to it; otherwise it falls back to the legacy
  `pulse.llm.*` fields. Verified at all five call-sites:
  - `ChatService.java:316-317` (`@Autowired(required=false) LlmEndpointService`), with the three delegating
    shims `isLlmConfigured` (`:345-351`), `modelFor` (`:352-358`), `openLlmConnection` (`:360-366`).
  - `SchemaInferenceService.java:37, 174-189`.
  - `StoryGenerationService.java:56, 305-319` (`LlmSurface.STORY_GENERATION`).
  - `DqReadinessService.java:56, 262-276` (`LlmSurface.DQ_READINESS`).
  - `CobolDiscoveryAssistantService.java:48, 575-582` (`LlmSurface.COBOL_DISCOVERY`; here the bean is a
    constructor dependency, not field-injected).

**Normative — the seam contract**

- WHEN any LLM call-site (`router`/`discovery`/`composer`/`planner`/`responder` stage, or schema/story/DQ/COBOL)
  needs a model name, an auth'd connection, or a configured-check, THE SYSTEM SHALL obtain it from
  `LlmEndpointService` keyed by the call-site's `LlmSurface`, NOT from provider-specific code in the call-site.
- WHEN `LlmEndpointService` is not configured for a given `LlmSurface`, THE SYSTEM SHALL fall back to the legacy
  `pulse.llm.*` OpenRouter path so no call-site regresses when the seam bean is absent
  (`ChatService.java:345-366`).
- THE SYSTEM SHALL NOT branch on the provider inside a call-site; provider divergence (URL, auth, model
  defaults, thought-signature handling) lives entirely behind `LlmEndpointService` and the stream parser
  (§3–§4).

### 1.1 Provider selection + config keys (replaces the "base-url + model swap" claim)

- [read] The active provider is `pulse.llm.provider` (default `openrouter`), normalized lowercase
  (`LlmEndpointService.java:40, 176-179`; `application.yml:88-91`). Vertex is selected by `vertex` **or**
  `gcp-vertex` (`LlmEndpointService.java:150-152`).
- [read] **OpenRouter keys** (unchanged, default path): `pulse.llm.api-key`, `pulse.llm.base-url`
  (default `https://openrouter.ai/api/v1`), `pulse.llm.model` (default `openai/gpt-5.2`),
  `pulse.llm.reasoning-model` (default `o4-mini`), `pulse.llm.story-model`, `pulse.schema-inference.model`
  (default `google/gemini-2.0-flash-001`), `pulse.dq-readiness.model`, `pulse.cobol-discovery.model`
  (default `anthropic/claude-opus-4.6`) (`LlmEndpointService.java:41-48`; `application.yml:92-96, 111-119`).
- [read] **Vertex keys** (new, under `pulse.llm.vertex.*`) (`LlmEndpointService.java:49-58`;
  `application.yml:97-110`):
  - `pulse.llm.vertex.project-id` — GCP project (CG DEV2: env `VERTEX_PROJECT_ID`).
  - `pulse.llm.vertex.location` — default `global`; drives the endpoint host (§2).
  - `pulse.llm.vertex.chat-model` / `reasoning-model` / `story-model` — default `google/gemini-3.1-pro-preview`.
  - `pulse.llm.vertex.schema-model` / `dq-model` — default `google/gemini-2.5-flash`.
  - `pulse.llm.vertex.cobol-model` — default `google/gemini-3.1-pro-preview`.
  - `pulse.llm.vertex.credentials-path` and `pulse.llm.vertex.impersonate-service-account` — auth-only,
    optional (§2.1). Both `[read]`-commented as **local/dev helpers; runtime prefers ADC / workload identity**
    (`application.yml:100-104`).

**Normative — config**

- THE SYSTEM SHALL select the provider from `pulse.llm.provider` ∈ `{openrouter, vertex, gcp-vertex}`,
  defaulting to `openrouter` when unset or blank.
- WHEN `provider = vertex|gcp-vertex`, THE SYSTEM SHALL resolve each `LlmSurface`'s model from the
  `pulse.llm.vertex.*-model` keys (`LlmEndpointService.java:88-96`); WHEN `provider = openrouter`, from the
  legacy `pulse.llm.*` / `pulse.schema-inference.*` / `pulse.dq-readiness.*` / `pulse.cobol-discovery.*` keys
  (`:98-105`).

> RESOLVED (operator 2026-06-16, ADR 0025 §2): the §7.1 per-stage model matrix assigns the **cheap tier** to
> **Router / Discovery / Configure / Provision / Responder**, and the **reasoning tier** (`pulse.llm.model` →
> `LlmSurface.CHAT`, Gemini Pro) to **Build/Composer and Planner**; a node MAY escalate to the reasoning tier on
> a flagged hard case. The cheap tier is a **NEW** surface `LlmSurface.CHAT_CHEAP` backed by a **new** config key
> `pulse.llm.cheap-model` (under Vertex: `pulse.llm.vertex.cheap-chat-model`, default Gemini Flash e.g.
> `gemini-2.5-flash`). It is **NOT** `LlmSurface.SCHEMA_INFERENCE` / `pulse.schema-inference.model` /
> `pulse.llm.vertex.schema-model`: ADR 0011 retired model-based schema inference (100% deterministic, zero-LLM),
> so those surfaces/keys are **dead** and must NOT be repurposed as the chat cheap tier. (Adding `CHAT_CHEAP` to
> the `LlmSurface` enum + the two config keys is a small build task.) The reasoning tier resolves under Vertex to
> `pulse.llm.vertex.chat-model` (Pro) via the `[read]` enum→model resolution (`LlmEndpointService.java:87-106`);
> Vertex maps it to **Gemini 3.1 Pro Preview** because **GPT-5.2 is not available through Vertex** (commit
> `f852686` constraint). Each LangGraph4j node invokes this seam through the thin node→`LlmEndpointService`
> Vertex adapter (ADR 0025 §4), not a model client inside the orchestration library.

---

## 2. The Vertex provider (Gemini on CG DEV2) — endpoint + model resolution

- [read] **Endpoint URL is OpenAI-compatible chat/completions on Vertex** (`LlmEndpointService.chatCompletionsUrl`,
  `:133-144`):
  - OpenRouter: `{base-url}/chat/completions`.
  - Vertex `global` location → host `aiplatform.googleapis.com`; a regional location → host
    `{location}-aiplatform.googleapis.com`. Full path:
    `https://{host}/v1/projects/{project-id}/locations/{location}/endpoints/openapi/chat/completions`.
  - Locked by unit tests: `vertexUsesGeminiDefaultsAndGlobalEndpoint` and
    `vertexUsesRegionalEndpointWhenLocationIsRegional` (`LlmEndpointServiceTest.java:32-77`).
- [read] **The request body is provider-uniform.** The same OpenAI-shaped payload (`model`, `messages`,
  `tools`, `tool_choice`, `stream`, `max_tokens`, `temperature`) posts to either provider — the live Vertex
  loop test sends exactly that shape (`VertexLlmConnectivityIT.java:29-35, 62-72`), and the Chat loop builds
  it once for all providers (`ChatService.java:649-662`). Vertex is reached as Gemini's **OpenAI-compatible**
  surface (`application.yml:89-90`).
- [read] `isConfigured(surface)` for Vertex requires a non-blank `project-id` AND a non-blank resolved model;
  for OpenRouter, a non-blank api-key AND model (`LlmEndpointService.java:80-85`).

**Normative — Vertex endpoint**

- WHEN `provider = vertex|gcp-vertex`, THE SYSTEM SHALL POST the OpenAI-shaped chat/completions body to the
  Vertex `endpoints/openapi/chat/completions` URL for the configured `project-id` and `location`, deriving the
  host from `location` (`global` → `aiplatform.googleapis.com`, else `{location}-aiplatform.googleapis.com`).
- THE SYSTEM SHALL treat Vertex as "configured" for a surface only when both `pulse.llm.vertex.project-id` and
  the surface's resolved model are non-blank, else fall back per §1.

### 2.1 Vertex auth — OAuth bearer, ADC-first, NO service-account JSON in the repo

- [read] Vertex auth is a **GCP OAuth access token** minted at call time and set as
  `Authorization: Bearer <token>` (`LlmEndpointService.java:112-113, 154-174`). OpenRouter instead sets the
  api-key bearer plus `HTTP-Referer`/`X-Title` (`:114-119`).
- [read] Credential resolution order (`vertexAccessToken`, `:154-174`):
  1. IF `pulse.llm.vertex.credentials-path` is set → load that key file (**dev/local only**,
     `application.yml:100-102`).
  2. ELSE → `GoogleCredentials.getApplicationDefault()` (**ADC / workload identity — the runtime path**).
  3. Scope to `cloud-platform`; IF `pulse.llm.vertex.impersonate-service-account` is set → wrap in
     `ImpersonatedCredentials` (source ADC impersonates the PULSE runtime identity); refresh if expired; return
     the token value.

> CONSTRAINT (operator directive, honored): **no GCP service-account JSON is referenced or required to be
> committed.** `credentials-path` is an env-injected dev-only override (`VERTEX_CREDENTIALS_PATH`,
> `application.yml:102`); the runtime contract is **ADC / workload identity**. The administrative/setup service
> account is explicitly NOT the runtime identity (commit `f852686`: "setup identity must remain separate from
> the deployed app runtime identity"; `application.yml:101` "Do not point this at the administrative setup
> service account").

**Normative — Vertex auth**

- WHEN minting a Vertex request, THE SYSTEM SHALL obtain a `cloud-platform`-scoped GCP OAuth token, preferring
  Application Default Credentials (workload identity) in runtime and using a key-file path only as a dev/local
  override, and SHALL set it as the `Authorization: Bearer` header.
- THE SYSTEM SHALL NOT require any service-account JSON to be committed to the repo; runtime auth is ADC /
  workload identity, with optional same-token impersonation of the PULSE runtime service account.

---

## 3. Structured tool-call replay (replaces the deferred "tool flattening" question)

- [read] A completed tool call is carried as a structured `function` call across turns, NOT flattened to prose.
  The outbound wire shape is `{id, type:"function", function:{name, arguments}, extra_content?}`
  (`ChatService.toOutboundToolCall`, `:810-818`).
- [read] **Persist→replay round-trip.** When the assistant emits tool calls, PULSE persists them on the
  `chat_messages` row as the internal wrapper `{"calls":[...]}`
  (`ChatService.java:675, 683`: `tcAssistantMsg.setToolCalls(Map.of("calls", tcList))`). On the next turn it
  rebuilds history by un-wrapping that to the bare `tool_calls:[...]` array the OpenAI/Vertex API expects
  (`unwrapToolCalls`, `:796-806`; rebuild loop `:589-647`).
- [read] **Pairing integrity is enforced** so neither provider 400s: every replayed `tool_calls[].id` is
  tracked in `knownToolCallIds`, and any orphan `tool` message whose `tool_call_id` is unknown is dropped
  rather than forwarded (`ChatService.java:580-640`; BUG-2026-05-27 guard).

**Normative — structured replay**

- WHEN a tool call completes and the conversation continues, THE SYSTEM SHALL replay it as a structured
  `{id, type:"function", function:{name, arguments}}` entry in the assistant message's `tool_calls` array, and
  SHALL NOT flatten completed tool calls into plain context text (commit `f852686`: "Rejected: Flatten
  completed tool calls into plain text | would weaken structured tool-call replay and lose provider metadata
  such as thought signatures").
- THE SYSTEM SHALL replay a `tool` result message only when its `tool_call_id` matches a `tool_calls[].id`
  already emitted in the rebuilt history; otherwise it SHALL drop the orphan message
  (`ChatService.java:625-639`).

---

## 4. Vertex thought-signature preservation (the load-bearing new obligation)

- [read] Gemini 3.x function calls on Vertex's OpenAI-compatible endpoint attach an **opaque**
  `extra_content.google.thought_signature` (a `String`) to each function-call part. PULSE must capture it and
  **replay it unchanged** on the follow-up `tool_calls` entry — it is provider protocol metadata, **not prompt
  text**, and must not be flattened into a context message. Stated verbatim in the SSE contract test docstring
  (`ChatControllerSseContractTest.java:293-298`).
- [read] **Capture** — the stream parser reads `tool_calls[].delta.extra_content` (a Map), normalizes it, and
  accumulates it per tool-call index alongside id/name/arguments
  (`ChatService.streamLLM`, `:864, 907-911, 948`); it rides the `ToolCallAccumulator(id, name, arguments,
  extraContent)` record (`:807`).
- [read] **Emit/replay** — `toOutboundToolCall` re-attaches it as `extra_content` on the wire tool-call
  whenever non-empty (`ChatService.java:813-816`), and the persisted `{"calls":[...]}` carries it so it
  survives the persist→un-wrap→replay round-trip (§3).
- [read] **Exact shape (locked by tests):**
  - Live (real Vertex): `tool_calls[0].extra_content.google.thought_signature` is asserted present and a
    `String`, then the same `tool_calls` array is replayed on the follow-up request, which must still 2xx
    (`VertexLlmConnectivityIT.gemini31ProPreviewCompletesOpenAiCompatibleToolLoopWithThoughtSignature`,
    `:48-125`, esp. `:90-99, 101-124`).
  - Hermetic (SSE contract): with a fake server enqueuing a tool call carrying signature
    `"opaque-vertex-thought-signature"`, the **second** outbound request's assistant message must carry
    `tool_calls[0].type == "function"`, an intact `function` payload, and
    `extra_content.google.thought_signature` **equal to the original, unchanged**
    (`ChatControllerSseContractTest.vertexThoughtSignature_isPreservedOnStructuredToolCallFollowUp`,
    `:299-345`).

**Normative — thought-signature**

- WHEN a Vertex/Gemini function-call part carries `extra_content.google.thought_signature`, THE SYSTEM SHALL
  capture that metadata, persist it with the assistant tool-call, and replay it **byte-for-byte unchanged** as
  `extra_content` on the structured `tool_calls` entry of the follow-up request after the tool executes.
- THE SYSTEM SHALL treat `extra_content` as opaque provider protocol metadata: it SHALL NOT parse, mutate,
  reorder, or render it as prompt/context text, and SHALL NOT drop it when un-wrapping persisted tool calls.
- THE SYSTEM SHALL preserve `extra_content` only when non-empty (omitting the field entirely otherwise, so
  OpenRouter turns never emit a stray `extra_content`) (`ChatService.java:813-816`).

> GUESS: this fragment names `extra_content.google.thought_signature` as Vertex-only. The capture/emit code
> (`ChatService.java:907-911, 813-816`) is provider-agnostic — it preserves whatever `extra_content` arrives.
> So the obligation is correctly stated as "preserve opaque `extra_content` unchanged"; "thought_signature" is
> the concrete Gemini instance, asserted only on the Vertex/Gemini paths (`:99`, `:343-345`). Under OpenRouter
> the field is simply absent and the same code omits it. No invention beyond labeling the Gemini case.

---

## 5. OpenRouter stays switchable (the standing constraint)

- [read] OpenRouter is the **default** provider and a fully retained path: `pulse.llm.provider` defaults to
  `openrouter` (`LlmEndpointService.java:40`, `application.yml:91`); the OpenRouter URL/auth/model branches are
  intact (`:114-119, 134-135, 98-105`); the legacy `pulse.llm.*` fallback fires whenever the seam is
  unconfigured (§1). Unit test `openRouterKeepsLegacyModelsBySurface` locks the legacy model-by-surface
  resolution (`LlmEndpointServiceTest.java:9-29`).
- The directive that the Vertex replay path is **test-gated**: it MUST NOT change without re-running the
  thought-signature SSE contract test and the opt-in Vertex live tool-loop test (commit `f852686`).
- [read] The live Vertex tests are **opt-in and isolated** behind `@Tag("live-vertex")` and a dedicated
  `vertexLiveTest` Gradle task; the standard lanes EXCLUDE that tag, so the default build never makes paid
  Vertex calls (`build.gradle.kts:199, 205-210, 221-226, 256-272, 332`;
  `VertexLlmConnectivityIT.java:20, 135-136` `assumeTrue(VERTEX_PROJECT_ID)`).

**Normative — switchability**

- THE SYSTEM SHALL keep OpenRouter as the default, fully-functional provider, switchable to Vertex purely by
  configuration (`pulse.llm.provider` + the `pulse.llm.vertex.*` keys) with no call-site code change; Vertex is
  the CG DEV2 path, OpenRouter the local/default path.
- THE SYSTEM SHALL NOT make paid Vertex calls in the default test lanes; live Vertex coverage SHALL remain
  opt-in (`@Tag("live-vertex")` / `vertexLiveTest`), skipped when `VERTEX_PROJECT_ID` is unset.
- The Vertex tool-call replay + thought-signature path SHALL NOT be modified without re-running
  `ChatControllerSseContractTest.vertexThoughtSignature_isPreservedOnStructuredToolCallFollowUp` and the opt-in
  `VertexLlmConnectivityIT` tool-loop test.

---

## 6. What this fragment closes in §7

| §7 placeholder (current) | Status after this fragment |
|---|---|
| `:198-203` GUESS — "OpenRouter today, Vertex later = config swap (base-url + model), not a code change" | **Corrected.** Vertex is a real provider impl (new URL shape + GCP OAuth + thought-signature replay) behind a config-selected seam; consumers are unchanged, but the seam (`LlmEndpointService`) is new code. |
| `:829, 836-839` "DEFERRED — needs Vertex merge" (provider plumbing half) | **Grounded.** The provider plumbing is `[read]` on `cg-env-transition`; the contract is pinned here. (The *operator-voice prompt-text* half of that DEFERRED note stays deferred — out of scope for this fragment.) |
| `:661-664` GUESS — prompt-cache marker "`cache_control: ephemeral` for Anthropic/Vertex vs OpenRouter equivalent" | **Partially.** The provider seam is now real, but the actual `cg-env-transition` code carries NO prompt-cache markers on either path (no `cache_control` in the request body — `ChatService.java:649-662`). > GUESS: prompt-caching remains unimplemented; that marker note stays a forward GUESS, NOT grounded by this merge. |

> Out of scope (still deferred): the per-tool/per-stage **prompt TEXT** and data-engineering voice (the other
> half of `SPEC-ui-composition.md:836-839` and §7.14). This fragment pins only the provider CONTRACT — the
> seam, the Vertex endpoint/auth, structured replay, and thought-signature preservation.
