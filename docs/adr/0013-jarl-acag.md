# ADR-0013 — JARL ACAG as the fourth per-contest ticket

Status: **accepted by delegation** (T13, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1), ADR-0005 (the QSO engine's seams), ADR-0007
(determinism), ADR-0010 (the first per-contest ticket), ADR-0011 and ADR-0012 (whose shapes this
one follows).

T13 is spec §11's fourth row of "one ticket per remaining contest (11 configs + behaviour
checks)". Like T10–T12 it adds **no** app capability and **no** schema field: the picker, the
settings field and `SessionPlanner` already exist. It is a document, an asset, five behaviour
checks and one naming line.

ACAG is ALL JA's twin: the same loader (`tl.Count > 1`, two non-empty cells, no call grammar),
the same scoring rule (`Copy(S, 1, Length(S) - 1)` behind a `L/M/H/P` test), the same "no message
overrides". Three things differ and are the reason this ticket exists at all: the second field is
`etJaCity` with **`L: 7`** instead of `etJaPref`'s `L: 4`, the key is `city` rather than `pref`,
and the shipped data's multipliers **begin with zeros**.

## 1. The contest: JARL ACAG, and why it is this one

The same four criteria T10 used, applied to what was left after T12
(`docs/contests/exchange-facts.md`, T13's updated inventory):

| Criterion | JARL ACAG | CWops CWT | ARRL Field Day | ARRL DX CW | WPX / HST |
|---|---|---|---|---|---|
| an upstream call history to package unmodified | **yes** — `JARL_ACAG.TXT` (2 551 lines) | yes — `CWOPS.LIST` | yes — `FDGOTA.txt` | yes | WPX: none; HST: an empty results file |
| schema v1 only (no new capability) | **yes** — RST + the file's own cell | yes | **no** — the two-pass club-name logic is engine code (`ArrlFd.pas:227-260`) | **no** — `myCountry` is not in `exchange.sent.*` (ADR-0010 §1) | **no** — F8 (the computed-once serial) |
| independent of F8 | yes — no serial | yes | yes | yes | no |
| decidable behaviour checks | yes | yes | mostly | yes | mostly |

ACAG is the smaller of the two remaining schema-v1 contests: CWT's second piece is a name **plus**
a number-or-state-or-country read from the file and its multiplier depends on whether that CWT
number is present, while ACAG's is one cell with a strip. T12's §33 named ACAG as this ticket for
the same reason (it is the twin, so the reading of `substr`/`matches` is already pinned and this
ticket can spend its attention on what is genuinely new — the seven-character code and the leading
zero).

## 2. What the config is

`core-contest/src/main/resources/contests/acag.json`, schema v1, registered in `manifest.json`:

| Part | Value | Primary source |
|---|---|---|
| id / name | `acag` / `JARL ACAG` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:187` |
| duration | default 30, min 1, max 60 | `Ini.Duration` default (`Ini.pas:263`); see §5 |
| my fields | `rst` (rst, `RST`), `city` (text, `Number`) | `Ini.pas:189-190` (`etRST` + `etJaCity`); captions `ExchFields.pas:44,63` |
| my defaults | `{"rst": "5NN", "city": "1002H"}` | `Ini.pas:192` `ExchDefault: '5NN 1002H'` |
| validation | RST `^[1-5E][1-9N][1-9N]$`; code `^([0-9AOTN]*)([LMHP])$`, ≤7 | `ExchFields.pas:44` (L3), `:63` (L7), anchored by `Contest.pas:310` |
| their | `hisNumber` ← history `number`; `exch1 = '599'`, `exch2 = upper(hisNumber)` | `Acag.pas:85-86,214-231` |
| scoring | points `1`; multiplier = `theirExch2` minus a trailing `L/M/H/P` | `Acag.pas:191-207` |
| call history | `JARL_ACAG.TXT`, comma, skip `!!Order!!`/`#`, ≥2 fields, `call,number[,userText]`, call+number required, no dedupe, no DXCC gate | `Acag.pas:50-100` |
| messages | `{}` — ACAG overrides none | `Acag.pas` has no `SendMsg` at all |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `TACAG.Create` (`Acag.pas:106-111`) sets neither |

The call-history asset is copied **unmodified** from `upstream/MorseRunnerCE/JARL_ACAG.TXT` into
`app/src/main/assets/` (38 526 bytes, sha256 `75d0e1ea…`; MPL-2.0, spec §9), keeping upstream's
file name and case (Android assets are case-sensitive). `PackagedAssetsTest` re-parses the
packaged bytes and drives a roster from them.

### Measured facts about the data (used by the tests and this ADR)

- 2 551 lines: a four-line `#` banner and **2 547 data rows**, every one with exactly two cells —
  the third (`userText`) cell the config declares is never present in the shipped file, which is
  the same shape as ALL JA's;
- **0 rows lost to the shared callsign grammar.** `Acag.pas` does no call validation — it only
  requires two non-empty cells — but every one of the 2 547 calls also passes
  `Callsign.isValid`, including the portable `7K1AEU/8`. Stated as a measured fact, not an
  assumption (SST lost 2 rows to the same grammar; ALL JA and ACAG lose none);
- code lengths: **5 characters 1 616 rows, 6 characters 227, 7 characters 704**; the maximum is
  7, exactly the operator box's `L: 7` (`ExchFields.pas:63`), so every row fits and no row needs
  a longer box;
- power letter: M 1 613 rows, H 780, L 154, **P 0** — the `P` branch of the strip is exercised
  only by a test, never by the shipped file;
- **807 distinct multipliers, all digits — and some start with `0`** (`7K1AEU/8,01039M` → `01039`).
  This is the one fact that changes the port's behaviour rather than just its data (§3).

## 3. The multiplier is ALL JA's expression, copied on purpose — and it must stay a string

`Acag.pas:191-207` is byte-for-byte ALL JA's rule (`AllJa.pas:190-207`):

```pascal
S := Qso.Exch2;  P := S[Length(S)];
if CharInSet(P, ['L','M','H','P']) then Result := Copy(S, 1, Length(S) - 1)
else Result := S;
Qso^.Points := 1;
```

The document therefore repeats ADR-0012 §3's expression verbatim:

```json
{ "key": "ifElse(matches(theirExch2, '[LMHP]$'), substr(theirExch2, 1, len(theirExch2) - 1), theirExch2)" }
```

This is **not two independent readings that happen to agree** — it is one rule transcribed once
and shared by two documents, so a change to either must change both. ADR-0012 §3 already pinned
the two argument shapes this depends on (`substr` is 1-based-start + length; `Evaluator.matches`
is `containsMatchIn`, so `[LMHP]$` is the suffix test and the validation regexes need their own
`^…$`), and `AcagTest` re-pins them on ACAG's own data.

The difference that is ACAG's own: **the multiplier is a string, and it has to stay one.**
`upper(hisNumber)` yields a `Value.Str`, and `theirExch2` is bound as a string, so `01039` never
passes through a number. That is load-bearing: if the document had used `str(num(hisNumber))` or
any numeric round-trip, `01039M` would become `1039` and the two codes `01039` and `1039` — both
plausible rows in this file's universe — would collapse into one multiplier. `AcagTest` and
`AcagRulesTest` pin `01039M → 01039` precisely because this is the kind of bug that a
"looks right on the default row" test would miss.

Nothing else in the document asks the engine for anything new: no `their.parse`, no dice, no
serial, no DXCC fact. ACAG's rules never call a `call*` built-in, so **no row of its history
scores 0** for missing data — unlike SST's one-in-10 705 fail-closed DXCC multiplier
(ADR-0011 §3).

## 4. One engine touch: the `city` field's log label is `NR`, and the key is `city`

`Log.pas:943` is `etJaCity: if TrueExch2 <> Exch2 then Exch2Error := leNR` — the line right after
ALL JA's `etJaPref`, with the same label. `ContestRules.labelFor` names a field by its config key
for `name`, `state`, `qth` and `pref`; this ticket adds one line:

```kotlin
field.key == "city" -> QsoError.Number
```

That is naming, not a new rule: the key is the document's own, the enum value already existed
(`QsoError.Number`, printed `NR`), and an unknown TEXT key still falls back to `ERR`. A
mis-copied report is `RST` (`Log.pas:859`), which needed no change.

**Why the key is `city` and not `pref`.** Reusing ALL JA's `pref` would have saved the line, but
it would make the key lie about its own value — a prefecture is not a city/gun/ku code, and the
next reader (or the next contest) would be misled exactly the way
`exchange.myFields[].send` already misleads (§8.6). The key follows the upstream *type*
(`etJaCity`), which is the same rule T12 used for `pref` (`etJaPref`); the caption stays
upstream's `'Number'` (`ExchFields.pas:63`), so the operator still sees the word the Pascal shows.

## 5. Duration: no artifact gives ACAG's real window, so the clamp is the majority's

`Acag.pas` does not override `Ini.Duration`, and `Ini.pas` carries only the global default
(`Duration: integer = 30`, `:263`) plus the competition timer (`CompDuration = 60`, `:267`).
Nothing in `docs/`, in the upstream tree, or in the shipped data states how long an ACAG weekend
is. Rather than write a number from memory, the block is `{ default: 30, min: 1, max: 60 }` — the
same clamp CQ WW, ARRL SS, IARU HF, NAQP, SST and ALL JA use (ADR-0010 §5's F13: the operator's
setting is the source of truth, the contest's block clamps it). **This is a declared gap, not a
football score**: if a future artifact states the real window, only `duration.max` changes. A
first run still starts at 30, which the device run shows as `29:53` after the first second.

## 6. `capabilities.farnsworth: false` is faithful

`TACAG.Create` (`Acag.pas:106-111`) never sets `BFarnsworthEnabled`, so the document says `false`,
the same answer ALL JA gives and the opposite of SST's. Nothing reads the flag yet either way
(ADR-0011 §4's Bucket B finding is unchanged and still not this ticket's).

## 7. Determinism

ADR-0007 §6's contract is unchanged. ACAG declares no dice (`their.dice` is empty), so its roster
draws exactly the same streams as CQ WW's and ALL JA's; the same seed and settings replay the same
session, and `AcagTest`/`AcagSessionTest` both assert a replay. The device run confirmed the
resume path regenerates the same station from the snapshot's seed after a force-stop.

## 8. Deviations and known gaps, declared

1. **No rows are dropped, but the port still validates the call.** `Acag.pas:74-96` accepts any
   non-empty first cell; the port's `call` column is the shared `callsign` type (T6/ADR-0006). On
   the shipped file the two rules agree (0 lost of 2 547, measured), so this is a latent
   difference, not a present one — the same trade ADR-0011 §3 and ADR-0012 §8.1 made.
2. **The station's wording is not the Pascal's.** Upstream's ACAG station has no message
   overrides — the *shared* station script applies — so `messages: {}` is faithful, and the
   port's base wording (`CQ <my> TEST`, `TU`, `NR?`) is what an ACAG station actually says. The
   generic station-side variants (`R ` prefixes, greetings) are still inexpressible; that is
   ADR-0011 §8.3's open gap, not a new one.
3. **No operator exchange editor** (inherited from ADR-0010 §8.1): ACAG sends `5NN 1002H` whatever
   the operator's station is. Same follow-up ticket. For ACAG this is the most visible instance
   yet, because `1002H` is a *city* code (the 1002 block) — every operator, wherever they are,
   is simulated as sending that one city's code with high power.
4. **One band, one mode**: ACAG's per-band and category rules are out of scope for a
   single-receiver simulation, as with every earlier contest.
5. **A blank `city` is rejected, as upstream's regex requires.** `ExchFields.pas:63` demands a
   trailing `L/M/H/P`, so the operator cannot log a station that sent a bare code — the same
   strictness as the Pascal, and the shipped file never needs it.
6. **`exchange.myFields[].send` is loaded and unread.** `acag.json` omits it (as `allja.json`
   does): the value the engine transmits is `station.exchangeDefaults`
   (`SessionBootstrap.myExchange`), not `send`. Recorded as a Bucket B finding in
   `.scratch/progress.md` §33/§34 with the cqww config, which declares one today.
7. **Farnsworth is declared (false) but still not wired** (§6).
8. **The seven-character box is the first `L: 7` in a document.** It is faithful
   (`ExchFields.pas:63`) and the shipped data's longest code is exactly 7, but it means the
   operator's second box accepts four characters more than ALL JA's. That is upstream's
   difference, not a port slip — and `AcagTest` pins both ends (`100115M` accepted,
   `1001156M` rejected) so the two contests cannot drift into each other.

## Rejected

- **Reusing ALL JA's `pref` key to avoid the `labelFor` line.** The key would then say
  "prefecture" about a value that is a city, a gun or a ku code — §4.
- **Naming the key `number` and deriving the label from the caption.** `number` is the history
  *column* name (the file's second cell, which the config binds as `hisNumber`); using the same
  word for the operator's own field would blur two different things, and the caption is not an
  identifier.
- **A numeric multiplier (`str(num(...))`) to "normalise" the code.** It would fold `01039` into
  `1039` — §3. The code is an identifier, not a quantity.
- **CWops CWT or ARRL Field Day now.** Both are larger and neither is a twin: CWT's second piece
  is name + number-or-state-or-country with a conditional multiplier, and Field Day's club-name
  pass is engine code, not a document. ACAG leaves the queue with the smallest remaining
  schema-v1 expressible contest and one genuinely new fact (§3).
- **ARRL DX CW now.** Still blocked on `myCountry` in `exchange.sent.*` (ADR-0010 §1) — schema,
  not config.
- **Writing a "real" ACAG duration from memory.** §5: no artifact states it; the clamp is the
  honest majority value and the gap is declared.
- **A `text` type for the call column** to keep any future non-call row. It would drop the
  normalisation every other history relies on, for 0 present rows (§8.1).
