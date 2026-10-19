# Exchange facts — the three hardest contests (T3 input)

Source of truth: `upstream/MorseRunnerCE/` @ `2dd6f5c` (MRCE 1.85.4). Every claim below is read
from the Pascal, with `file:line`. This is the **pressure test** material for the config schema
(ADR-0002) and the expression language (ADR-0003): if the schema can absorb these three, the
other nine are expected to fit.

Extraction template for T10–T20 — answer all of these per contest:
1. call-history file: name, delimiter, header row?, column order, skip rules, extra validation
2. my exchange: how the operator enters it, how it is split into Exch1 / Exch2
3. DX exchange: what Exch1 / Exch2 contain, and where each piece comes from
4. realism quirks (deliberate corruption the operator must copy through)
5. QSO points rule
6. multiplier string
7. message overrides (CQ wording, etc.)
8. capability flags (Farnsworth, requires-my-DXCC, …)

---

## CQ WW — `CqWW.pas`

| | |
|---|---|
| call history | `CQWWCW.TXT`, comma, **no header**; `call,cqzone,usertext` with `tl.Count > 2` (`:84`); skips `!!Order!!` and `#` lines (`:79-80`); rejects non-numeric zone (`:93`); optional user text (`:90`) |
| my exchange | base class (not overridden) |
| DX Exch1 | always `'599'` (`:285-288`) |
| DX Exch2 | CQ zone from call history (`:291-298`), also stored as numeric `NR` (`:280`) |
| realism | 5 % of the time a leading `0` is inserted for zones < 9 (`:296-297`) |
| points | different continent → 3; same entity → 0; same continent `NA` → 2; else 1 (`:258-265`) |
| multiplier | `ZN-<zone>` and, unless `/MM`, also `;<entity>` (`:233-255`); Alaska/Hawaii collapse to `United States of America` (`:251-253`) |
| special | `/MM` suffix scores 0 points and yields **zone only** (`:236-240`) |

Notes for the schema: needs DXCC lookups (continent, entity) for *both* stations, a suffix test on
the call, and a two-part multiplier string. Points is a small ordered decision table, so a ternary
chain suffices.

## ARRL Sweepstakes — `ArrlSS.pas`

| | |
|---|---|
| call history | `SSCW.TXT`, comma, needs `tl.Count > 3`; columns `call,section,?,check[,usertext]` (`:110-113`); skips `!!Order!!` (`:106`); `check` must parse as integer (`:112`); call must pass `TSSLexer.IsValidCall` (`:116`); list is sorted, lookups are binary searches (`:124`) |
| my exchange (entered) | **one text field** parsed as a whole (`:179-180`):<br>`^ *(?P<exch1>(?P<nr>[0-9]+|#)? *(?P<prec>[QABUMS])) +(?P<chk>[0-9]{2}) +(?P<sect>[A-Z]{2,3}) *$` |
| my exchange (split) | `Exch1 = "[nr] prec"`, `Exch2 = "chk sect"` (`:193-198`) |
| DX Exch1 | `"<serial> <precedence>"` (`:437`) |
| DX Exch2 | `"<check:02d> <section>"` (`:440`) |
| precedence | weighted: A 43 %, B 19 %, U 28 %, Q/M/S 10 % (`:422-430`) |
| realism | 10 % chance check becomes ±1 (mod 100) (`:489-495`); 10 % chance the section is replaced by a table neighbour (`:498-499`, `:507-517`) |
| points | constant 2 (`:469`) |
| multiplier | the ARRL/RAC section (`:470`) |
| messages | `msgCQ` → `CQ SS <my>`; long CQ → `CQ CQ SS <my> <my> SS` (`:256-257`) |
| callsign in exchange | the parser may extract a **corrected callsign** from the exchange field (`:296-313`), which suppresses re-sending |

Notes: this is the case that motivated ADR-0003 — four semantic tokens in one input box, validated
jointly, plus a callsign that can be corrected inside the exchange. `GetAlternateSection` needs
“adjacent entry in a fixed table”, which is **not** expressible with the v1 built-in set (see gaps).

## IARU HF — `IaruHf.pas`

