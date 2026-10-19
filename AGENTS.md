# Agent notes — AndroidMorse

## Agent skills
This workspace runs Matt Pocock's AI-coding workflow (ask-matt).
- Start from `CONTEXT.md` (verified facts + glossary) and `docs/adr/` (decisions worth not re-litigating).
- Idea → ship flow: grilling → `to-spec` → `to-tickets` → one `implement` per ticket → `code-review` → push.
- Issue tracker: **not yet configured** — engineering-skill setup runs the first time a tracker-dependent
  skill (to-spec / to-tickets / triage) is used.

## Repo map
- `upstream/MorseRunnerCE/` — read-only reference: MRCE 1.85.4 source (MPL-2.0, Delphi/VCL). Never edit.
- `docs/` — ADRs and specs.
- `.scratch/` — session snapshots, batch state, run logs (not product code).

## Hard constraints learned during recon
- The original is Delphi/VCL and cannot be compiled here (no Delphi, no FPC/Lazarus).
  A port is a **rewrite**, not a build.
- `github.com` needs the local proxy; `git` is preconfigured for `127.0.0.1:7892`.
- Do not make credentials, callsigns, or personal data part of committed source.

## Session conventions
- **Autonomy**: the human delegated decisions on 2026-10-19 — "不需要我同意，你们爱怎么做怎么做",
  "如果有选择题，自动选最优解". Do not block on questions: pick the best option, write the
  reasoning into the ADR/commit/progress snapshot, and keep moving. Verification discipline is
  unchanged — delegation covers choices, not evidence.
- **Model**: new sessions should use `DeepSeek-V4.1-Flash` (the name with the decimal point);
  never switch the model of a session that is already open (operator instruction, 2026-10-19).
- **One Gradle at a time**: all sessions share this working tree and its `.gradle/` locks.
  Two concurrent builds wait on each other or fail with a lock timeout, so stagger them.
  Parallel *sessions* are fine only when their file scopes are disjoint (e.g. `core-expr` vs
  `core-audio`); commits stay separate. If Kotlin does start emitting bogus errors
  (`IC_FAILED_TO_CLOSE_CACHES`, then phantom `Unresolved reference`), rerun with
  `-Pkotlin.incremental=false` and delete the affected module's `build/` directory.
- **Evidence check**: before calling a ticket green, run `python tools/verify-tests.py`
  (`--strict` when skips would be surprising). It prints per-suite counts and fails on any
  failure/error. A skipped test is not evidence — this exists because three tests were silently
  skipped once and looked green.
