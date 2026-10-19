# ADR-0011 — K1USN Slow Speed Test as the second per-contest ticket

Status: **accepted by delegation** (T11, 2026-10-20), under the standing delegation recorded in
ADR-0004 ("不需要我同意，你们爱怎么做怎么做" / "如果有选择题，自动选最优解"). Builds on ADR-0002
(contests are data), ADR-0004 (schema v1), ADR-0005 (the QSO engine's seams), ADR-0007
(determinism) and ADR-0010 (the first per-contest ticket, whose shape this one follows).

T11 is spec §11's second row of "one ticket per remaining contest (11 configs + behaviour
checks)". Unlike T10 it adds **no** app capability: the contest picker, the settings field and
`SessionPlanner` already exist, and SST needs nothing schema v1 does not have. The ticket is a
document, an asset, three behaviour checks and one naming rule.

## 1. The contest: K1USN SST — and why not the next-hardest one

The same four criteria T10 used, applied to what was left (`docs/contests/exchange-facts.md`'s
data inventory):

| Criterion | K1USN SST | ARRL DX CW | The other seven |
|---|---|---|---|
| an upstream call history to package unmodified | **yes** — `K1USNSST.txt` (15 045 lines) | yes — `ARRLDXCW_USDX.txt` | no history → `MASTER.DTA` |
| schema v1 only (no new capability) | **yes** — name + QTH are history columns | **no** — the exchange branches on *my* location (`myCountry` is not in `exchange.sent.*`, ADR-0010 §1) | WPX/HST/Field Day need F8 (the computed-once serial) |
| independent of F8 | yes — no serial | yes | no, or no testable exchange |
| decidable behaviour checks | yes | yes | mostly yes |

So SST is the only remaining contest that is *both* expressible and has real data to be
behaviour-checked. ARRL DX stays blocked on the same schema capability ADR-0010 §1 named.

## 2. What the config is

`core-contest/src/main/resources/contests/k1usnsst.json`, schema v1, registered in
`manifest.json`:

| Part | Value | Primary source |
|---|---|---|
| id / name | `k1usnsst` / `K1USN Slow Speed Test` | `ContestRegistry.BUNDLED_IDS`; `Ini.pas:166-176` |
| duration | default 30, min 1, max 60 | `Ini.Duration` default (`Ini.pas:263`); 60 = the real one-hour sprint |
| my fields | `name` (text), `qth` (text) | `Ini.pas:166-176` (`etOpName` + `etGenericField`, captions `('Name', 'State/Prov/DX')`) |
| my defaults | `{"name": "BRUCE", "qth": "MA"}` | `Ini.pas:172` `ExchDefault: 'BRUCE MA'` |
| validation | name `[A-Z][A-Z]*`, ≤10; QTH `[0-9A-Z]*`, ≤12 | `ExchFields.pas:45` (`etOpName`), `:54` (`etGenericField`) |
| their | `hisName` ← history `name`, `hisQth` ← history `qth`; `exch1 = upper(hisName)`, `exch2 = upper(hisQth)` | `CWSST.pas:88-90,172-177` |
| scoring | points `1`; multiplier `ifElse(theirExch2 == 'DX', callCountry(theirCall), theirExch2)` | `CWSST.pas:322-330` |
| call history | `K1USNSST.txt`, comma, skip `!!Order!!`/`#`, ≥3 fields, `call,name,qth[,userText]`, all three required, no dedupe, no DXCC gate | `CWSST.pas:52-113` |
| messages | `CQ` → `CQ SST <my>`, `TU` → `TU <my>`, `longCQ` → `CQ CQ SST <my> <my>` | `CWSST.pas:190,200,223` |
| capabilities | `farnsworth: true`, `requiresMyDxcc: false` | `CWSST.pas:121` |

The call-history asset is copied **unmodified** from `upstream/MorseRunnerCE/K1USNSST.txt` into
`app/src/main/assets/` (251 728 bytes, sha256 `576391c5…`; MPL-2.0, spec §9). The packaged bytes
are re-parsed by `PackagedAssetsTest`, which also proves the whole chain the device uses.

