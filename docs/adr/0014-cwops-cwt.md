# ADR-0014 — CWops CWT as the fifth per-contest ticket

Status: **accepted by delegation** (T14, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1), ADR-0005 (the QSO engine's seams), ADR-0007
(determinism), ADR-0010 (the first per-contest ticket), ADR-0011 and ADR-0012 (whose shapes this
one follows) and ADR-0013 (the ticket before it).

T14 is spec §11's fifth row of "one ticket per remaining contest (11 configs + behaviour checks)".
Like T10–T13 it adds **no** app capability and **no** schema field: the picker, the settings
field and `SessionPlanner` already exist. It is a document, an asset, six behaviour checks and
**one** engine signature change (§4).

CWT is the last schema-v1 contest with its own call history. It is also the first document whose
second sent piece is three different kinds of value in one field (a member number, a state or a
country prefix) and whose multiplier is not derived from the exchange at all — it is the worked
call. Those two facts are why it is not simply a copy of SST's or ACAG's document.

## 1. The contest: CWops CWT, and why it is this one

The same four criteria T10 used, applied to what was left after T13
(`docs/contests/exchange-facts.md`, T14's updated inventory):

| Criterion | CWops CWT | ARRL Field Day | ARRL DX CW | CQ WPX / HST |
|---|---|---|---|---|
| an upstream call history to package unmodified | **yes** — `CWOPS.LIST` (6 344 lines) | yes — `FDGOTA.txt` | yes | WPX: none; HST: an empty results file |
| schema v1 only (no new capability) | **yes** — two text fields and one expression | **no** — the two-pass club-name logic is engine code (`ArrlFd.pas:227-260`) | **no** — `myCountry` is not in `exchange.sent.*` (ADR-0010 §1) | **no** — F8 (the computed-once serial) |
| independent of F8 | yes — no serial | yes | yes | no |
| decidable behaviour checks | yes | mostly | yes | mostly |

CWT is the only remaining schema-v1 contest with a call history, and T13's §34 named it as this
ticket. It is larger than ACAG (6 290 records against 2 547) and it is **not** a twin: the
multiplier is the call, the second piece is a mixed-format field, and its verdict label depends
on the true value — the one genuinely new engine question left in the series.

## 2. What the config is

`core-contest/src/main/resources/contests/cwops.json`, schema v1, registered in `manifest.json`:

| Part | Value | Primary source |
|---|---|---|
| id / name | `cwops` / `CWOPS CWT` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:106` |
| duration | default 30, min 1, max 60 | `Ini.Duration` default (`Ini.pas:263`); see §5 |
| my fields | `name` (text, `Name`), `exch` (text, `Exch`) | `Ini.pas:106-114` (`etOpName` + `etGenericField`; captions `('Name', 'Exch')`) |
| my defaults | `{"name": "DAVID", "exch": "123"}` | `Ini.pas:112` `ExchDefault: 'DAVID 123'` |
| validation | name `^[A-Z][A-Z]*$`, ≤10; exchange `^[0-9A-Z]*$`, ≤12 | `ExchFields.pas:45` (L10), `:54` (L12), anchored by `Contest.pas:310` |
| their | `hisName` ← history `name`, `hisExch` ← history `exch`; `exch1 = upper(hisName)`, `exch2 = upper(hisExch)` | `CwOps.pas:168-173` |
| scoring | points `1`; multiplier = `theirCall` | `CwOps.pas:245-249` |
| call history | `CWOPS.LIST`, comma, skip `#`, **header** `!!Order!!` naming `Call/Name/Exch1/UserText`, ≥3 fields, call+name+exch required, no dedupe, no DXCC gate | `CwOps.pas:50-120` |
| messages | `CQ` → `CQ CWT <my>`, `longCQ` → `CQ CQ CWT <my> <my>` | `CwOps.pas:181-197` |
| capabilities | `farnsworth: false`, `requiresMyDxcc: false` | `TCWOPS.Create` (`CwOps.pas:113-118`) sets neither |

The call-history asset is copied **unmodified** from `upstream/MorseRunnerCE/CWOPS.LIST` into
`app/src/main/assets/` (190 371 bytes, sha256 `06ae0020…`; MPL-2.0, spec §9), keeping upstream's
file name and case (Android assets are case-sensitive).

### The header is not a skip prefix

`CwOps.pas:76-77` skips a line that starts with `!!Order!!` **and** a line that starts with `#`,
because the Pascal parses positionally and the marker line is a comment to it. Schema v1 has a
dedicated `callHistory.header` mechanism (ADR-0004), and `CallHistoryFile` runs the skip-prefix
test **before** the header branch — so listing `!!Order!!` as a skip prefix makes the header
invisible and every data row is then parsed against a header that was never seen. On the first
attempt the real file was rejected with *"has no usable records"*: the marker must be listed only
in `header.marker`, and `skipPrefixes` must contain `#` alone. That is what IARU HF's config
already does, and `CwopsTest` now pins it.

### Measured facts about the data (used by the tests and this ADR)

- 6 344 lines: one `!!Order!!` header, a nine-line `#` banner and **6 334 data rows**;
- upstream's rule table (`tl.Count >= 3`, call/name/exch non-empty) keeps **6 293 rows over
  6 292 distinct calls**: `W1AL,Scott,FL,Cape Coral FL` is in the file **twice, byte for byte**;
- the port's map keeps one of the duplicate rows and the shared callsign grammar drops
  **2** more (`F/G7BED`, `I/W9CF` — one-letter portable prefixes), so `CallHistoryFile` holds
  **6 290 records** (measured through the real loader, not counted by hand);
- exchanges: **3 055 numeric** (CWT member numbers) and **3 235** state/province or country
  prefixes, over **2 814 distinct exchange values**;
- exchange lengths 1 char 193 / 2 chars 3 048 / 3 chars 695 / 4 chars 2 354 — maximum **4**;
  name lengths 1 char 1 … 9 chars 10 — maximum **9**;
- therefore the loader's two caps (`length(name) > 10`, `length(exch) > 5`, `CwOps.pas:94-95`)
  **never fire on the shipped file** (§8.1);
- four rows spell the exchange in lower case (`AC8SI,mi`, `K0ARS,mo`, `K9GDF,Wi`, `WB9IXS,Wi`);
  upstream upper-cases at load (`CwOps.pas:88`) and the document's `upper(hisExch)` does the same,
  so the sent exchange is uppercase while the parsed record keeps the file's case.

## 3. The multiplier is the worked call — and it must not touch the exchange

`CwOps.pas:245-249`:

```pascal
function TCWOPS.ExtractMultiplier(Qso: PQso) : string;
begin
  Qso^.Points := 1;
  Result:= Qso^.Call;
end;
```

so the document is:

```json
{ "points": "1", "multipliers": [ { "key": "theirCall" } ] }
```

The "unique calls" count is not implemented anywhere: `QsoLog` accumulates multiplier keys in a
`LinkedHashSet`, and every contact contributes exactly one key — the call. A hand-checked log in
`CwopsRulesTest` pins the resulting arithmetic: five contacts (`3B9FR`, `4O3A`, `N5OT`, `K1ABC`,
`W1AL`) are five points and five multipliers = 25, and the two stations that both sent `ND`
(`N5OT`, `K1ABC`) are two multipliers, not one. A sixth contact with a call already in the log is
refused as `DUP` and changes nothing.

This is the first contest in the series whose multiplier expression reads only `theirCall`.
CWT declares no `their.parse`: nothing needs a `their<Group>` identifier, and
`ExtractMultiplier` never looks inside the exchange.

## 4. One engine touch: the second field's label depends on the true value

`Log.pas:911-918`:

```pascal
etGenericField:
  case Ini.SimContest of
    scCwt:
      if TrueExch2 <> Exch2 then
        if IsNum(TrueExch2) then
          Exch2Error := leNR
        else
          Exch2Error := leQTH;
```

Upstream labels a mis-copied CWT exchange **`NR` when the station sent a member number and `QTH`
when it sent a state, province or country prefix**. `ContestRules.labelFor` was a pure function of
the field (its type and key); the value was not an input.

**Decision (a): pass the true value in.** `labelFor(field, trueValue: String? = null)`, and one
new branch:

```kotlin
field.key == "exch" -> if (isNumeric(trueValue)) QsoError.Number else QsoError.Qth
```

with `isNumeric` being upstream's `IsNum` (`CwOps.pas:263-276`: non-empty, ASCII digits only).
`judge` already had the true value at both call sites (the per-field branch iterates
`truth.fields`; the piece branch computes `trueValue` before comparing), so the change is two
arguments and one branch — no schema key, no new field type, no change to `sameValue`.

**Backward compatibility.** The argument defaults to `null`, so every existing mapping is
untouched, and no other document declares the key `exch`. `labelFor` is private and `judge` is
its only caller, so nothing outside this file can be affected; `SstRulesTest`,
`NaqpRulesTest` and `AcagRulesTest` re-run unchanged and stay green.

**Why not (b), a key-only label** (always `NR`, or always `QTH`): the shipped file is almost
evenly split — 3 055 numeric against 3 235 non-numeric — so either choice would print the wrong
label on about half of all mis-copied exchanges. The error column is the operator's cue for what
to copy again; a label that is wrong half the time is worse than no label.

**Why not (c), a schema key** (a per-value label table, or a `labelExpression`): that is a schema
change, which a per-contest ticket does not own (ADR-0004's extension discipline). If a future
contest needs a genuinely arbitrary label rule, that is its own ticket with its own ADR.

`QsoError.Name`'s documentation already listed CWT among the `leNAME` contests, and `Log.pas:860`
(`etOpName: … leNAME`) needed no change: the name box is a text field whose `labelFor` branch
already returns `NAME`.

**Why the key is `exch`.** The upstream field type is `etGenericField` and the caption upstream
shows is `Exch` (`Ini.pas:110`, `ExchFields.pas:54`). Reusing `qth` (SST's key) would attach
SST's unconditional `QTH` label to a field that can hold a member number, and would make the key
lie about a state or a prefix; a value-specific key (`nr`, `state`) would lie about the other
two. The key follows the upstream *type* and the caption, the same rule T12 used for `pref` and
T13 for `city`.

## 5. Duration: no artifact gives CWT's real window, so the clamp is the majority's

`CwOps.pas` does not override `Ini.Duration`; `Ini.pas` carries only the global default
(`Duration: integer = 30`, `:263`) and the competition timer (`CompDuration = 60`, `:267`).
Nothing in `docs/`, in the upstream tree, or in the shipped data states how long a CWT session
is. Rather than write a number from memory, the block is `{ default: 30, min: 1, max: 60 }` — the
same clamp every earlier document uses (ADR-0010 §5's F13). **This is a declared gap, not a
football score**: if a future artifact states the real window, only `duration.max` changes.

## 6. `capabilities.farnsworth: false` is faithful

`TCWOPS.Create` (`CwOps.pas:113-118`) never sets `BFarnsworthEnabled`, so the document says
`false`. Nothing reads the flag yet either way (ADR-0011 §4's Bucket B finding is unchanged and
still not this ticket's).

## 7. Determinism

ADR-0007 §6's contract is unchanged. CWT declares no dice (`their.dice` is empty) and no serial,
so its roster draws exactly the same streams as CQ WW's, ALL JA's and ACAG's; the same seed and
settings replay the same session, and `CwopsTest`/`CwopsSessionTest` both assert a replay. The
device run confirmed the resume path regenerates the same station from the snapshot's seed after
a force-stop.

## 8. Deviations and known gaps, declared

1. **The loader's two length caps are not expressible.** `CwOps.pas:94-95` drops a name longer
   than 10 and an exchange longer than 5; schema v1 has no column-length concept (the operator
   box's `L: 10`/`L: 12` are validation rules, not loader rules). On the shipped file the caps
   never fire (longest name 9, longest exchange 4), so this is a **latent** difference, not a
   present one. Recorded here rather than given a schema key — the same trade ADR-0011 §8.1 made
   for SST.
2. **The shared call grammar drops two rows upstream would keep.** `CwOps.pas` does no call
   validation; the port's `call` column is the shared `callsign` type. Measured: `F/G7BED` and
   `I/W9CF` (one-letter portable prefixes) fail it, so the port holds 6 290 of upstream's 6 293
   rows. Same class as SST's two lost rows (ADR-0011 §3/§8).
3. **One duplicate call collapses.** `W1AL` is listed twice, byte for byte; upstream's
   `TObjectList` keeps both, the port's map keeps the last (`dedupe: false`). No observable
   difference in generation — the two rows are identical — but the pool is one record smaller.
   This is why the port has 6 292 records before the grammar drops the other two.
4. **The station's exchange is joined with one space, upstream uses two.** `Station.pas:372-373`
   is `Format('%s  %s', [Exch1, Exch2])`; `SessionController` renders
   `"${exch1} ${exch2}".trim()`. This is a display-layer difference that would change every
   contest if "fixed", so it is declared, not changed.
5. **The `R ` prefix is the port's station default, upstream's 10 % variant.** `CwOps.pas:185-192`
   overrides `msgR_NR`/`msgR_NR2` so the station sends the bare exchange 90 % of the time and
   prefixes `R ` only 10 %. The port's station script (`DxOperator`'s `NEED_END`) always sends
   `R <#>` (90 %) or `R <#> <#>` (10 %) — the **base** `Contest.pas:430-431` wording — because
   `messages` has no station-side variant hook. So a CWT station in the port prefixes `R ` on
   every reply where upstream prefixes it on one in ten; SST has the same shape (and additionally
   greets 20 % of the time, `CWSST.pas:202-214`). This is the open gap ADR-0011 §8.3 declared;
   T14 measures it, does not widen it. The device run happened to catch the port's 10 % branch
   (`R LOU 769 LOU 769`), which is the one case where the two behaviours agree.
6. **`longCQ` is declared but currently unread.** Upstream's `msgLongCQ: 'CQ CQ CWT <my> <my>'` is
   the QRM station's line (`:193`, "QrmStation only"), and the port's QRM station hard-codes its
   own wording (`QrmStation.kt`, ADR-0011 §8.8). The declaration is faithful; nothing consumes it
   today.
7. **`userText` is loaded and read by nothing.** Upstream uses it in `GetStationInfo`
   (`CwOps.pas:200-231`) to put `<call> - <text>` in the status bar; the port's status bar shows
   contest/clock/score/rate and has no station-info line (spec §8). 6 195 of the 6 290 rows carry
   text, so this is the largest loaded-and-unread gap in the series — recorded next to
   `exchange.myFields[].send` (§8.9).
8. **No operator exchange editor** (inherited from ADR-0010 §8.1): CWT sends `DAVID 123` whatever
   the operator's name is.
9. **`exchange.myFields[].send` is loaded and unread.** `cwops.json` omits it (as `allja.json`,
   `acag.json` and `naqp.json` do); the value transmitted is `station.exchangeDefaults`
   (`SessionBootstrap.myExchange`). Bucket B finding in `.scratch/progress.md` §33–§35.
10. **Farnsworth is declared (false) but still not wired** (§6).
11. **One band, one mode**: CWT's per-band and QRP categories are out of scope for a
    single-receiver simulation, as with every earlier contest.

## Rejected

- **A key-only label (§4 b).** It would mislabel about half of all mis-copied exchanges; the
  shipped file is 3 055 numeric against 3 235 non-numeric.
- **A schema key for the label (§4 c).** Schema changes belong to the schema, not to a contest
  document (ADR-0004).
- **Reusing SST's `qth` key.** The field can hold a member number; the key would lie, and it would
  attach SST's unconditional `QTH` label.
- **A numeric multiplier or a parsed member number.** The multiplier is the call
  (`CwOps.pas:248`); parsing the number would invent a rule upstream does not have.
- **Putting `!!Order!!` in `skipPrefixes`** because the Pascal does. Schema v1 has a header
  mechanism, and the skip test runs first — the first run of the real loader rejected the file
  because of exactly this (§2).
- **ARRL Field Day or ARRL DX CW now.** Field Day's two-pass club-name pass is engine code
  (`ArrlFd.pas:227-260`); ARRL DX is still blocked on `myCountry` in `exchange.sent.*`
  (ADR-0010 §1).
- **Writing a "real" CWT duration from memory.** §5: no artifact states it; the clamp is the
  honest majority value and the gap is declared.
