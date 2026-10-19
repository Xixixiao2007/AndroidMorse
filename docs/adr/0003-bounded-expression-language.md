# ADR-0003 — Bounded expression language for contest behaviour

- Status: accepted (human chose "B" on 2026-10-19)
- Deciders: human (BH4GZK), agent

## Context
Declarative fields alone cannot express what the 12 contests actually do: ARRL Sweepstakes
validates precedence + check + section jointly, IARU HF maps society abbreviations, CQ WPX
derives prefixes, HST changes the operating flow, serial-number ranges are generated.

## Decision
Implement a small, total expression language inside the app:
- **Expressions only** — no statements, no loops, no assignment, no I/O, no reflection.
- Deterministic and side-effect free; evaluation is step-bounded (evaluation budget).
- Used for: field validation, exchange formatting, scoring points/multipliers,
  serial generation, and conditional message selection.
- Grammar, built-in functions and error model are specified in `docs/spec/`.

## Consequences
+ Behaviour lives in the config, satisfying ADR-0002, without embedding a scripting runtime.
+ The evaluator is a pure module with exhaustive unit tests (fuzzable, no Android deps).
− Every new kind of contest behaviour may require a new built-in function — an explicit,
  reviewable extension point rather than silent drift.

## Rejected
- Full JSONLogic-style operator soup without typing: harder to validate at load time.
- Embedded Lua/JS: larger APK, unbounded evaluation, harder to test on the JVM.
