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

## JARL ACAG — `Acag.pas` (T13, the fourth per-contest ticket)

| | |
|---|---|
| call history | `JARL_ACAG.TXT` (2 551 lines), comma via `StrictDelimiter` (`:65-66`), skips `!!Order!!` and `#` (`:75-76`) — the shipped file's first four lines are a `#` banner; columns `call,exch1[,usertext]` = `rec.Call/Number/UserText` (`:85-88`); **`tl.Count > 1` is the whole row rule** (`:80`), i.e. two cells; `call` and `exch1` must both be non-empty (`:89-90`); `usertext` stored only when `tl.Count >= 3` (`:88`); **no call validation at all**; no dedupe, no DXCC gate, `ParamStr(1)` file (`:72`) |
| my exchange | `etRST` + `etJaCity`, default `5NN 1002H`, msg `'RST <City\|Gun\|Ku><Power>' (e.g. 5NN 1002H)` (`Ini.pas:187-194`); captions `'RST'` / `'Number'` (`ExchFields.pas:44,63`); RST `[1-5E][1-9N][1-9N]` L3 (`:44`), number `([0-9AOTN]*)([LMHP])` **L7** (`:63`), both anchored as `'^(' + R + ')$'` (`Contest.pas:310`) |
| DX Exch1 | **always the literal `'599'`** (`:220-224`) — not `5NN` |
| DX Exch2 | the history's second column, `<city\|gun\|ku><power>`, upper-cased at load (`:85-86,226-230`) — e.g. `1002H`, `01039M`, `100115M` |
| format facts | `<city>` is 4 digits (0102–4715), `<gun>` 5 digits (01001–47005), `<ku>` 6 digits (010101–430105); `<power>` is one of `L/M/H/P` (`:181-188`) |
| points | constant 1 (`:207`) |
| multiplier | `Exch2` with a trailing `L`/`M`/`H`/`P` **stripped** (`Copy(S,1,Length(S)-1)`); if the last character is not one of those four, the whole string (`:191-207`) — character for character ALL JA's rule |
| messages | **none** — `Acag.pas` has no `SendMsg` override, so the shared base wording applies |
| capability flags | `TACAG.Create` (`:106-111`) sets neither `BFarnsworthEnabled` nor a DXCC requirement |
| verdict labels | RST mismatch `leRST` (`Log.pas:859`); number mismatch `leNR` (`Log.pas:943`, `etJaCity`) — the line right after ALL JA's `etJaPref`, **not** `leST`/`leQTH` |
| data, measured | 2 547 data rows; **0 lost to the shared call grammar**; number lengths 5 chars 1 616 / 6 chars 227 / 7 chars 704 (max 7 = the box's `L: 7`, fitted by every row); power letters M 1 613 / H 780 / L 154 / **P 0**; 807 distinct multipliers, all digits, **including leading zeros** (`7K1AEU/8,01039M` → `01039`) |

Implemented in `contests/acag.json` (ADR-0013); `JARL_ACAG.TXT` is packaged unmodified
(38 526 bytes, sha256 `75d0e1ea…`). The multiplier expression is ALL JA's verbatim, and that is
deliberate: the two Pascal functions are the same code, so one document's semantics pins both
(ADR-0013 §3). The one difference that matters is data: a code like `01039` must stay a
**string**, or the leading zero would fold two multipliers into one.

## CWops CWT — `CwOps.pas` (T14, the fifth per-contest ticket)

| | |
|---|---|
| call history | `CWOPS.LIST` (6 344 lines), comma via `StrictDelimiter` (`:279-286`), **header-driven**: the `!!Order!!,Call,Name,Exch1,UserText,` line names the columns (`:50-56`), and the `#` lines are the banner; `tl.Count >= 3` (`:82`); call, name and exchange all non-empty (`:91-93`); `length(name) > 10` and `length(exch) > 5` dropped (`:94-95`); `usertext` stored only when `tl.Count > 3` (`:89-90`); **no call validation**; no dedupe, no DXCC gate (`ParamStr(1)` file, `:74`) |
| my exchange | `etOpName` + `etGenericField`, default `DAVID 123`, msg `'<op name> <CWOPS Number\|State\|Country>' (e.g. DAVID 123)` (`Ini.pas:106-114`); captions `'Name'` / `'Exch'`; name `[A-Z][A-Z]*` L10 (`ExchFields.pas:45`), exchange `[0-9A-Z]*` L12 (`:54`), both anchored as `'^(' + R + ')$'` (`Contest.pas:310`) |
| DX Exch1 | the record's `Exch1` = the file's **Name** column, upper-cased at load (`:86-88`, `:168-173`) |
| DX Exch2 | the record's `Exch2` = the file's **Exch1** column: a CWT **member number** (digits), or a **state/province** or **country prefix** (`:86-88`, `:172`) |
| format facts | numeric exchanges are member numbers; everything else is a state/province (`FL`, `ND`) or a country prefix (`G`, `4O`, `DL`); measured lengths 1–4 characters |
| points | constant 1 (`:247`) |
| multiplier | **the worked call itself** (`Result:= Qso^.Call`, `:248`), so the log's set union is the "unique calls" count; no `their.parse` |
| messages | `msgCQ` → `CQ CWT <my>` (`:184`), `msgLongCQ` → `CQ CQ CWT <my> <my>` (`:193`, the QRM station only); `msgR_NR`/`msgR_NR2` send `'R '` 10 % of the time (`:185-192`) — a **station-side variant the schema cannot express** (ADR-0011 §8.3) |
| capability flags | `TCWOPS.Create` (`:113-118`) sets neither `BFarnsworthEnabled` nor a DXCC requirement |
| verdict labels | name mismatch `leNAME` (`Log.pas:860`); **the second field's label depends on the true value**: `etGenericField` under `scCwt` prints `leNR` when `IsNum(TrueExch2)` and `leQTH` otherwise (`Log.pas:911-918`) |
| data, measured | 6 334 data rows; upstream's rule table keeps 6 293 over **6 292 distinct calls** (`W1AL` is listed twice, byte for byte); the port's map keeps one and the shared call grammar drops `F/G7BED` and `I/W9CF` → **6 290 records**; 3 055 numeric (member) / 3 235 state-or-prefix exchanges, 2 814 distinct exchange values; longest name 9 (`length > 10` never fires), longest exchange 4 (`length > 5` never fires); four rows spell the exchange in lower case (`AC8SI,mi`) and upstream upper-cases at load |

Implemented in `contests/cwops.json` (ADR-0014); `CWOPS.LIST` is packaged unmodified
(190 371 bytes, sha256 `06ae0020…`), keeping upstream's file name and case. It is the series'
second **header-driven** history (IARU HF was the first), so the `!!Order!!` line must **not** be
a skip prefix — the config lists only `#`. The multiplier is the first one in the series that is
not derived from the exchange at all: it is the call, which is why two stations in the same state
are two multipliers and a duplicate call adds none.

## CQ WPX — `CqWpx.pas` (T15, the sixth per-contest ticket — the first with no call history)

| | |
|---|---|
| call history | **none** — `LoadCallHistory` (`:87-97`) fills `TCallList` from `Master.dta` through `CallLst.pas:40-61`, i.e. the *pool*, and `PickStation` (`:246-249`) returns -1; nothing is parsed per row |
| my exchange | `etRST` + `etSerialNr`, default `5NN #`, msg `'''RST <serial>'' (e.g. 5NN #\|123)'` (`Ini.pas:96-103`); captions `'RST'` (`ExchFields.pas:44`, `[1-5E][1-9N][1-9N]`, L3) and `'Nr.'` (`:53`, `([0-9OTN]+)|(#)`, L4), anchored as `'^(' + R + ')$'` (`Contest.pas:310`) |
| my serial | `Tst.Me.NR` starts at 1 for the start-of-contest mode (`Main.pas:1313-1317` → `:1511-1531`; `#`/non-numeric → 1) and `Inc`s after every saved contact (`Log.pas:764-766`); `Main.pas:599-601` maps `A/E/N → 1/5/9` in the RST box, `:665-673` maps `A/N/T → 1/9/0` in the serial box |
| my sent text | `Format('%d%.3d', [RST, NR])` — report and **three-digit** serial, concatenated — then `599 → 5NN` (`Station.pas:384-394,433`): `5NN001`, one piece |
| DX Exch1 | the literal `'599'` (`:323-326` `getExch1`) |
| DX Exch2 | `IntToStr(GetNR(station))` (`:299-304`). `GetNR` (`:314-320`) is `1 + Round(Random * Tst.Minute * Skills)` for HST and the start-of-contest mode (`DxOper.pas:216-220`), and a WPX entry distribution (`:118-212`) for the mid/end/custom ranges |
| serial range | the start-of-contest setting is `MinVal:1; MaxVal:176`, 1–3 digits (`Ini.pas:234`); `SerialNR` defaults to `snStartContest` (`:238`) |
| points | constant 1 (`:285-288`) |
| multiplier | `Qso.Pfx` (`:289`), which `Log.pas:715` sets to `ExtractPrefix(Qso.Call)` — the WPX prefix: `DL2XYZ → DL2`, `W7SST/6 → W6`, `DL/AA1ZZ → DL0` (`CallsignUtils.pas:58-133`, `DeleteTrailingLetters = True`) |
| messages | **none** — `CqWpx.pas` has no `SendMsg` override, so the base wording applies |
| capability flags | `TCqWpx.Create` (`:100-107`) builds `CallLst` + `SerialNRGen` and sets neither `BFarnsworthEnabled` nor a DXCC requirement |
| station info | `GetStationInfo` (`:264-278`) returns `<call> - <Continent>/<Entity>` for the status bar — a line the port does not have (ADR-0011 §8) |

Implemented in `contests/cqwpx.json` (ADR-0015). It is the first contest in the series with **no
`callHistory` block at all**: `MASTER.DTA` is the pool and every exchange piece is generated from
the document, which is why T15 made the schema key optional. It is also the first contest whose
*operator* exchange changes between contacts — the running serial, F8 — and the first to score on
`callPrefix`, a built-in that existed since T2 and had no caller until now. The serial's
**distribution** is the port's declared approximation (uniform over 1..176) where upstream's is a
function of elapsed time; the *range* is upstream's.

### Data inventory for the remaining per-contest tickets (verified 2026-10-20)

| Asset | Used by | State |
|---|---|---|
| `CQWWCW.txt` / `SSCW.txt` / `IARU_HF.txt` | CQ WW / ARRL SS / IARU HF | packaged (T3/T6) |
| `NAQPCW.txt` | NCJ NAQP | packaged (T10) |
| `K1USNSST.txt` | K1USN SST | packaged (T11) — `call,name,qth`; multiplier is the QTH, or the DXCC entity when the QTH is `DX` (`CWSST.pas:326-330`) |
| `ARRLDXCW_USDX.txt` | ARRL DX (`ArrlDx.pas`) | **not packaged** — needs the my-exchange branch (§1 of ADR-0010) |
| `JARL_ALLJA.TXT` (2 542 lines, `call,exch1[,usertext]`, `#` banner) | JARL ALL JA (`AllJa.pas:72-100`) | packaged (T12) — RST `599` + `<pref><power>`; no serial; 1 point; multiplier = `Exch2` minus a trailing `L/M/H/P` (`:190-207`) |
| `JARL_ACAG.TXT` (2 551 lines, `call,exch1[,usertext]`, `#` banner) | JARL ACAG (`Acag.pas:72-100`) | packaged (T13) — RST `599` + `<city\|gun\|ku><power>`; no serial; 1 point; multiplier = `Exch2` minus a trailing `L/M/H/P` (`:191-207`), a **string** so leading zeros survive |
| `CWOPS.LIST` (6 344 lines, header-driven `!!Order!!,Call,Name,Exch1,UserText`) | CWops CWT (`CwOps.pas:74`) | packaged (T14) — name + CWT number/state/country from the file; 1 point; multiplier = **the worked call**; no serial |
| `FDGOTA.txt` (17 330 lines, `!!Order!!,Call,Exch1,Sect,UserText`) | ARRL Field Day (`ArrlFd.pas:224`) | **not packaged** — class + section from the file; the two-pass club-name logic (`:227-260`) is engine code |
| — | CQ WPX | packaged (T15) — **no history at all**: `MASTER.DTA` is the pool and every exchange piece is generated; RST + a **running serial** (the first contest with one); 1 point; multiplier = the worked call's WPX prefix |
| `HstResults.txt` | `Main.pas` (HST results import) | empty file; HST needs its own score table (F8 is fixed by T15) |

> **T11 correction to the T3 inventory.** The rows above for ALL JA, ACAG, CWops CWT and Field
> Day used to read "no history → `MASTER.DTA`" (and `FDGOTA.txt` "nothing — `grep` finds no
> Pascal reference"). That was wrong: each of those contests **does** load its own file, by the
> absolute path at `ParamStr(1) + <name>` — `AllJa.pas:72`, `Acag.pas:72`, `CwOps.pas:74`,
> `ArrlFd.pas:224`. The files ship in `upstream/MorseRunnerCE/`. Only CQ WPX and HST genuinely have
> no history; both carry the running serial, which T15 fixed for both (ADR-0015 §2).