| | |
|---|---|
| call history | `IARU_HF.txt`, comma, **header-driven**: `!!Order!!,Call,Sect,UserText,` defines the column indices (`:115-126`); skips `#` lines (`:114`); dedupes by call (`:143-144`); also requires *my* callsign to resolve in DXCC before any record is added (`:147-151`) |
| my exchange | RST + (ITU zone **or** IARU society) — chosen by the operator, explicitly *not* derived from my callsign (`:172-175`) |
| DX Exch1 | always `'599'` (`:287-290`) |
| DX Exch2 | society abbreviation **or** ITU zone string, straight from the history file (`:293-296`) |
| points | same zone **or** non-numeric (HQ station) → 1; else same continent → 3; else → 5; no DXCC record → 1 (`:337-348`) |
| multiplier | `Exch2` verbatim (`:351`) |
| special | `OnSetMyCall` fails when my call is not a valid DXCC call (`:185-197`) |

Notes: the same field carries a number *or* an abbreviation, so validation cannot assume numeric;
scoring switches on “is this numeric”, which the expression language needs as a first-class test.

---

## What these three demand of the schema (T3 acceptance targets)

1. **Two exchange fields**, each with caption, pattern, max length, and a semantic type — the
   `ExchFields.pas:43-70` table is already exactly this shape.
2. **Entry form ≠ sent form.** SS enters `[nr] prec chk sect` as one string and sends it as two.
   The schema needs a `parse` step (whole-string regex with named groups) plus a mapping from
   groups to Exch1/Exch2.
3. **Call-history parse spec** per contest: filename, delimiter, whether the header defines columns,
   skip prefixes, per-column validation, dedupe, and a required-DXCC gate for my own call.
4. **Generation rules** per exchange piece: constant (`599`), history column, serial generator,
   weighted choice, and deliberate perturbation (leading zero / ±1 / table neighbour).
5. **Validation rules** for operator input: whole-string pattern + token-level checks + the ability
   to extract a corrected callsign out of the exchange text.
6. **Scoring** as an expression over (my entity/continent, his entity/continent, zone equality,
   call suffix) with DXCC built-ins (`callZone`, `callCountry`, …).
7. **Multiplier expression**, including composite keys (`ZN-3;Germany`).
8. **Message overrides** per contest (CQ wording).
9. **Capability flags**: Farnsworth (`Contest.pas:200`), requires-my-DXCC, `/MM` handling.

### Gaps found against the v1 built-in set (spec §5) — to resolve in T3
- `GetAlternateSection` (`ArrlSS.pas:507-517`) needs a **table-neighbour** lookup; v1 has no way to
  index a fixed list, so the section table must either become a built-in or be expressed as a
  static map in the config.
- Weighted random choice (`ArrlSS.pas:422-430`) and the 5 % / 10 % perturbation rules need a
  **deterministic RNG hook** — a random built-in would make evaluation non-deterministic, which
  ADR-0003 forbids; the seeded value must be supplied by the runtime, not by the expression.
- `IsNum` (`IaruHf.pas:355-366`) must exist as a numeric test in the language (or be expressible
  as `matches(s, "^[0-9]+$")` — verify before adding a built-in).

---

## NCJ NAQP — `NaQp.pas` (T10, the first per-contest ticket)

| | |
|---|---|
| call history | `NAQPCW.txt` (15 467 lines), comma, **no header** (`!!Order!!` is a skip prefix, `:98-99`); columns `call,name,state[,usertext]`; `tl.Count > 2` (`:114`); empty call/name dropped (`:122-123`); name > 12 dropped (`:124`); **state may be empty** (DX, `:125`); no dedupe, no DXCC gate (`OnSetMyCall` only warns, `:178-210`) |
| my exchange | one *name* box and one *state* box (`Ini.pas:129-136`: `<name> [<state\|prov\|dxcc-entity>]`); the default exchange string is `ALEX ON` (`Ini.pas:133`); name `[A-Z][A-Z]*` ≤ 10, state `[0-9A-Z/]*` ≤ 6 (`ExchFields.pas:45,66`) |
| DX Exch1 | the history's `Name`, verbatim (`:439-442`) |
| DX Exch2 | the history's `State`, verbatim — **empty for a DX station**, which then sends its name only (`:215-216`, `:445-448`) |
| realism quirks | none; the station's file values are sent (`NaQp.pas` has no corruption) |
| points | constant 1 (`:397`) |
| multiplier | `Exch2` when the call is local (`continent == 'NA' or entity == 'Hawaii'`, `:400-421`); a DX station contributes none |
| messages | none overridden (base wording) |
| capability flags | no Farnsworth; no DXCC-required |
| verdict labels | name mismatch `leNAME` (`Log.pas:860`); state mismatch `leST` (`Log.pas:944`), and `DX` is accepted for an empty second piece (`:945-949`) |

