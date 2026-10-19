# ADR-0015 — CQ WPX and the running serial: F8, an optional call history, and a value instead of an identifier

Status: **accepted by delegation** (T15, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1 and its extension discipline), ADR-0005 (the QSO engine's
seams), ADR-0007 (determinism, and where F8 was filed), ADR-0010 (the first per-contest ticket),
ADR-0011/0012/0013/0014 (whose shapes this one follows).

T15 is spec §11's sixth "one ticket per remaining contest" row, and the **first of that series
that is not a document alone**: the queue held no document-only contest left (arrldx needs
`myCountry` in `exchange.sent.*` plus a history filter, arrlfd needs the two-pass club-name logic,
hst needs the same serial fix CQ WPX needs). F8 — "the operator's serial is computed once" —
blocks both serial contests, so it is this ticket's engine work, and CQ WPX is the contest that
proves it.

**Two schema-level changes are recorded here** (§2 and §4), both used by ADR-0004's extension
discipline: spec §4's `callHistory` became optional, and `exchange.sent.*` now sees the running
serial as a **value**. No identifier was added to the environment table, and no new JSON key was
invented.

## 1. Why CQ WPX, and why it is engine work

The same four criteria T10 used, applied to what was left after T14 (`docs/contests/exchange-facts.md`,
T15's updated inventory):

| Criterion | CQ WPX | HST | ARRL DX CW | ARRL Field Day |
|---|---|---|---|---|
| an upstream call history to package unmodified | **no** — `MASTER.DTA` is the pool only | no | yes | yes |
| schema v1 only (no new capability) | **no** — F8 | **no** — F8 and its own score table | **no** — `myCountry` is not in `exchange.sent.*` | **no** — the club-name logic is engine code |
| independent of F8 | **no** — this is where F8 is fixed | no | yes | yes |
| decidable behaviour checks | yes | mostly | yes | mostly |

Every remaining contest needs engine work, and **F8 blocks two of them**. Fixing it once, on the
contest that uses the serial most simply (RST + serial, one multiplier rule, no history), is the
smallest slice that unblocks the most; HST — the one with its own score table — is the next
ticket.

## 2. F8: the running serial is a **value**, not an identifier

Upstream keeps the operator's serial in `Tst.Me.NR`:

- it is initialised when the contest starts, from the exchange box (`Main.pas:1313-1317`
  `SetMySerialNR` → `:1511-1531` `SetMyExch2`: a numeric box gives its value; `#` and anything
  else give `1`);
- `Log.pas:764-766` increments it after every saved contact, for exactly the two field types that
  carry a serial (`etSSNrPrecedence`, `etSerialNr` — ARRL SS, CQ WPX, HST);
- `Station.pas:364-430 NrAsText` renders the current number as the `<#>` piece.

The port computed `ContestRules.operatorExchange` **once**, in the constructor, so a contest whose
sent piece reads the serial sent the same number all session (`.scratch/progress.md` §27, F8).

**Decision: the engine supplies the number, the document keeps describing the field.**

```kotlin
// core-qso ContestRules
val startingSerial: Int                        // the serial field's value at contest start, else 1
fun serialFor(logged: Int): Int                // startingSerial + logged
fun operatorExchange(serial: Int? = null)      // a serial field's value is replaced by `serial`
fun operator(serial: Int? = null): Operator
```

`sessionController` builds the script with a provider instead of a value, so every render — the
queued pieces, the keyer's current cue and the UI's `myExchange` — reads the current number:

```kotlin
script = MessageScript({ rules.operator(rules.serialFor(log.size)) }, config.messages)
```

and `MessageScript` gained a `() -> Operator` constructor (the fixed-`Operator` form stays as a
secondary constructor, so every existing test and the station-side `StationScript` are unchanged).

**The log is the state.** `log.size` is the number of *accepted* contacts, so the serial is
`starting + log.size` — which is why a resumed session (T9's snapshot restores the log) continues
where it stopped instead of restarting at 001. `CqwpxSessionTest` pins that.

**Why not (b), a new identifier in `exchange.sent.*`'s environment** (`{ "key": "serial" }` or an
`nr` bound by the runtime): it would change spec §4's environment table, the loader's allowed set
and the "unknown identifier is a load-time error" contract — for a number no document supplies.
The document supplies the *field* (its type, range, label, sent form); the number is session
state. A new key would also have to dodge `myFields[].key` collisions, and every future serial
contest would have to agree on its name.

**Why not (c), keep it static:** that is F8.

**Consequence, stated plainly:** ARRL SS's `nr` field is also `type: serial`, and it starts at 1
because its `exchangeDefaults` are empty (`Main.pas:1495-1531`; upstream's SS branch is
`Tst.Me.NR := 1`). SS therefore sends `1 A`, `2 A`, … now instead of the same serial all session.
That is a **fix**, not a regression: `Log.pas:764-766` increments for `etSSNrPrecedence` too.
`arrlss`'s existing tests assert `operatorExchange()` (no serial) and stay green.

## 3. What my exchange reads like: `5NN001`, one piece

`Station.pas:384-394`, the `scWpx` branch of `NrAsText`, is
`Format('%d%.3d', [RST, NR])` — the report and the serial **concatenated, the serial padded to
three digits** — and `:433` then replaces `599` with `5NN`. My exchange is therefore `5NN001`,
not `5NN 1`, and the document says so:

```json
"sent": {
  "exch1": "rst + ifElse(len(str(nr)) >= 3, str(nr), substr('000' + str(nr), len('000' + str(nr)) - 2, 3))",
  "exch2": "''"
}
```

`%.3d` is a **minimum** width, so the `len >= 3` branch keeps a four-digit serial whole
(`5NN1234`); the other branch takes the last three characters of `'000' + nr`. `exch2` is empty,
and `OperatorExchange.piece` then omits it, so `<#>` renders one piece.

`exchange.sent` is used rather than the two-field default because the default joins the pieces
with one space (`OperatorExchange.piece`), and upstream concatenates here.

The station's **received** exchange is still two pieces (`599`, `123`): the entry boxes are RST and
Nr., and the save-time verdict compares two fields (`ContestRules.judge`). The audio transcript
therefore joins them with one space — a display difference, declared in §7.

## 4. `callHistory` is optional: CQ WPX has no history at all

`TCqWpx.LoadCallHistory` (`CqWpx.pas:87-97`) fills its `TCallList` from **`Master.dta`**
(`CallLst.pas:40-61`) — the callsign pool, not a contest file — and `TCqWpx.PickStation` returns
`-1`, so every caller comes from that list. There is no CQ WPX history to package, and the port's
`station.callPool` already says `MASTER.DTA`.

**Decision (a): make the key optional.** `ContestConfig.callHistory: CallHistory?`, the loader
reads it with `required = false`, and `SessionBootstrap` takes the no-history branch: pool =
`MASTER.DTA`, history lookup = `null`, autocomplete empty, note `CQ WPX · MASTER.DTA`. The roster
already tolerates a null lookup when the document binds no history column
(`StationRoster.kt:161-163`), and the loader still rejects a `history` binding without a history,
so "optional" cannot hide a column typo (`ContestLoaderTest` pins both directions).

Spec §4 gained the sentence and the load-time rule; nothing else in the schema changed.

**Why not (b), a small invented table:** that is inventing data, which the ticket series has
refused since T10 (ADR-0011 §8.1's caps, ADR-0014 §8.3's rows).

## 5. What the config is

`core-contest/src/main/resources/contests/cqwpx.json`, schema v1, registered in `manifest.json`
(9 ids):

| Part | Value | Primary source |
|---|---|---|
| id / name | `cqwpx` / `CQ WPX` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:96` |
| duration | default 30, min 1, max 60 | `Ini.Duration` default (`Ini.pas:263`); see §6 |
| my fields | `rst` (rst, `RST`), `nr` (serial, `Nr.`, range 1..176) | `Ini.pas:96-103` (`etRST` + `etSerialNr`); captions `ExchFields.pas:44,53`; range `Ini.pas:234` |
| my defaults | `{"rst": "5NN", "nr": "1"}` | `Ini.pas:101` `ExchDefault: '5NN #'`; `Main.pas:1511-1531` starts `NR` at 1 |
| sent form | `rst + <three-digit nr>`, one piece | `Station.pas:384-394,433`; §3 |
| validation | RST `^[1-5E][1-9N][1-9N]$`; serial 1..9999 | `ExchFields.pas:44` (L3), `:53` (L4), anchored by `Contest.pas:310` |
| their | `hisNr` ← serial; `exch1 = '599'`, `exch2 = str(hisNr)` | `CqWpx.pas:299-304` (`GetExchange`); `:323-326` (`getExch1`) |
| scoring | points `1`; multiplier = `callPrefix(theirCall)` | `CqWpx.pas:285-291`; `Log.pas:715`; `CallsignUtils.pas:58-133` |
| call history | **none** | `CqWpx.pas:87-97`; §4 |
| messages | **none** — the base wording applies | `CqWpx.pas` has no `SendMsg` override |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `TCqWpx.Create` (`:100-107`) sets neither |

The multiplier is the port's **first** use of `callPrefix`: `AssetCallFacts.prefix` is
`CallPrefix.extractPrefix(call, deleteTrailingLetters = true)`, which `CallPrefixTest` already pins
against upstream's 42 golden cases (`Test/CallsignUtilsTest.pas`), so `CqwpxTest`/`CqwpxRulesTest`
only have to prove the *document* asks the right question (`callPrefix(theirCall)`).

## 6. Duration: no artifact gives CQ WPX's window

`CqWpx.pas` does not override `Ini.Duration`; `Ini.pas:263` carries only the global default (30
minutes) and `:267` the competition timer. The WPX rules are on the web, not in the upstream tree,
and writing "48 hours" from memory is exactly what ADR-0014 §5 refused for CWT. The block is
therefore `{ default: 30, min: 1, max: 60 }` — the same clamp every earlier document uses — and
**the gap is declared**: if a future artifact states the real window, only `duration` changes.

## 7. Deviations and known gaps, declared

1. **The serial box's O/T/N letters are not accepted by `validation`.** Upstream's pattern is
   `([0-9OTN]+)|(#)` (`ExchFields.pas:53`) because the operator may type what they hear, and
   `Main.pas:662-673` maps `T → 0`, `N → 9` (and `A → 1`) as they type. Schema v1 binds a numeric
   field as a number when it parses, so `1T` is a `Value.Str` and `nr >= 1` fails closed: the
   port rejects the entry instead of folding the letter. This is the same shape CQ WW's
   `[0-9OANT]+` zone already has (ADR-0010), and the save-time **verdict** still folds the letters
   (`ContestRules.digits`: `T,O → 0`, `N → 9`, `A → 1`, `E → 5`), which `CqwpxRulesTest` pins.
   A schema v1 field cannot have both "numeric" and "letter-tolerant" meanings.
2. **The DX serial is drawn uniformly, not from upstream's elapsed-time rule.** For the default
   start-of-contest mode `GetNR` is `1 + Round(Random * Tst.Minute * Skills)` (`CqWpx.pas:314-320`,
   `DxOper.pas:216-220`) — a function of elapsed time and the operator's skill — while the
   mid/end-of-contest modes use a 2023 WPX entry distribution (`CqWpx.pas:118-212`). Schema v1 has
   one `{ "source": "serial" }` binding over the serial field's range, so the port draws
   **uniformly from `Ini.pas:234`'s 1..176**, stable per station for a given seed. The declared
   approximation: the *range* is upstream's, the *distribution* is the port's.
3. **A duplicate attempt does not advance the serial.** Upstream records a duplicate as `Dupe` and
   still `Inc`s `Tst.Me.NR`; the port rejects the contact before recording it (ADR-0005 §8), so
   `log.size` does not move and the next contact re-sends the same number. A consequence of an
   existing decision, not a new one.
4. **The received exchange is joined with one space.** `SessionController` renders
   `"${exch1} ${exch2}"`, where upstream's WPX `NrAsText` concatenates (see §3). This is the same
   display-layer difference ADR-0014 §8.4 declared for CWT; "fixing" it would change every
   contest, so it is declared here instead.
5. **No operator exchange editor** (inherited from ADR-0010 §8.1): the serial starts at 1 whatever
   an operator would type, and `exchange.myFields[].send` stays loaded-unread.
6. **The multiplier reads the station's call, not the typed one.** `Log.pas:715-716` extracts the
   prefix from `Qso.Call` (what was typed); schema v1's scoring environment carries `theirCall`
   (what was sent), which is ADR-0005 §5's declared deviation.
7. **A call with no resolvable WPX prefix fails closed.** `callPrefix` returns
   `CALL_DATA_MISSING` when `CallFacts.prefix` is null, and the engine then reports a zero-point
   `ScoreResult` instead of scoring a guess — upstream would give the contact one point and no
   multiplier. Measured on the shipped pool: **0 of 46 039** `MASTER.DTA` calls fail the WPX prefix
   rule (`PackagedAssetsTest`), so the path cannot cost a real station its point; it can only fire
   for a call the shared grammar would already have rejected.
8. **No station-info line.** `TCqWpx.GetStationInfo` (`CqWpx.pas:264-278`) prints
   `<call> - <Continent>/<Entity>` in the status bar; the port's status bar has no such line
   (ADR-0011 §8's `userText` finding, unchanged).
9. **One band, one mode**: WPX's per-band, single-operator and multi-operator categories are out
   of scope for a single-receiver simulation, as with every earlier contest.

## 8. Determinism

ADR-0007 §6's contract is unchanged. CQ WPX declares **no dice**, so it draws exactly the roster
and operator streams the other contests do; the only contest-specific draw is the serial, which
`StationRoster` takes from the same `SeededRandom` in the same order. `CqwpxTest` pins that 20
seeds produce serials inside 1..176 and that one seed replays byte for byte; `CqwpxSessionTest`
pins the session replay, and the serial is a pure function of the seed and the log.

## Rejected

- **A new environment identifier for the serial (§2 b).** It changes the §4 environment table and
  the loader's allowed set for a number no document supplies, and every future serial contest
  would have to agree on the key.
- **Keeping the serial static (§2 c).** That is F8, the finding this ticket exists to clear.
- **Inventing a CQ WPX call history (§4 b).** Upstream has none; the pool is `MASTER.DTA`.
- **Padding the serial by plain truncation.** `substr('000' + str(nr), …)` without the
  `len >= 3` guard turns `1234` into `234`; `%.3d` is a minimum width, not a mask.
- **A `pad(str, width)` built-in.** One contest needs one padding rule; `+`, `len`, `substr` and
  `ifElse` express it, the same trade ADR-0004 made when it refused `printf`.
- **A `serial` key in `station` or `exchange`.** The start is already `exchangeDefaults`; the
  running value is state, and state lives in the log (§2).
- **Writing a "real" 48-hour duration from memory (§6).** No artifact states WPX's window.
