# ADR-0004 — Schema v1 and four language extension points, after the pressure test

- Status: **accepted by delegation, and settled by evidence** — the human delegated
  decision-making on 2026-10-19 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"),
  so the agent decides and records the reasoning here instead of waiting for review.
  T3's exit evidence now exists (`feat(T3)` commit): `:core-contest:test` → exit 0, 61 tests /
  0 failures, of which `PressureTestTest` runs the shipped CQ WW / ARRL SS / IARU HF configs
  through the evaluator and compares the answers with the Pascal; full `assembleDebug test`
  → exit 0. Spec §4 is therefore no longer provisional.
- Deciders: agent, under that delegation. An earlier draft recorded the human as a decider
  before they had seen the file; that was wrong and is corrected.
- Scope note: revising the schema during T3 is pre-authorised by spec §4:94-96 ("If a contest
  cannot be expressed, revise the schema now"). The four new built-ins and the two schema
  corrections below are recorded here for reviewability, per ADR-0003's "explicit, reviewable
  extension point" rule.

## Context
Spec §4 left schema v1 a draft with a blocking open item: prove it against the three
hardest contests *before* building the engine. `docs/contests/exchange-facts.md` extracted
CQ WW, ARRL SS and IARU HF from the Pascal with `file:line` and named three gaps in the v1
built-in set (table-neighbour lookup, seeded RNG, numeric test), plus two scoring demands
v1 did not cover: continent lookup and number→string formatting.

## Decision
1. **Schema v1 is proposed frozen** as written in spec §4 — fields, types, the evaluation
   environment table, and the load-time validation list. The three hardest contests load,
   validate and score; the remaining nine are expected to fit without another schema change.
2. **Numeric test — no language change.** IARU's `IsNum` (`IaruHf.pas:355-366`) is exactly
   `matches(s, "^[0-9]+$")`: empty string → `false`, any non-digit → `false`. Verified by
   test against the upstream truth table.
3. **Seeded RNG — no language change.** ADR-0003 requires deterministic, side-effect-free
   evaluation, so the dice are *inputs*, not a function: a config declares `their.dice`, the
   runtime draws them from a seeded PRNG and injects numbers in `[0, 1)`. Weighted choice
   and deliberate corruption then become ordinary expressions — and the set of random draws
   a contest makes is enumerable, loggable and replayable, which the Pascal's global
   `Random` is not.
4. **Table-neighbour lookup — two new built-ins.** `GetAlternateSection`
   (`ArrlSS.pas:507-517`) needs "the adjacent entry of a fixed table"; v1 had no list
   indexing at all. Added `indexOf(list, value)` (absent → `-1`) and `listAt(list, index)`
   (out of range → fails closed, `BAD_ARGUMENT`). The ARRL section table stays **config
   data** (`constants.arrlSections`), not a built-in: ADR-0002 says contests are data.
5. **Continent lookup — one new built-in.** CQ WW (`CqWW.pas:258-265`) and IARU
   (`IaruHf.pas:337-348`) both score on continent equality, which `callCountry` cannot
   answer. `CallFacts` gained `continent(call)` with a default implementation returning
   `null` (unknown → fail closed, `CALL_DATA_MISSING`), exposed as `callContinent(call)`.
6. **Formatting — one new built-in.** `ZN-<zone>` (`CqWW.pas:233`) needs integer→string; v1
   only had `num(str)`. Added `str(x)` (integral numbers print without a fractional part).
   ARRL SS's `format('%.02d %s')` zero padding then reads
   `ifElse(n < 10, '0' + str(n), str(n))` — no `printf` in the language.
7. **Static identifier checking.** `Expression.identifiers()` exposes the free identifiers
   of a compiled program, so the loader rejects a misspelled field name at load time
   against the §4 environment table instead of failing mid-QSO.
8. **Two schema corrections to the §4 draft.**
   - `scoring.multipliers` is a list of `{ "key": expr, "when"?: expr }` objects, so a QSO
     can contribute *no* multiplier (CQ WW `/MM`, `CqWW.pas:236-240`) without an
     empty-string sentinel.
   - `exchange.theirPattern` (a display template) became executable
     `exchange.their.exch1/exch2` generation rules. A template cannot express the
     leading-zero perturbation (`CqWW.pas:296-297`), and keeping both would be two sources
     of truth for one string.
9. **A shared data asset is referenced, never copied (T3 debt, decided in T5).**
   `constants.arrlSections` is `{ "asset": "arrlSections" }`: the loader resolves the
   name against `core-data` (`ArrlSections.all`) and injects the list into the
   expression environment, so the 85 entries exist in exactly one place. A literal list
   under a known asset name is a load-time error, and so is an unknown asset id — the
   second copy cannot come back silently. `core-contest` therefore depends on
   `core-data` (it already depends on `core-expr`).

## Consequences
+ The three hardest contests are data, not code. Every expression is compiled once at load
  time and unknown identifiers are rejected there, so a config typo cannot reach a QSO.
+ Determinism is preserved end to end: the same seed produces the same audio, which makes
  the audio and scoring suites reproducible.
− Every future contest may need another built-in. That is still the explicit, reviewable
  extension point ADR-0003 asked for — the count is now four (`str`, `indexOf`, `listAt`,
  `callContinent`), and each one is total and fail-closed.
− Config authors must respect the §4 environment table; the loader enforces it.
− **T3 debt resolved in T5 (2026-10-19):** decision 9 replaces the duplicate table with a
  reference. The alternative — keep the literal list and have the loader cross-check it
  against `core-data` — was rejected: it preserves two copies of the same 85 strings, and
  every ARRL-family contest added by T10–T20 would copy them again. Referencing the asset
  is also what spec §9 already says (`arrlSections` is a data asset). Guard:
  `DataAssetConstantTest` fails if the shipped config carries a literal table, and if the
  loaded constant diverges from `ArrlSections.all`.

## Rejected
- A contest-specific `arrlSectionNeighbour()` built-in: puts domain logic in the engine and
  does not generalise to the other nine contests' tables.
- Keeping the 85-section literal in `arrlss.json` and cross-checking it in the loader
  (the T3 debt's other option): two copies would still exist, and T10–T20 would multiply
  them per contest.
- A `random()` built-in: non-deterministic evaluation, and the draw would be untestable.
- `printf`-style formatting: `str` + `+` + `ifElse` covers both real uses; a format
  sub-language is a much larger surface for one zero-padding rule.
- Keeping `theirPattern` as a template *and* adding generators: two places to change.