Implemented in `contests/naqp.json` (ADR-0010); `NAQPCW.txt` is packaged unmodified.

## K1USN Slow Speed Test — `CWSST.pas` (T11, the second per-contest ticket)

| | |
|---|---|
| call history | `K1USNSST.txt` (15 045 lines), comma via `StrictDelimiter` (`:356-364`), **no header** (`!!Order!!` is a skip prefix, `:80-81`); columns `call,name,exch1[,usertext]` = call, name, QTH (`:54-58`); `tl.Count >= 3` (`:84`); **call, name and QTH all required** (`:93-95`); name > 10 and QTH > 5 dropped (`:96-97`); the loader sorts the *lines* (`:77`) because it binary-searches by call (`:163`) — irrelevant to a map; no dedupe, no DXCC gate |
| my exchange | `etOpName` + `etGenericField`, captions `('Name', 'State/Prov/DX')`, default `BRUCE MA` (`Ini.pas:166-176`); name `[A-Z][A-Z]*` ≤ 10, QTH `[0-9A-Z]*` ≤ 12 (`ExchFields.pas:45,54`) |
| DX Exch1 | the history's `Name`, upper-cased (the field is also `station.OpName`, `:174-175`) |
| DX Exch2 | the history's third column — the state/province, or the **literal `DX`** for a non-US/VE station (`:90,176`) |
| realism quirks | none on the exchange. Station-side wording only: `R ` prefix 10 %, greeting 20 %, `NR?`/`NAME?`/`ST?` at random (`:191-222`, `:258-272`) — station-script code, no schema key |
| points | constant 1, regardless of QTH (`:322`) |
| multiplier | `Exch2`; when `TrueExch2 == 'DX'` and the call is in DXCC, the entity name instead (`:326-330`) |
| messages | `msgCQ` → `CQ SST <my>` (`:190`), `msgTU` → `TU <my>` (`:200`), `msgLongCQ` → `CQ CQ SST <my> <my>` (`:223`, sent by the QRM station) |
| capability flags | `BFarnsworthEnabled := true` (`:121`); no DXCC-required |
| verdict labels | name mismatch `leNAME` (`Log.pas:860`); QTH mismatch `leQTH` (`Log.pas:917-920`, the `scSst` branch of `etGenericField`) |
| data, measured | 10 705 usable of 15 045 lines (4 311 rows have no name, 16 no QTH, 2 fail the shared call grammar); 701 DX records, 65 distinct QTH; exactly one DX call has no DXCC record (`LU1AW/D`) |

Implemented in `contests/k1usnsst.json` (ADR-0011); `K1USNSST.txt` is packaged unmodified.

## JARL ALL JA — `AllJa.pas` (T12, the third per-contest ticket)

| | |
|---|---|
| call history | `JARL_ALLJA.TXT` (2 542 lines), comma via `StrictDelimiter` (`:65-66`), skips `!!Order!!` and `#` (`:75-76`) — the shipped file's first four lines are a `#` banner; columns `call,exch1[,usertext]` = `rec.Call/Number/UserText` (`:85-88`); **`tl.Count > 1` is the whole row rule** (`:80`), i.e. two cells; `call` and `exch1` must both be non-empty (`:89-90`); `usertext` stored only when `tl.Count >= 3` (`:88`); **no call validation at all**; no dedupe, no DXCC gate, `ParamStr(1)` file (`:72`) |
| my exchange | `etRST` + `etJaPref`, default `5NN 10H`, msg `'RST <Pref><Power>' (e.g. 5NN 10H)` (`Ini.pas:178-185`); captions `'RST'` / `'Number'` (`ExchFields.pas:44,62`); RST `[1-5E][1-9N][1-9N]` L3 (`:44`), number `([0-9AOTN]*)([LMHP])` L4 (`:62`), both anchored as `'^(' + R + ')$'` (`Contest.pas:310`) |
| DX Exch1 | **always the literal `'599'`** (`:220-223`) — not `5NN` |
| DX Exch2 | the history's second column, `<pref><power>`, upper-cased at load (`:85-86,225-227`) — e.g. `14H`, `112M` |
| format facts | `<pref>` is 2 digits (02–49) or 3 digits (101–114, Hokkaido promotion bureaus); `<power>` is one of `L/M/H/P` (`:181-189`) |
| points | constant 1 (`:206`) |
| multiplier | `Exch2` with a trailing `L`/`M`/`H`/`P` **stripped** (`Copy(S,1,Length(S)-1)`); if the last character is not one of those four, the whole string (`:190-207`) |
| messages | **none** — `AllJa.pas` has no `SendMsg` override, so the shared base wording applies |
| capability flags | `TALLJA.Create` (`:106-111`) sets neither `BFarnsworthEnabled` nor a DXCC requirement |
| verdict labels | RST mismatch `leRST` (`Log.pas:859`); number mismatch `leNR` (`Log.pas:942`, `etJaPref`) — **not** `leST`/`leQTH` |
| data, measured | 2 538 data rows; **0 lost to the shared call grammar** (unlike SST's 2); power letters M 1 528 / H 896 / L 114 / **P 0**; 2 441 numbers are 3 characters and 97 are 4 (max 4, so the box's `L: 4` fits every row); 59 distinct prefectures, 59 distinct multipliers, all digits |