Measured facts about the data (used by the tests and this ADR):

- 15 045 lines; 4 311 carry **no operator name** (upstream's `Exch1 = ''` rule drops them,
  `CWSST.pas:94`), 16 have no QTH (`:95`) → 10 707;
- the shared callsign grammar (`CallHistoryFile`'s `callsign` column) drops **2 more** — see §8 —
  so the usable set is **10 705**;
- 701 of them are DX records (the QTH is the literal `DX`), 65 distinct QTH values;
- the longest name is 9 characters and the longest QTH 2, so upstream's `> 10` / `> 5` drops
  (`:96-97`) never fire on the shipped file;
- 234 names are spelled in lower case in the file; the config upper-cases them at generation,
  which is what upstream does at load.

## 3. The DX → entity multiplier, and what fail-closed costs

`CWSST.pas:326-330` keeps the QTH as the multiplier, except when the station sent the literal
`DX`: then the DXCC entity is the multiplier **if the call is in the table**, else the literal
`DX` stays. The port's expression language has no "try" and its `call*` built-ins fail closed
(`CALL_DATA_MISSING`, ADR-0003/ADR-0004), so the config cannot express upstream's fallback:

```json
{ "key": "ifElse(theirExch2 == 'DX', callCountry(theirCall), theirExch2)" }
```

`ifElse` is the language's one lazy built-in (`Evaluator.kt`), so the lookup only runs for a
station that actually sent `DX`; a state/province contact never touches DXCC (a `name` + `MA`
contact cannot be affected).

**The price is measured, not assumed**: of the shipped file's 701 DX records, exactly **one**
(`LU1AW/D`) has a call the packaged DXCC table cannot resolve, so that one station scores 0
(the contact is still logged, with the failure string visible) instead of upstream's 1. That is
the port's established behaviour for a built-in with no data — the same rule NAQP's multiplier
follows — and it is accepted rather than papered over:

- a `TEXT` call column would keep the 2 grammar-dropped rows (§8) but would silently drop
  normalisation, i.e. trade a visible, measured 2-row gap for an invisible one;
- a "tolerate a missing lookup" capability is a language extension (T2/ADR-0003 territory), and
  it would be bought for one record in 10 705.

`SstTest` pins the fail-closed behaviour on a fake unknown call; `PackagedAssetsTest` pins the
real cost (`unresolved == {LU1AW/D}`), so a future DXCC table that answers for more calls makes
the claim fail loudly instead of silently improving.

## 4. `capabilities.farnsworth: true` ships as data, and nothing reads it yet

`CWSST.pas:121` sets `BFarnsworthEnabled := true`, so the document declares it: the flag is part
of what the contest *is*, and writing `false` would be a lie about the primary source. But
`MorseTiming` takes its two speeds directly and no settings screen exposes a character speed
(`SessionSettings` has one WPM), so today the flag is **loaded and unread** — the gap T10's
finding already named.

Wiring it means a new operator setting (`FarnsworthCharRate`, `Ini.pas:269`), a settings row, and
`MorseTiming(effectiveWpm, characterWpm)` plumbed through `SessionController`; that is a
settings/audio ticket, not a contest document. Recorded as a Bucket B finding in
`.scratch/progress.md` §32 and declared here, so the next ticket that touches the keyer owns it.

## 5. Duration: the contest's one-hour window is a clamp, not a default

SST is a one-hour sprint in reality, and `CWSST.pas` does not override `Ini.Duration`, so
`duration: { default: 30, min: 1, max: 60 }` — ADR-0010 §5's F13 rule unchanged: the operator's
setting is the source of truth and each contest's block clamps it (the settings screen itself
goes to 240; SST refuses anything over 60). A first run does not adopt 60.

## 6. One engine touch: the `qth` field's log label is `QTH`, not `ERR`

SST's second field is `etGenericField`, whose label in upstream depends on the *contest*
(`Log.pas:917-920`: `scSst` → `leQTH`, `scCwt` → `leNR`/`leQTH` by value, `scIaruHf` → `leZN`/
`leSOC`). `ContestRules.labelFor` already names a field by its config key for `name` and `state`
(T10); this ticket adds one line:

