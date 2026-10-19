# ADR-0016 — HST and the Morse weight: the first score that is a *sum*, and a built-in that reads the keyer's own alphabet

Status: **accepted by delegation** (T16, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0003 (the bounded expression language), ADR-0004 (schema v1 and its
extension discipline), ADR-0005 (the QSO engine's seams), ADR-0007 (determinism), ADR-0015 (CQ WPX
and the running serial — the shape this one reuses).

T16 is spec §11's seventh "one ticket per remaining contest" row. HST is the last contest whose
exchange shape is already in place: T15 fixed F8 (the running serial), and `Main.pas:505` shows
HST reusing `TCqWpx` wholesale, so the exchange, the serial and the roster path are CQ WPX's.

What is *not* in place is the score. HST's score is **the Morse sending time of the callsign
worked**, accumulated per contact:

```pascal
function CallToScore(S: string): integer;          // Log.pas:586-597
begin
  S := Keyer.Encode(S);
  Result := -1;
  for i:=1 to Length(S) do
    case S[i] of
      '.': Inc(Result, 2);
      '-': Inc(Result, 4);
      ' ': Inc(Result, 2);
    end;
end;

procedure UpdateStatsHst;                          // Log.pas:600-620
begin
  for i:=0 to High(QsoList) do begin
    CallScore := CallToScore(QsoList[i].Call);
    Inc(RawScore, CallScore);
    if QsoList[i].Err = '   ' then Inc(Score, CallScore);   // no errors → verified
  end;
end;
```

That is a **per-contact sum**, not `points × multipliers`. The port's `QsoLog` only knows
`points × multiplierCount` (`QsoLog.kt:95`), and the expression language has no loop and no
dot/dash table, so a comment-only document cannot express it. **This is the ticket's decision.**

## 1. Why HST now, and what was left

The four criteria T10 established, applied to the three contests left after T15
(`docs/contests/exchange-facts.md`, T16's updated inventory):

| Criterion | HST | ARRL DX CW | ARRL Field Day |
|---|---|---|---|
| an upstream call history to package unmodified | **no** — `MASTER.DTA` is the pool only (`CqWpx.pas:87-97`) | yes | yes |
| schema v1 only, no new capability | **no** — its own score, but no new *field* type | **no** — `myCountry` is not in `exchange.sent.*` | **no** — the club-name logic is engine code |
| independent of F8 | **yes** — T15 shipped the serial | yes | yes |
| decidable behaviour checks | **yes** — every weight is arithmetic | yes | mostly |

HST needs **one engine extension** (a scoring built-in) rather than a new field type; the other two
need new identifiers and new upstream code paths. HST is the smallest remaining slice, and the one
whose arithmetic can be pinned character by character.

## 2. The decision: a `callWeight(call)` built-in behind a shared Morse module

1. **The score expression is `callWeight(theirCall)`.** A new built-in, named for the domain word
   (*weight*, the sending time of a call) rather than for the implementation. It returns a number:
   the exact integer `Log.pas:586-597` produces, in sent-element units.
2. **The Morse alphabet becomes a shared, dependency-free module, `:core-morse`.** It owns the
   character → dot/dash table and the weight function; `:core-audio` (which already had the table
   privately, `MorseTable.kt`) and `:core-expr` (which needs it for the built-in) both depend on
   it. Nothing else moves.
3. **No schema change.** `scoring.points` and `scoring.multipliers` stay as they are; the document
   says `"points": "callWeight(theirCall)"`, and one constant multiplier key turns the product into
   the sum (§4). Spec §5's built-in list gains one line; spec §4's environment table does not
   change at all, because `callWeight` is a *function of `theirCall`*, which scoring already has.

### Why a built-in and not a schema mode

The alternative was `scoring.kind: "sum"` (a scoring mode that adds a per-contact weight instead
of multiplying points by multipliers). It was rejected because:

- it adds a second *shape* to `scoring`, so `readScoring`, `ContestRules.score`, `QsoLog` and
  spec §4 would each grow a branch for **one contest**;
- it is less composable: a future contest wanting `callWeight(theirCall) / 10` or
  `callWeight(theirCall) + callPrefixBonus` would need another mode, while a built-in composes in
  the language that already runs points and multipliers;
- ADR-0003's extension rule is explicit: *"Every new kind of contest behaviour may require a new
  built-in function"* — and spec §5: *"A new built-in is an explicit extension point: spec edit +
  ADR-0003 conformance + tests."* This is exactly that case.

### Why `:core-morse` and not a copy in `:core-expr`

The table already existed in `:core-audio` (T4). Copying it into `:core-expr` would create two
alphabets that can drift — a scoring table and a sending table — which is the one bug this port
cannot afford to have. Putting it in `:core-expr` and having `:core-audio` depend on the language
module is the same mistake in the other direction; putting it in `:core-data` would make the *code*
of a built-in live in the *assets* module. A leaf module with two consumers is the shape that
leaves no edge pointing the wrong way:

```
core-morse  (dependency-free: alphabet + weight + their tests)
   ↑                    ↑
core-audio          core-expr
(encodes)           (callWeight built-in)
```

`MorseTable` is **moved**, not re-exported: `core-audio` had a private copy of the same 42
single-character rows, transcribed from `VCL/MorseTbl.pas`; the multi-character prosign rows stay
skipped for the reason its comment already gives (`MorseKey.pas:100-113` ignores any row whose
second character is not `[`).

## 3. The weight, transcribed exactly

`TKeyer.Encode` (`VCL/MorseKey.pas:158-170`) maps each character to its pattern **plus one trailing
space**, maps `' '`/`'_'` to a single space, and **replaces the last character of the result with
`'~'`** ("EOM has ~5U spacing"). `CallToScore` starts at `-1` and charges 2 per dot, 4 per dash and
2 per space; `~` charges nothing.

So for a call made of *n* known characters:

```
weight(call) = 2·dots + 4·dashes + 2·(n − 1) − 1
```

One trailing space per character, the last one turned into `~`, and upstream's `-1` baseline. The
closed form is not where the rule lives — `MorseWeight` transcribes the loop — but it is what the
tests check against. Worked examples, every dot and dash counted by hand from the table:

| Call | dots | dashes | n | weight |
|---|---|---|---|---|
| `K1ABC` | 8 | 10 | 5 | 2·8 + 4·10 + 2·4 − 1 = **63** |
| `W1AW` | 4 | 9 | 4 | 2·4 + 4·9 + 2·3 − 1 = **49** |
| `JA1ABC` | 9 | 12 | 6 | 2·9 + 4·12 + 2·5 − 1 = **75** |
| `DL/AA1ZZ` | 15 | 14 | 8 | 2·15 + 4·14 + 2·7 − 1 = **99** |
| `E` | 1 | 0 | 1 | 2 + 0 + 0 − 1 = **1** |
| `T` | 0 | 1 | 1 | 0 + 4 + 0 − 1 = **3** |
| `5NN` | 7 | 2 | 3 | 2·7 + 4·2 + 2·2 − 1 = **25** |

Two consequences worth naming:

- **Case is not folded.** `TKeyer.Encode` looks characters up in `Morse[ch]`, which only has
  upper-case keys, so a lower-case call would score `-1`. The port keeps that: the scoring input is
  a validated, upper-cased callsign (`QsoLog.normalise`, `ContestRules.normaliseCall`), and
  silently folding case would be a deviation from the function the score is defined by.
- **A call with no sendable character scores `-1`**, because upstream's baseline is `-1` and the
  loop never runs. That is upstream's arithmetic, not a port choice; it cannot be reached by a call
  the shared grammar accepts.

## 4. How the sum becomes `points × multipliers`

`QsoLog` has one aggregation rule — `rawScore = rawPoints × rawMultiplierCount`, where
`rawPoints = Σ accepted.points` and the multiplier count is the number of **distinct** keys
(`QsoLog.kt:95-97`). HST declares:

```json
"scoring": { "points": "callWeight(theirCall)", "multipliers": [ { "key": "'HST'" } ] }
```

A constant key is contributed by every contact, so the set has exactly one member as soon as there
is a contact, and `rawScore = Σ weight × 1`. The verified side is the same shape for free:
`verifiedPoints` already sums only confirmed contacts (`QsoLog.kt:150-153`) and `SessionController`
sets `verified = verdict.errors.isEmpty()` — which is upstream's `Qso.Err = '   '` test
(`Log.pas:611-619`) — so `verifiedScore = Σ(weight of contacts with no errors)`.

The two totals therefore agree with `UpdateStatsHst` contact for contact. The port expresses the
rule as a score rather than as a mutable total, which is also why the per-contact number is visible
in the log: upstream writes the same number into the score column (`Log.pas:718-719` puts the score
string in `Qso.Pfx`, and `:800-812` prints `Pfx` as the score).

**Why not put the score in the multiplier instead** (`points: 1`, multiplier = the weight string),
which is what upstream's field layout literally does? Because upstream's total *ignores* both
fields and re-sums `CallToScore` over the list; a multiplier keyed by the weight would deduplicate
equal weights (`K1ABC` seen twice would count once) and produce a completely different number. The
sum lives in `points`; the multiplier is the constant that makes the product a sum.

## 5. Where the alphabet lives, and what moved

| File | Change |
|---|---|
| `core-morse/.../MorseAlphabet.kt` | new — the table, moved verbatim from `core-audio`'s `MorseTable` |
| `core-morse/.../MorseWeight.kt` | new — `Log.pas:586-597` + `MorseKey.pas:158-170` |
| `core-morse/.../MorseWeightTest.kt` | new — the worked examples of §3, plus the roster's real calls |
| `core-audio/.../MorseTable.kt` | deleted (moved) |
| `core-audio/.../MorseEncoder.kt` | imports `MorseAlphabet` |
| `core-audio/build.gradle.kts`, `core-expr/build.gradle.kts` | depend on `:core-morse` |
| `settings.gradle.kts` | `include(":core-morse")` |
| `core-expr/.../Builtins.kt` | `Signature("callWeight", 1, 1)` |
| `core-expr/.../Evaluator.kt` | one branch: `callWeight` → `MorseWeight.of(call)`, charging the string's length against the step budget |

`callWeight` is **not** a `CallFacts` lookup. `CallFacts` exists for facts that need *data*
(`DXCC.LIST`, the section table) and fails closed with `CALL_DATA_MISSING`; a Morse weight needs no
data and can never be unknown, so making it a provider-supplied fact would create a fail-closed
path that should be impossible — a provider that forgot to implement it would silently score
zero.

## 6. What the config is

`core-contest/src/main/resources/contests/hst.json`, schema v1, registered in `manifest.json`
(10 ids). HST reuses `TCqWpx` (`Main.pas:505`), so the exchange half is CQ WPX's document:

| Part | Value | Primary source |
|---|---|---|
| id / name | `hst` / `HST (High Speed Test)` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:138` |
| duration | default 30, min 1, max 60 | `Ini.pas:263`; see §7 |
| my fields | `rst` (rst, `RST`), `nr` (serial, `Nr.`, range 1..176) | `Ini.pas:138-145` (`etRST` + `etSerialNr`); captions `ExchFields.pas:44,53`; range `Ini.pas:234` |
| my defaults | `{"rst": "5NN", "nr": "1"}` | `Ini.pas:143` `ExchDefault: '5NN #'`; `Main.pas:1523-1524` forces `NR := 1` for HST |
| sent form | `rst + <three-digit nr>`, one piece | `Station.pas:384-394` (`scWpx, scHst` branch), `:427-433` 599→5NN |
| validation | RST `^[1-5E][1-9N][1-9N]$`; serial 1..9999 | `ExchFields.pas:44` (L3), `:53` (L4) |
| their | `hisNr` ← serial; `exch1 = '599'`, `exch2 = str(hisNr)` | `CqWpx.pas:299-304`; `:323-326` |
| scoring | points `callWeight(theirCall)`; one constant multiplier `'HST'` | `Log.pas:586-597,600-620`; §4 |
| call history | **none** | `CqWpx.pas:87-97`; ADR-0015 §4 |
| messages | **none** — the base wording applies | `CqWpx.pas` has no `SendMsg` override |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `TCqWpx.Create` (`:100-107`) sets neither |

The `nr` range is 1..176 because the DX station's serial comes through the same `CqWpx.GetNR`
path: for HST it is `1 + Round(Random * Minute * Skills)` (`CqWpx.pas:314-320`,
`DxOper.pas:216-220`) — elapsed-time based, like the start-of-contest mode — while the port draws
uniformly from `Ini.pas:234`'s range (ADR-0015 §7.2, unchanged here).

## 7. Duration: no artifact gives HST's window

`Ini.pas:263` carries only the global default (30 minutes), and `docs/` holds nothing that states
HST's competition window. The block is `{ default: 30, min: 1, max: 60 }`, like every earlier
document, and **the gap is declared**: if an artifact states the real window, only `duration`
changes.

## 8. Deviations and known gaps, declared

1. **The HST run mode is not a port run mode.** Upstream's `rmHst` changes the *operating flow*,
   not the exchange: speed steps in 5 WPM (`Main.pas:1101-1114`), RIT steps by 50 Hz
   (`:2306-2311`), and it requires `SerialNR = snStartContest` (`:1987-2000`). The port has one
   receiver and one run-mode set (`single-call`, `pile-up`), so HST is operated exactly like CQ WPX.
   Nothing about the score or the exchange depends on `rmHst`; the *session* differences are
   settings/UI work for a later ticket (T9's settings, ADR-0009).
2. **The log table prints the serial as `%.4d` under `rmHst`** (`Log.pas:800-812`), while the sent
   text stays `%.3d` (`Station.pas:391-394`). The port has no HST-specific log column
   (`ShowCorrections`, `:527`, and the WPM column, `:961`, are absent for every contest), so the
   display difference does not arise; the *sent* text is the faithful `%.3d`.
3. **`HstResults.txt` is an output, not an input.** `PopupScoreHst` (`Main.pas:2210-2230`) appends a
   line to that file; the copy in the upstream tree is 0 bytes. The earlier inventory row
   ("results import", `exchange-facts.md`) was a misreading and is corrected by this ticket. It is
   not packaged as an asset.
4. **No operator-name window title.** `Main.pas:1291` shows `HamName` in the title bar for HST; the
   port has no custom title (the status bar shows the contest name, ADR-0011 §8). Recorded, not
   built.
5. **The multiplier reads the station's call, not the typed one.** `Log.pas:718-719` scores
   `Qso.Call` (what the operator typed); the scoring environment carries `theirCall` (what was
   sent) — ADR-0005 §5's declared deviation, inherited unchanged (ADR-0015 §7.6).
6. **A duplicate attempt does not advance the serial, and is not scored.** Upstream keeps the dupe
   in `QsoList` and counts its weight in the raw total; the port rejects it before recording
   (ADR-0005 §8, ADR-0015 §7.3).
7. **No `rmHst` bandwidth/`ENN`/`TTT` substitutions.** `Station.pas:431` skips the 5 % `599 → ENN`
   corruption and the leading-zero `000 → TTT` replacement for `rmHst`; the port never generates
   either, so the behaviour coincides.
8. **The station-info line is absent**, as for CQ WPX (ADR-0015 §7.8).

## 9. Determinism

ADR-0007 §6's contract is unchanged. HST declares **no dice**; the only draws are the roster and
operator streams CQ WPX already uses, plus the DX serial from the same `SeededRandom` in the same
order. The score itself is a **pure function of the call string** — no state, no time, no seed —
which makes HST's total the most replayable in the set: `HstTest` pins the weights of a fixed list
of calls, and `HstSessionTest` pins a whole session replay.

## Rejected

- **A `scoring.kind` mode (§2).** A new scoring shape for one contest; less composable than a
  built-in; pays in `readScoring`, `ContestRules`, `QsoLog` and spec §4.
- **`points: 1` + the weight as the multiplier key.** Upstream's field layout, but the *total* would
  deduplicate equal weights and stop being a sum (§4).
- **A second alphabet inside `:core-expr` (§2).** Two tables that can drift: sending and scoring
  would disagree, and a wrong score is invisible until the log is compared.
- **Making the weight a `CallFacts` fact (§5).** It needs no data; a provider that forgot it would
  fail closed on a question that can never be unanswerable.
- **Expressing the weight in the document** with a 37-entry list constant and `indexOf`/`listAt`.
  The language has no loop and no fold, so per-character dot counting cannot be expressed; it would
  also put a 37-row table in the contest file.
- **A "real" HST duration from memory (§7).** No artifact states it.
- **Building the `rmHst` run-mode differences now (§8.1).** They are settings/UI work, not
  scoring, and the ticket's contract is the score.