Implemented in `contests/allja.json` (ADR-0012); `JARL_ALLJA.TXT` is packaged unmodified
(31 462 bytes, sha256 `d7952200…`). The multiplier is the port's first `substr` in a document:
`substr(s, 1, len(s) - 1)` because the start is 1-based and the third argument is a length, and
`matches(s, '[LMHP]$')` because `Evaluator.matches` is a *search*, not a whole-string match
(ADR-0012 §3).

### Data inventory for the remaining per-contest tickets (verified 2026-10-20)

| Asset | Used by | State |
|---|---|---|
| `CQWWCW.txt` / `SSCW.txt` / `IARU_HF.txt` | CQ WW / ARRL SS / IARU HF | packaged (T3/T6) |
| `NAQPCW.txt` | NCJ NAQP | packaged (T10) |
| `K1USNSST.txt` | K1USN SST | packaged (T11) — `call,name,qth`; multiplier is the QTH, or the DXCC entity when the QTH is `DX` (`CWSST.pas:326-330`) |
| `ARRLDXCW_USDX.txt` | ARRL DX (`ArrlDx.pas`) | **not packaged** — needs the my-exchange branch (§1 of ADR-0010) |
| `JARL_ALLJA.TXT` (2 542 lines, `call,exch1[,usertext]`, `#` banner) | JARL ALL JA (`AllJa.pas:72-100`) | packaged (T12) — RST `599` + `<pref><power>`; no serial; 1 point; multiplier = `Exch2` minus a trailing `L/M/H/P` (`:190-207`) |
| `JARL_ACAG.TXT` (2 551 lines, `call,exch1[,usertext]`) | JARL ACAG (`Acag.pas:72-100`) | **not packaged** — RST + `<city\|gun\|ku><power>`; no serial |
| `CWOPS.LIST` (6 344 lines, header-driven `!!Order!!,Call,Name,Exch1,UserText`) | CWops CWT (`CwOps.pas:74`) | **not packaged** — name + CWT number/state/country from the file; no serial |
| `FDGOTA.txt` (17 330 lines, `!!Order!!,Call,Exch1,Sect,UserText`) | ARRL Field Day (`ArrlFd.pas:224`) | **not packaged** — class + section from the file; the two-pass club-name logic (`:227-260`) is engine code |
| `HstResults.txt` | `Main.pas` (HST results import) | empty file; HST also needs the serial fix (F8) |
| — | CQ WPX | no history → `MASTER.DTA`; needs F8 (the once-computed serial) |

> **T11 correction to the T3 inventory.** The rows above for ALL JA, ACAG, CWops CWT and Field
> Day used to read "no history → `MASTER.DTA`" (and `FDGOTA.txt` "nothing — `grep` finds no
> Pascal reference"). That was wrong: each of those contests **does** load its own file, by the
> absolute path at `ParamStr(1) + <name>` — `AllJa.pas:72`, `Acag.pas:72`, `CwOps.pas:74`,
> `ArrlFd.pas:224`. The files ship in `upstream/MorseRunnerCE/`. Only CQ WPX (and the serial
> contests WPX/HST) genuinely has no history and no serial-free path.