```kotlin
field.key == "qth" -> QsoError.Qth
```

That is naming, not a new rule: the key is the document's own, the enum value already existed
(`QsoError.Qth`, `QTH`), and an unknown TEXT key still falls back to `ERR`. A mis-copied name is
`NAME` (`Log.pas:860`).

## 7. Determinism

ADR-0007 §6's contract is unchanged. SST declares no dice (`their.dice` is empty), so the
roster's draws are exactly the same streams as CQ WW's; the same seed and settings replay the
same session, and `SstTest`/`SstSessionTest` both assert a replay. The device run confirmed the
resume path regenerates the same station from the snapshot's seed.

## 8. Deviations and known gaps, declared

1. **Two rows the shared callsign grammar refuses.** `CWSST.pas` does **no** call validation
   (it only requires three non-empty cells). The port's `call` column is the shared `callsign`
   type (T6/ADR-0006), so `F/OZ1CGQ` (a one-letter portable prefix) and `VYIJA` (not a call) are
   dropped: 10 705 of upstream's 10 707. `RealCallHistoryTest` states both numbers and
   `PackagedAssetsTest` pins the packaged size.
2. **One DX station fails closed on the multiplier** (`LU1AW/D`, §3). Measured, not hypothetical.
3. **The station's own wording is not the Pascal's.** Upstream's SST station randomly prefixes
   `R ` (10 %), adds a greeting (20 %), and asks `NR?`/`NAME?`/`ST?` at random (`CWSST.pas:191-222`,
   `:258-272`). Those live in `StationScript`'s replies, which have no config key (the messages
   map covers `CQ`, `TU`, `longCQ` and the operator's own buttons), so the port always sends
   `NR?` and `R <#>` and never a greeting. A schema key for "station-side wording variants"
   would be a new capability; deferred, declared.
4. **No operator exchange editor** (inherited from ADR-0010 §8.1): SST sends `BRUCE MA` whatever
   the operator's real name and state are. Same follow-up ticket.
5. **A shorter/longer name or QTH is not dropped.** Upstream skips a name > 10 or a QTH > 5
   (`CWSST.pas:96-97`); the schema's TEXT column has no length rule, and the shipped file has no
   such row (measured: 9 and 2). The *entry* validation does enforce both (`ExchFields.pas`), so
   an operator cannot type one; only a future file could carry one.
6. **One band, one mode**: SST's per-band and category rules are out of scope for a
   single-receiver simulation, as with every earlier contest.
7. **Farnsworth is declared but not wired** (§4).
8. **`messages.longCQ` is faithful data that reaches no transmitter yet.** Upstream's
   long-CQ override is the *interfering* station's wording (`CWSST.pas:223` is commented
   "QrmStation only"), and `QrmStation` renders its own hard-coded
   `CQ CQ TEST <my> <my> TEST` without reading the contest's messages map (its KDoc says the
   per-contest pass would add that; T10 had no override to carry). So `longCQ` ships in the
   document — the same status `arrlss.json`'s already had — and an SST QRM episode currently
   says `CQ CQ TEST` where upstream says `CQ CQ SST`. Recorded as a Bucket B finding in
   `.scratch/progress.md` §32; wiring it is a `QrmStation`/`SessionController` change with its
   own test, not a document.

## Rejected

- **ARRL DX CW now.** Needs `myCountry` in `exchange.sent.*` (ADR-0010 §1) — schema, not config.
- **A `text` type for the call column** to keep `F/OZ1CGQ` and `VYIJA`. It would drop the
  normalisation every other history relies on, for 2 rows in 10 705 (§3, §8.1).
- **A nullable `callCountry` / "try" expression.** A language extension for one record (§3).
- **Wiring Farnsworth in this ticket.** It needs an operator setting and keyer plumbing (§4).
- **An `app`-level special case for SST's DX multiplier.** The document says it; the engine runs
  documents (ADR-0002).
- **Naming the second field `state`.** It would print `ST` for a `DX`-station mismatch where
  upstream prints `QTH`, and SST's value is genuinely a state *or* province *or* `DX` (§6).
