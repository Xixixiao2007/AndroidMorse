# ADR-0018 — ARRL Field Day: the last contest, and the only history whose *load* is an algorithm

Status: **accepted by delegation** (T18, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0003 (the bounded expression language), ADR-0004 (schema v1 and its
extension discipline), ADR-0005 (the QSO engine's seams), ADR-0006 (call history / station /
operator), ADR-0007 (determinism), ADR-0015 (the optional history), ADR-0016 (the sum-shaped
score) and ADR-0017 (the row filter).

T18 is spec §11's last "one ticket per remaining contest" row, and the twelfth document: after it,
every id in `ContestRegistry.BUNDLED_IDS` ships a config. It is also the **only contest in the
queue whose call-history load is an algorithm** rather than a row predicate: ARRL Field Day builds
its station pool in two passes, with a 25 % cull, because one club can be represented twice — by a
club station and by home/portable members (`ArrlFd.pas:192-383`).

## 1. Why Field Day now, and what was left

After T17 the twelve ids all had a document except `arrlfd`, so the ticket was fixed and only the
slicing was open. The four criteria T10 established, applied to the one contest left:

| Criterion | ARRL Field Day |
|---|---|
| an upstream call history to package unmodified | **yes** — `FDGOTA.txt`, 17 330 lines, header-driven |
| schema v1 only, no new capability | **no** — the load is a two-pass reduction with a random cull |
| independent of earlier tickets | yes — the exchange is two history strings |
| decidable behaviour checks | **yes** — 2 points, a constant multiplier, class + section copy-down, and a replayable pool |

The handoff predicted "the club-name logic is engine code"; §2 records which way that went and why
the obvious alternative — express the two passes as data — is the larger change, not the smaller.

## 2. The decision: `callHistory.load` names a strategy; the algorithm is engine code

The document gains **one optional key**:

```json
"callHistory": { …, "load": "two-pass-clubs" }
```

`HistoryLoadMode` (`TWO_PASS_CLUBS("two-pass-clubs", requiredColumns = ["class", "club"])`) is the
enum a config names; `CallHistoryFile.parse` hands the rows it accepted to
`TwoPassClubLoad.apply(rows, seed)`, and the loader rejects a config that names the strategy
without the columns it reads. The strategy is one object:
`core-contest/src/main/kotlin/com/bh4gzk/morserunner/contest/TwoPassClubLoad.kt`.

### What the strategy is, line for line

- **Pass 1** (`ArrlFd.pas:222-306`): every club station (class suffix `A`/`C`/`F` — upstream's
  `else` branch, which is every suffix that is not home/portable) is added as it is read; a later
  station from a club already seen replaces the earlier one **only when its transmitter count is
  strictly greater** (`TxCnt`, the class's leading digits). A home/portable station (`D`/`E`/`B`)
  **without** a club name is kept with probability `0.25`; **with** a club name it is deferred.
- **Pass 2** (`:308-350`): a deferred club already occupied in pass 1 has its whole group dropped;
  otherwise exactly one row is picked from the group's top transmitter-count segment
  (`PickPendingCallIdx`, `:130-160`) and kept if its class ends in `B` or with probability `0.25`.
- The pool is sorted by call (`FdCallList.Sort`, `:349`).

### Why not a declarative reduction

The bounded expression language (ADR-0003) has no loop, no group-by, no aggregate and no
row-level randomness; the load combines file order, per-club grouping, a max-by, a deferred
second pass and three separate random decisions. Expressing it as data would mean inventing a
**reduction DSL** (group-by + pick + dice) for one contest — a second language, larger than the
150-line strategy and impossible to check line for line against upstream. ADR-0002's "contests
are data" governs the rules the *engine* evaluates (exchange, scoring, validation, messages); a
loader is engine code even upstream — `TArrlFieldDay.LoadCallHistory` is 190 lines of Pascal.
What the document must still declare is the *choice* of strategy, and it does.

### Why not a `load` object with the rule's parameters

`{"mode": "two-pass-clubs", "homeSuffixes": ["D","E"], "portableSuffixes": ["B"],
"keepChance": 0.25, "pick": "highest"}` would make the strategy's constants data. Rejected:
(a) it invents a family of keys for one contest and then has to validate every combination
(`keepChance = 0`? a missing `homeSuffixes`?); (b) the values are not contest *decisions*, they
are the transcription of one Pascal function — an operator editing them would get a contest
upstream never had; (c) ADR-0015's discipline is explicit: prefer one key that names behaviour
over several that half-describe it. The loader does enforce the strategy's *contract*: an unknown
name, or a config that names the strategy without a `class`/`club` column, is a load error.

### What the loader gained (and nothing else)

- `HistoryLoadMode` and `CallHistory.load: HistoryLoadMode?` — absent keeps the generic parser;
- `CallHistoryFile.parse(…, seed)` — the strategy reads the session seed (§3);
- three rejections: unknown strategy name, missing required column, and the existing "no usable
  records" (an empty pool after two passes is still an empty pool).

No new identifier reaches the expression environment (spec §4's table is unchanged), no built-in
was added (spec §5 unchanged), and `callHistory.filter` (T17) is untouched: a strategy is a
sibling of the filter, not a replacement — a spec may carry both, and the filter then runs per row
before the strategy sees the survivors.

## 3. The randomness: per-identity declared draws from the session seed

Upstream calls the **unseeded, process-global** `Random` at three sites: the pass-1 cull
(`ArrlFd.pas:262`), the within-segment pick (`:157`), and the pass-2 keep (`:344`). ADR-0007
requires every random decision to be declared and replayable, and every earlier ticket turned its
draws into `their.dice` — a *fixed-size list* the config declares. The load's draw count is not
fixed: it depends on how many rows are eligible and how many clubs are free. The candidates:

| Candidate | Why it was not taken |
|---|---|
| a per-row declared dice list (`their.dice`-style) | **impossible**: the schema cannot enumerate draws whose number depends on the file's contents |
| a load-block `dice` stream consumed in a documented order | possible, but every decision would depend on the number and order of earlier ones, so a future `FDGOTA.txt` update would reshuffle the whole pool; the "declaration" would be a count no config can state |
| **a deterministic draw from (seed, purpose, identity)** | **chosen** §3 |

**The draw.** `TwoPassClubLoad.draw(seed, purpose, identity)` =
`SplitMix64(seed xor FNV-1a(purpose NUL identity)).nextDouble()`. The two purpose tags — `keep`
(identity = the callsign) and `pick` (identity = the club name) — are part of the replay contract:
renaming one changes every session. The distribution is uniform in `[0, 1)`, so `draw < 0.25` is
upstream's `Random < 0.25`, and `(draw * segmentSize).toInt()` is upstream's
`Random(EndIdx − BeginIdx)`.

**Why this satisfies ADR-0004 decision 3 rather than dodging it.** Dice are *inputs* to the
engine; here the engine derives them from the seed and the decision's identity, exactly as
`FadeSchedule.seedFor(seed, call)` derives a fade stream from the seed and the callsign (T5). The
set of random decisions is enumerable (three sites, two purpose tags), the outcome is a pure
function of (file, seed), and nothing in `app` draws randomness outside the controller's two
streams.

**Where the seed comes from.** `SessionBootstrap` passes the session seed it already hands
`SessionController` (the roster's stream, the operators' `seed + 1`, the QRM's derived stream). A
resumed session carries the snapshot's seed, so the pool replays *with the session* — which is
what makes a disputed Field Day contact reproducible.

## 4. The document, and the measured data

`contests/arrlfd.json`, schema v1, loadable by `ContestLibrary.load`:

| Part | Value | Upstream |
|---|---|---|
| `id` / `name` | `arrlfd` / `ARRL Field Day` | `Ini.pas:118-125` |
| `duration` | `30 / 1 / 60` minutes | §7.5 |
| `myFields` | `class` (text) + `section` (text) | `Ini.pas:120-124`: `etFdClass` + `etArrlSection` |
| `sent` | `class`, `section` | the two boxes are sent as they are |
| `their.exch1/exch2` | `hisClass`, `hisSection` ← history `class`, `section` | `ArrlFd.pas:495-510` |
| `validation` | class `^[1-9][0-9]?[A-F]$`, section `^[A-Z]{2,3}$` | `ExchFields.pas:46,55`; `Contest.pas:571,584` |
| `scoring.points` | `2` | `ArrlFd.pas:479-486` `Qso^.Points := 2` |
| `scoring.multipliers` | one key, `'1'` | `Result := '1'` — no multiplier at all |
| `callHistory` | header-driven, `minFields: 3`, four columns, `load: "two-pass-clubs"` | `ArrlFd.pas:192-383` |
| `callHistory.asset` | `FDGOTA.txt` (459 501 bytes, sha256 `0b8f78e2…f7116`) | packaged unmodified, MPL-2.0 |
| `messages` | `CQ: "CQ FD <my>"`, `longCQ: "CQ CQ FD <my> <my> FD"` | `ArrlFd.pas:418-433` |
| `capabilities` | `requiresMyDxcc: false`, no Farnsworth | `ArrlFd.pas` never looks the operator up |

**The file** (measured 2026-10-20): 17 330 lines = 17 322 data rows + one `!!Order!!` header + seven
`#` banner lines. Cell counts: 12 511 four-cell, 4 804 three-cell (no club cell at all), 7 five-cell.
Class suffixes: `D` 8 817, `A` 2 909, `E` 2 862, `B` 2 120, `F` 378, `C` 236 (47 distinct classes, 86
distinct sections). 12 240 rows carry a club name over 4 136 clubs: 3 355 club-station rows (over
2 879 clubs), 8 885 home/portable with a club, 4 914 without; 2 266 pending clubs, 1 257 of them free.

**The pool**: 17 322 rows reduce to **4 742 stations at seed 20 261 020** — the number
`RealCallHistoryTest` and `PackagedAssetsTest` pin, because it *is* the replay contract. It is
3 047 club stations (2 879 club names + 168 anonymous), about a quarter of the 4 914 anonymous
home/portable rows, and one station from a share of the 1 257 free clubs.

Two file details the config must tolerate, both measured:

- **The seven five-cell rows are club names with a comma in them** (e.g.
  `K5JFB,1E,NTX,Kilocycle Club of Fort Worth, TX`), not a trailing comma. Upstream reads
  `tl.Strings[3]` and drops the rest, and cell 3 is what the port's `club` column reads — so the
  two agree, and the extra cell is ignored on both sides.
- **Two calls are duplicated** (`NN0JS` twice with a case-varied club name, `WX4SC` twice with two
  different club names). The port's pool is keyed by call, so they collapse to one entry each
  (later row wins); upstream's list would hold both. 2 rows of 17 322 — §7.3.

## 5. Scoring and messages, in one key each

- **Two points, no multiplier.** `scoring.points` is the literal `2`; `scoring.multipliers` is the
  single constant key `'1'`. That is the same shape as HST's sum (ADR-0016 §4): `QsoLog` multiplies
  points by the number of *distinct* keys, so one constant key makes the score a plain sum of 2s —
  two contacts are 4 points × 1 key = 4, and a different class/section is not a multiplier.
- **The CQ wording** is the contest's only message override: `CQ FD <my>` and
  `CQ CQ FD <my> <my> FD`. `TU`, the exchange piece and every station reply keep the base wording.

## 6. Determinism and the empty pool

The same seed replays the same pool byte for byte, and a different seed moves both the culls and
the picks — pinned in `ArrlFdTest` (both outcomes exist over a seed range, so the cull is proven
both ways), `RealCallHistoryTest` (replay + structural invariants) and the app's session test. A
two-pass load that yields nothing is `HistoryLoad.Rejected` ("no usable records"), never a loaded
contest with no stations — T17's precedent, and the generic empty check covers the strategy's
empty result.

## 7. Deviations (recorded, not hidden)

1. **The station's repeat questions.** `ArrlFd.pas:425-433` picks `NR?` / `SECT?` / `CLASS?` /
   `CL?` by `Floor(AStn.R1 * 5)` — a *per-station* choice, so that a station asks for the piece it
   wants repeated. The port's `StationScript` renders one wording per message
   (`messages["NR?"]`); a per-station choice needs a station-side conditional mode, a message-engine
   change larger than the contest. The port sends `NR?` for all four.
2. **The station info line.** `GetStationInfo` (`:455-480`) appends the club name (and, for a `DX`
   section, the DXCC entity) to the status-bar line. The port has no station-info line at all
   (ADR-0011 §8); the `club` column is declared and read by the load, and nothing else consumes it.
   It was **not** invented for this ticket.
3. **Duplicate calls** collapse (§4): 2 rows of 17 322.
4. **The 25 % cull is a specified substitute** for upstream's unseeded `Random` (§3): the
   distribution is upstream's, the selection is the port's replayable one.
5. **`duration`**: `Ini.pas` sets the session length globally (30 minutes) and the Field Day block
   carries none; `30 / 1 / 60` exactly as T10–T17 (the same gap ADR-0017 §9 records).

## 8. What this ticket did not change

- `callHistory.filter` (ADR-0017), `hst.json` (T16), `arrldx.json` (T17) and every earlier
  contest: untouched.
- The UI: no Field Day screen change. The two entry boxes are the two exchange strings, exactly
  what `exchange.myFields` already produces.
- The roster: `PickStation`/`DropStation` needed no port — `StationRoster` already draws from a
  `CallPool` and removes from it, and the two-pass load produces a `CallPool` + `HistoryLookup`.

## Rejected

- **Hard-coding `arrlfd` in `CallHistoryFile`.** The strategy is named and reviewable, and a user
  config (or a future contest) can name the same strategy — hard-coding one id would hide the
  choice inside the parser.
- **A `keepEverything`/`cullChance` switch on the generic parser.** It would describe a policy
  without describing the two passes, and the loader would have to guess the rest.
- **Making the strategy a built-in expression.** A built-in must be total and composable
  (ADR-0003/§5); a two-pass reduction over the whole file is neither — it is a loader.
- **A `random()` in the language.** ADR-0004 decision 3 forbids it, and this ticket is about
  keeping that promise while a load needs randomness: the draws stay inputs (§3).
