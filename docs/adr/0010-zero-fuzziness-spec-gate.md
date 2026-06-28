# No work starts until the spec has ZERO fuzziness

Status: accepted

## Context

PULSE's recurring failure mode — *"forever,"* per the operator — is **fuzzy specs
that lead to slop that must be constantly revisited.** This session reproduced it:
the orchestrator was willing to dispatch Builder work (G1/G2/S3) to agents on specs
that still had fuzzy boundaries and unsettled architecture (the deterministic-
skeleton/form question, the S1 contract, the schema-inference boundary). Only
because the operator stopped it did it not become another round of
built-something-the-operator-didn't-agree-with. **Agents — Codex, Droid, Claude —
faithfully implement whatever the spec says, so any fuzziness in the spec becomes
confident, wrong, throwaway code that *looks* done.**

## Decision

**No work starts — no agent is dispatched, no line of code is written — until the
spec has ZERO fuzziness.** A spec is "ready" only when ALL of these hold:

- **Every contract is concrete:** the exact inputs, the exact outputs / the
  deterministic oracle, the exact boundaries with every adjacent component, the
  exact data shapes.
- **No open questions, no unresolved `[verify]` items, no "we'll figure it out at
  implementation time," no "the agent will decide."**
- **The architecture the spec rests on is itself settled** (not "under revision").
- **The operator has agreed it.** The spec author is operator + orchestrator via
  grill-with-docs — never an agent's recon.

If *anything* is fuzzy, the move is to **grill it to concrete first — never "start
and refine."** A spec with any fuzziness is not a spec; it is a setup for slop.

**The standard, stated three ways — all must hold:** the spec is complete only if
**(1) an autonomous agent can CODE it** with zero guesswork; **(2) an autonomous
agent can VERIFY it** (the verification — including the deterministic oracle — is
itself fully specified); and **(3) behavioral tests can verify it across features**
(the cross-module scenario, ADR 0004). **Litmus test: if an agent has to *guess*
anything — the architecture, a contract, a boundary, a name, a data shape — the
spec is NOT complete.** Zero guessing.

This **raises the bar above ADR 0004** ("a deep spec an agent can implement"): the
standing bar is now **zero fuzziness, fully contracted, operator-agreed.**

## Considered

- **"Start with a good-enough spec and iterate"** (the agile instinct) — **rejected
  for agent-implemented work**: it produces confident wrong code that looks done,
  which *is* PULSE's chronic slop-and-revisit cycle. Iteration is fine *on a
  concrete spec*; it is not a substitute for one.

## Trade-off

More up-front grilling, so it feels slower to "start." Accepted because the
operator's repeated lived experience is that **fuzzy-fast is far slower** than
concrete-first (rework + eroded trust). This is operator-mandated and permanent.
