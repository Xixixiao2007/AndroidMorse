# ADR-0012 — JARL ALL JA as the third per-contest ticket

Status: **accepted by delegation** (T12, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1), ADR-0005 (the QSO engine's seams), ADR-0007
(determinism), ADR-0010 (the first per-contest ticket) and ADR-0011 (whose shape this one
follows).

T12 is spec §11's third row of "one ticket per remaining contest (11 configs + behaviour
checks)". Like T10 and T11 it adds **no** app capability and **no** schema field: the picker,
the settings field and `SessionPlanner` already exist. It is a document, an asset, four
behaviour checks and one naming rule — but it is the first document whose scoring expression
needs `substr`, so the language's argument shape is pinned here rather than assumed.

## 1. The contest: JARL ALL JA, and why it is this one

The same four criteria T10 used, applied to what was left after T11
(`docs/contests/exchange-facts.md`, T11's corrected inventory):

| Criterion | JARL ALL JA | CWops CWT | JARL ACAG | ARRL DX CW | WPX / HST / Field Day |
|---|---|---|---|---|---|
| an upstream call history to package unmodified | **yes** — `JARL_ALLJA.TXT` (2 542 lines) | yes — `CWOPS.LIST` | yes — `JARL_ACAG.TXT` | yes | WPX: none; FD: yes |
| schema v1 only (no new capability) | **yes** — RST + the file's own cell | yes | yes | **no** — `myCountry` is not in `exchange.sent.*` (ADR-0010 §1) | **no** — F8 (the computed-once serial) |
| independent of F8 | yes — no serial | yes | yes | yes | no |
| decidable behaviour checks | yes | yes | yes | yes | mostly |

ALL JA is the smallest of the three expressible ones: CWT's exchange is a name plus a
**number-or-state-or-country** from the file, ACAG's is a city/gun/ku code with its own
matching rule, and ALL JA's is the file's `<prefecture><power>` cell verbatim plus one
multiplier transformation. It is also the only one of the three whose scoring exercises a
string operation the earlier documents never used.

## 2. What the config is

`core-contest/src/main/resources/contests/allja.json`, schema v1, registered in
`manifest.json`:

| Part | Value | Primary source |
|---|---|---|
| id / name | `allja` / `JARL ALL JA` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:178` |
| duration | default 30, min 1, max 60 | `Ini.Duration` default (`Ini.pas:263`); see §5 |
| my fields | `rst` (rst, `RST`), `pref` (text, `Number`) | `Ini.pas:180-181` (`etRST` + `etJaPref`); captions `ExchFields.pas:43,62` |
| my defaults | `{"rst": "5NN", "pref": "10H"}` | `Ini.pas:183` `ExchDefault: '5NN 10H'` |
| validation | RST `^[1-5E][1-9N][1-9N]$`; number `^([0-9AOTN]*)([LMHP])$`, ≤4 | `ExchFields.pas:44` (L3), `:62` (L4), anchored by `Contest.pas:310` |
| their | `hisNumber` ← history `number`; `exch1 = '599'`, `exch2 = upper(hisNumber)` | `AllJa.pas:85-86,214-227` |
| scoring | points `1`; multiplier = `theirExch2` minus a trailing `L/M/H/P` | `AllJa.pas:190-207` |
| call history | `JARL_ALLJA.TXT`, comma, skip `!!Order!!`/`#`, ≥2 fields, `call,number[,userText]`, call+number required, no dedupe, no DXCC gate | `AllJa.pas:50-100` |
| messages | `{}` — ALL JA overrides none | `AllJa.pas` has no `SendMsg` at all |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `TALLJA.Create` (`AllJa.pas:105-117`) sets neither |

The call-history asset is copied **unmodified** from `upstream/MorseRunnerCE/JARL_ALLJA.TXT`
into `app/src/main/assets/` (31 462 bytes, sha256 `d7952200…`; MPL-2.0, spec §9), keeping
upstream's file name and case (Android assets are case-sensitive). `PackagedAssetsTest`
re-parses the packaged bytes and drives a roster from them.

### Measured facts about the data (used by the tests and this ADR)

- 2 542 lines: a four-line `#` banner and **2 538 data rows**, every one with exactly two
  cells (no user text at all in the shipped file);
- **0 rows lost to the shared callsign grammar.** `AllJa.pas` does no call validation — it only
  requires two non-empty cells — but every one of the 2 538 calls also passes
  `Callsign.isValid`, so upstream's rule and the port's agree exactly. This is the opposite of
  SST's measured 2-row gap (ADR-0011 §8.1) and it is stated as a fact, not an assumption;
- the power letter: M 1 528 rows, H 896, L 114, **P 0** — so the `P` branch of the strip is
  exercised only by a test, never by the shipped file;
- number lengths: 2 441 rows are three characters (`02`–`48` + a letter), **97 are four**
  (`101`–`114`, the Hokkaido promotion-bureau codes `AllJa.pas:187`); the maximum is 4, so
  every row fits the operator's box (`L: 4`);
- 59 distinct prefectures and, after the strip, 59 distinct multipliers — all digits. No row
  needs the "last character is not a power letter" branch.

## 3. The multiplier is the first `substr` in a document — and the `$` is load-bearing

`AllJa.pas:190-207` is three lines of Pascal:

```pascal
S := Qso.Exch2;  P := S[Length(S)];
if CharInSet(P, ['L','M','H','P']) then Result := Copy(S, 1, Length(S) - 1)
else Result := S;
Qso^.Points := 1;
```

The document says the same thing, with the two built-ins whose argument shapes were checked
against the evaluator before they were written:

```json
{ "key": "ifElse(matches(theirExch2, '[LMHP]$'), substr(theirExch2, 1, len(theirExch2) - 1), theirExch2)" }
```

- `Evaluator.substring` takes a **1-based start** and a **length** as its third argument
  (`Evaluator.kt:261-281`), so `Copy(S, 1, Length(S) - 1)` transcribes to
  `substr(S, 1, len(S) - 1)` and not to `substr(S, 1, len(S))`;
- a length of 0 returns the empty string, which the engine then skips as a multiplier key
  (`ContestRules.score`) — exactly upstream's `Copy('M', 1, 0) = ''`;
- `Evaluator.matches` is `Regex.containsMatchIn`, **not** a whole-string match
  (`Evaluator.kt:283-296`). So `matches(x, '[LMHP]$')` really is "ends with one of L/M/H/P",
  and the `^…$` anchors the *validation* regexes need would be wrong here. `AllJaTest` pins
  both readings: `27M → 27`, `2708 → 2708` (not `270`) and `M27 → M27` (not `27`);
- `ifElse` is the language's one lazy built-in (ADR-0011 §3), so a station whose number does
  not end in a power letter never evaluates `substr` — a blank `theirExch2` would otherwise
  make `len - 1` negative and fail closed.

Nothing else in the document asks the engine for anything new: no `their.parse` (the
multiplier reads `theirExch2`, not a group), no dice, no serial, no DXCC fact. That last point
matters: SST's fail-closed DXCC multiplier costs one record in 10 705 (ADR-0011 §3); ALL JA's
rules never call a `call*` built-in, so **no row of its history scores 0** for missing data.

## 4. One engine touch: the `pref` field's log label is `NR`

`Log.pas:942` is `etJaPref: if TrueExch2 <> Exch2 then Exch2Error := leNR` — not `leST` (NAQP)
and not `leQTH` (SST). `ContestRules.labelFor` already names a field by its config key for
`name`, `state` and `qth`; this ticket adds one line:

```kotlin
field.key == "pref" -> QsoError.Number
```

That is naming, not a new rule: the key is the document's own, the enum value already existed
(`QsoError.Number`, printed `NR`), and an unknown TEXT key still falls back to `ERR`. A
mis-copied report is `RST` (`Log.pas:859`), which needed no change.

## 5. Duration: no artifact gives ALL JA's real window, so the clamp is the majority's

`AllJa.pas` does not override `Ini.Duration`, and `Ini.pas` carries only the global default
(`Duration: integer = 30`, `:263`) plus the competition timer (`CompDuration = 60`, `:267`).
Nothing in `docs/`, in the upstream tree, or in the shipped data states how long an ALL JA
weekend is. Rather than write a number from memory, the block is
`{ default: 30, min: 1, max: 60 }` — the same clamp CQ WW, ARRL SS, IARU HF, NAQP and SST use
(ADR-0010 §5's F13: the operator's setting is the source of truth, the contest's block clamps
it). **This is a declared gap, not a football score**: if a future artifact states the real
window, only `duration.max` changes. A first run still starts at 30.

## 6. `capabilities.farnsworth: false` is faithful

`TALLJA.Create` (`AllJa.pas:106-111`) never sets `BFarnsworthEnabled`, so the document says
`false` — the first per-contest ticket whose answer differs from SST's. Nothing reads the flag
yet either way (ADR-0011 §4's Bucket B finding is unchanged and still not this ticket's).

## 7. Determinism

ADR-0007 §6's contract is unchanged. ALL JA declares no dice (`their.dice` is empty), so its
roster draws exactly the same streams as CQ WW's; the same seed and settings replay the same
session, and `AllJaTest`/`AllJaSessionTest` both assert a replay. The device run confirmed the
resume path regenerates the same station from the snapshot's seed.

## 8. Deviations and known gaps, declared

1. **No rows are dropped, but the port still validates the call.** `AllJa.pas:74-96` accepts
   any non-empty first cell; the port's `call` column is the shared `callsign` type
   (T6/ADR-0006). On the shipped file the two rules agree (0 lost of 2 538, measured), so this
   is a latent difference, not a present one. A future file could expose it — the same trade
   ADR-0011 §3 made, recorded here so nobody re-discovers it.
2. **The station's wording is not the Pascal's.** Upstream's ALL JA station has no message
   overrides — the *shared* station script applies — so `messages: {}` is faithful, and the
   port's base wording (`CQ <my> TEST`, `TU`, `NR?`) is what an ALL JA station actually says.
   The generic station-side variants (`R ` prefixes, greetings) are still inexpressible; that
   is ADR-0011 §8.3's open gap, not a new one.
3. **No operator exchange editor** (inherited from ADR-0010 §8.1): ALL JA sends `5NN 10H`
   whatever the operator's station is. Same follow-up ticket. This one is more visible than
   for NAQP/SST because `10H` is a *prefecture* number: a non-Japanese operator is simulated
   as sending Tokyo's code.
4. **One band, one mode**: ALL JA's per-band and category rules are out of scope for a
   single-receiver simulation, as with every earlier contest.
5. **A blank `pref` is rejected, as upstream's regex requires.** `ExchFields.pas:62` demands a
   trailing `L/M/H/P`, so the operator cannot log a station that sent a bare prefecture — the
   same strictness as the Pascal, and the shipped file never needs it.
6. **`exchange.myFields[].send` is loaded and unread.** `allja.json` therefore omits it: the
   value the engine transmits is `station.exchangeDefaults` (`SessionBootstrap.myExchange`),
   not `send`. Recorded as a Bucket B finding in `.scratch/progress.md` §33 with the cqww
   config, which declares one today.
7. **Farnsworth is declared (false) but still not wired** (§6).

## Rejected

- **CWops CWT or JARL ACAG now.** CWT's second piece is a number *or* a state *or* a country
  read from the file, and its multiplier depends on the CWT number's presence; ACAG's
  city/gun/ku code needs its own matching rule. Both are expressible, but larger, and neither
  would exercise a language feature the port has not already run.
- **ARRL DX CW now.** Still blocked on `myCountry` in `exchange.sent.*` (ADR-0010 §1) —
  schema, not config.
- **A `text` type for the call column** to keep any future non-call row. It would drop the
  normalisation every other history relies on, for 0 present rows (§8.1).
- **Writing a "real" ALL JA duration from memory.** §5: no artifact states it; the clamp is the
  honest majority value and the gap is declared.
- **A third sent-piece expression for the number.** `exch2 = upper(hisNumber)` is upstream's
  own `UpperCase` at load (`:85-86`); a derive would add a step the Pascal does not have.
- **Naming the second field `number` and reading its label from the caption.** The config key
  is also the expression identifier and the `labelFor` key; `pref` is the name the upstream
  *type* has (`etJaPref`), which keeps `labelFor` from guessing at a generic `number`.
